-- Internal-only, opaque, provider-neutral correlation reference for a confirmed pending
-- (queued/in_progress) provider invocation. Exists only while its operation is RECONCILING;
-- removed atomically in the same transaction as any RECONCILING -> TERMINAL resolution, before
-- the operation is observable terminal. Never exposed through any public/wire API -- pure backend
-- persistence metadata for a future, separately-authorized manual reconciliation process. V1 is
-- never edited. See receiptanalysis-slice-report.md 6.10.36/6.10.38/6.10.39.

CREATE TABLE receipt_analysis_provider_correlation (
    operation_id           uuid PRIMARY KEY REFERENCES receipt_analysis_operation (operation_id),
    correlation_reference  text NOT NULL,
    recorded_at            timestamptz NOT NULL
);

-- ---------------------------------------------------------------------------------------------
-- Immediate guard -- fires per statement, never deferred: insertion only for a non-tombstoned
-- RECONCILING operation, no update/overwrite ever, deletion only once the operation is TERMINAL.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION guard_provider_correlation_mutation() RETURNS trigger AS $$
DECLARE
    op_lifecycle_state text;
    op_tombstoned_at   timestamptz;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'receipt_analysis_provider_correlation rows are never updated (operation_id=%)', OLD.operation_id;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT lifecycle_state, tombstoned_at INTO op_lifecycle_state, op_tombstoned_at
        FROM receipt_analysis_operation
        WHERE operation_id = NEW.operation_id;

        IF op_lifecycle_state IS DISTINCT FROM 'RECONCILING' OR op_tombstoned_at IS NOT NULL THEN
            RAISE EXCEPTION 'a provider correlation reference can only be inserted for a non-tombstoned RECONCILING operation (operation_id=%)', NEW.operation_id;
        END IF;

        RETURN NEW;
    END IF;

    -- DELETE: only ever removed together with (in the same transaction as, after) the operation
    -- row's own transition to TERMINAL -- never an independent action.
    SELECT lifecycle_state INTO op_lifecycle_state
    FROM receipt_analysis_operation
    WHERE operation_id = OLD.operation_id;

    IF op_lifecycle_state IS DISTINCT FROM 'TERMINAL' THEN
        RAISE EXCEPTION 'receipt_analysis_provider_correlation can only be deleted as part of a transition to TERMINAL (operation_id=%)', OLD.operation_id;
    END IF;

    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER receipt_analysis_provider_correlation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON receipt_analysis_provider_correlation
    FOR EACH ROW EXECUTE FUNCTION guard_provider_correlation_mutation();

-- ---------------------------------------------------------------------------------------------
-- Deferred cross-table invariant -- extends validate_receipt_analysis_operation_invariants (V1)
-- with the correlation-row rule, preserving every existing check unchanged: a non-tombstoned
-- RECONCILING operation may have zero (uncertain/attempt-mismatch path) or one (confirmed pending
-- response) correlation row -- at most one is already guaranteed by the primary key. Every other
-- operation -- RECEIVED, INVOCATION_CLAIMED, TERMINAL, or tombstoned -- must have zero.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION validate_receipt_analysis_operation_invariants(p_operation_id uuid) RETURNS void AS $$
DECLARE
    op                    receipt_analysis_operation%ROWTYPE;
    result_count          integer;
    payload_count         integer;
    reserved_count        integer;
    terminal_effect_count integer;
    reserved_grant_id     uuid;
    reserved_user_id      text;
    terminal_effect       text;
    terminal_grant_id     uuid;
    terminal_user_id      text;
    correlation_count     integer;
BEGIN
    SELECT * INTO op FROM receipt_analysis_operation WHERE operation_id = p_operation_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT count(*) INTO result_count
    FROM receipt_analysis_result WHERE operation_id = p_operation_id;

    IF op.tombstoned_at IS NOT NULL THEN
        IF result_count <> 0 THEN
            RAISE EXCEPTION 'tombstoned operation must have zero receipt_analysis_result rows (operation_id=%)', p_operation_id;
        END IF;
    ELSIF op.lifecycle_state = 'TERMINAL' AND op.terminal_kind = 'SUCCEEDED' THEN
        IF result_count <> 1 THEN
            RAISE EXCEPTION 'TERMINAL SUCCEEDED operation must have exactly one receipt_analysis_result row (operation_id=%, found=%)', p_operation_id, result_count;
        END IF;
    ELSE
        IF result_count <> 0 THEN
            RAISE EXCEPTION 'receipt_analysis_result present for an operation that is not TERMINAL-SUCCEEDED-and-not-tombstoned (operation_id=%)', p_operation_id;
        END IF;
    END IF;

    SELECT count(*) INTO payload_count
    FROM receipt_analysis_payload WHERE operation_id = p_operation_id;

    IF op.lifecycle_state <> 'TERMINAL' AND payload_count <> 1 THEN
        RAISE EXCEPTION 'non-terminal operation must have exactly one payload row (operation_id=%, found=%)', p_operation_id, payload_count;
    END IF;

    IF op.tombstoned_at IS NOT NULL AND payload_count <> 0 THEN
        RAISE EXCEPTION 'tombstoned operation must have zero payload rows (operation_id=%)', p_operation_id;
    END IF;

    SELECT count(*) INTO reserved_count
    FROM credit_ledger_entry WHERE operation_id = p_operation_id AND effect = 'RESERVED';

    IF reserved_count <> 1 THEN
        RAISE EXCEPTION 'operation must have exactly one RESERVED ledger entry (operation_id=%, found=%)', p_operation_id, reserved_count;
    END IF;

    SELECT grant_id, user_id INTO reserved_grant_id, reserved_user_id
    FROM credit_ledger_entry WHERE operation_id = p_operation_id AND effect = 'RESERVED'
    LIMIT 1;

    SELECT count(*) INTO terminal_effect_count
    FROM credit_ledger_entry WHERE operation_id = p_operation_id AND effect IN ('DEBITED', 'RELEASED');

    SELECT effect, grant_id, user_id INTO terminal_effect, terminal_grant_id, terminal_user_id
    FROM credit_ledger_entry WHERE operation_id = p_operation_id AND effect IN ('DEBITED', 'RELEASED')
    LIMIT 1;

    IF op.lifecycle_state = 'TERMINAL' THEN
        IF terminal_effect_count <> 1 THEN
            RAISE EXCEPTION 'TERMINAL operation must have exactly one DEBITED/RELEASED entry (operation_id=%, found=%)', p_operation_id, terminal_effect_count;
        END IF;

        IF (op.terminal_kind IN ('SUCCEEDED', 'FAILED') AND terminal_effect <> 'DEBITED')
            OR (op.terminal_kind = 'FAILED_NO_PROVIDER' AND terminal_effect <> 'RELEASED')
        THEN
            RAISE EXCEPTION 'terminal_kind % does not match ledger effect % (operation_id=%)', op.terminal_kind, terminal_effect, p_operation_id;
        END IF;

        IF terminal_grant_id IS DISTINCT FROM reserved_grant_id OR terminal_user_id IS DISTINCT FROM reserved_user_id THEN
            RAISE EXCEPTION 'terminal ledger entry does not match its RESERVED entry''s grant_id/user_id (operation_id=%)', p_operation_id;
        END IF;
    ELSE
        IF terminal_effect_count <> 0 THEN
            RAISE EXCEPTION 'non-TERMINAL operation must have zero DEBITED/RELEASED entries (operation_id=%)', p_operation_id;
        END IF;
    END IF;

    -- V2: provider correlation availability.
    SELECT count(*) INTO correlation_count
    FROM receipt_analysis_provider_correlation WHERE operation_id = p_operation_id;

    IF NOT (op.lifecycle_state = 'RECONCILING' AND op.tombstoned_at IS NULL) THEN
        IF correlation_count <> 0 THEN
            RAISE EXCEPTION 'only a non-tombstoned RECONCILING operation may have a provider correlation row (operation_id=%, found=%)', p_operation_id, correlation_count;
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION trg_validate_operation_from_provider_correlation() RETURNS trigger AS $$
BEGIN
    PERFORM validate_receipt_analysis_operation_invariants(COALESCE(NEW.operation_id, OLD.operation_id));
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER receipt_analysis_provider_correlation_invariants
    AFTER INSERT OR DELETE ON receipt_analysis_provider_correlation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION trg_validate_operation_from_provider_correlation();
