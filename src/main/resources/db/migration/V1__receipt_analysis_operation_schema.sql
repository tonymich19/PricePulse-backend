-- Physical contract for receiptanalysis-slice-report.md 6.10.22: the five responsibilities
-- (operation/idempotency, payload, validated result, credit-grant + ledger, tombstone) as
-- separate tables, never conflated into one lifecycle_state. No application connects to this
-- schema yet; this migration and its Testcontainers tests are the whole slice.

-- ---------------------------------------------------------------------------------------------
-- A + B: receipt_analysis_operation -- identity, idempotency key, and lifecycle state.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE receipt_analysis_operation (
    operation_id            uuid PRIMARY KEY,
    user_id                 text NOT NULL,
    request_id              text NOT NULL,
    content_hash            char(64) NOT NULL,
    size_bytes              bigint NOT NULL,
    mime_type               text NOT NULL,
    attempt_id              uuid NOT NULL,
    lifecycle_state         text NOT NULL
        CHECK (lifecycle_state IN ('RECEIVED', 'INVOCATION_CLAIMED', 'RECONCILING', 'TERMINAL')),
    terminal_kind           text
        CHECK (terminal_kind IN ('SUCCEEDED', 'FAILED', 'FAILED_NO_PROVIDER')),
    terminal_failure_reason text,
    tombstoned_at           timestamptz,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,

    -- terminal_kind is set if and only if the operation is TERMINAL -- never a terminal_kind on a
    -- non-terminal row, never a TERMINAL row without one.
    CONSTRAINT receipt_analysis_operation_terminal_kind_matches_state
        CHECK ((lifecycle_state = 'TERMINAL') = (terminal_kind IS NOT NULL)),

    -- Mirrors the Kotlin contract exactly: ReceiptAnalysisOperationResult.Failed carries no
    -- reason, only FailedNoProvider(reason) does. COALESCE avoids 3-valued-logic drift when
    -- terminal_kind is NULL (non-terminal rows) -- those must have a NULL reason too.
    CONSTRAINT receipt_analysis_operation_failure_reason_matches_kind
        CHECK ((COALESCE(terminal_kind, '') = 'FAILED_NO_PROVIDER') = (terminal_failure_reason IS NOT NULL)),

    CONSTRAINT receipt_analysis_operation_user_request_uq UNIQUE (user_id, request_id)
);

-- ---------------------------------------------------------------------------------------------
-- C: receipt_analysis_payload -- the bytes sent to the provider, retained only while an
-- operation might still need them (crash recovery before a claimed invocation resolves).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE receipt_analysis_payload (
    operation_id uuid PRIMARY KEY REFERENCES receipt_analysis_operation (operation_id),
    payload_bytes bytea NOT NULL,
    stored_at     timestamptz NOT NULL
);

-- ---------------------------------------------------------------------------------------------
-- D: receipt_analysis_result -- the validated provider document. bytea, never jsonb: jsonb
-- reformats JSON on write and would not preserve the exact bytes the schema validated.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE receipt_analysis_result (
    operation_id uuid PRIMARY KEY REFERENCES receipt_analysis_operation (operation_id),
    document     bytea NOT NULL,
    stored_at    timestamptz NOT NULL
);

-- ---------------------------------------------------------------------------------------------
-- E: credit_grant -- one row per free-monthly-period or purchased grant. remaining_credits is a
-- transactional projection of credit_ledger_entry, the sole source of truth; kept in sync only
-- through validate_credit_grant_invariants below, never trusted on its own.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE credit_grant (
    grant_id           uuid PRIMARY KEY,
    user_id            text NOT NULL,
    source             text NOT NULL CHECK (source IN ('FREE_MONTHLY', 'PURCHASED')),
    period             text,
    purchase_reference text,
    credits_granted    integer NOT NULL,
    remaining_credits  integer NOT NULL,
    granted_at         timestamptz NOT NULL,

    CONSTRAINT credit_grant_remaining_in_range
        CHECK (remaining_credits BETWEEN 0 AND credits_granted),

    CONSTRAINT credit_grant_free_monthly_has_period
        CHECK (source <> 'FREE_MONTHLY' OR (period IS NOT NULL AND purchase_reference IS NULL)),

    CONSTRAINT credit_grant_purchased_has_reference
        CHECK (source <> 'PURCHASED' OR (purchase_reference IS NOT NULL AND period IS NULL))
);

-- Lazy per-period free grant creation relies on this: no reset job, absence of a period's row IS
-- the reset state.
CREATE UNIQUE INDEX credit_grant_free_monthly_period_uq
    ON credit_grant (user_id, period)
    WHERE source = 'FREE_MONTHLY';

CREATE UNIQUE INDEX credit_grant_purchase_reference_uq
    ON credit_grant (purchase_reference)
    WHERE source = 'PURCHASED';

-- ---------------------------------------------------------------------------------------------
-- E: credit_ledger_entry -- append-only. Never UPDATEd or DELETEd (enforced below).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE credit_ledger_entry (
    entry_id     uuid PRIMARY KEY,
    grant_id     uuid NOT NULL REFERENCES credit_grant (grant_id),
    operation_id uuid REFERENCES receipt_analysis_operation (operation_id),
    user_id      text NOT NULL,
    effect       text NOT NULL CHECK (effect IN ('GRANTED', 'RESERVED', 'DEBITED', 'RELEASED')),
    created_at   timestamptz NOT NULL,

    -- GRANTED entries are about the grant itself, never a specific operation; every other effect
    -- is always about a specific operation.
    CONSTRAINT credit_ledger_entry_operation_matches_effect
        CHECK ((effect = 'GRANTED') = (operation_id IS NULL))
);

CREATE UNIQUE INDEX credit_ledger_entry_granted_uq
    ON credit_ledger_entry (grant_id)
    WHERE effect = 'GRANTED';

CREATE UNIQUE INDEX credit_ledger_entry_reserved_uq
    ON credit_ledger_entry (operation_id)
    WHERE effect = 'RESERVED';

CREATE UNIQUE INDEX credit_ledger_entry_terminal_uq
    ON credit_ledger_entry (operation_id)
    WHERE effect IN ('DEBITED', 'RELEASED');

-- ---------------------------------------------------------------------------------------------
-- Immediate immutability triggers -- fire per statement, never deferred: these protect rules that
-- never depend on the state of any other table.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION reject_ledger_entry_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'credit_ledger_entry rows are append-only (attempted % on entry_id=%)', TG_OP, OLD.entry_id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER credit_ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON credit_ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_entry_mutation();

CREATE FUNCTION reject_grant_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'credit_grant rows are never deleted (grant_id=%)', OLD.grant_id;
    END IF;

    IF NEW.grant_id <> OLD.grant_id
        OR NEW.user_id <> OLD.user_id
        OR NEW.source <> OLD.source
        OR NEW.period IS DISTINCT FROM OLD.period
        OR NEW.purchase_reference IS DISTINCT FROM OLD.purchase_reference
        OR NEW.credits_granted <> OLD.credits_granted
        OR NEW.granted_at <> OLD.granted_at
    THEN
        RAISE EXCEPTION 'credit_grant rows are immutable except remaining_credits (grant_id=%)', OLD.grant_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER credit_grant_immutable
    BEFORE UPDATE OR DELETE ON credit_grant
    FOR EACH ROW EXECUTE FUNCTION reject_grant_mutation();

CREATE FUNCTION reject_result_mutation() RETURNS trigger AS $$
DECLARE
    op_lifecycle_state text;
    op_tombstoned_at   timestamptz;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'receipt_analysis_result rows are never updated (operation_id=%)', OLD.operation_id;
    END IF;

    -- DELETE is only ever the tombstone cleanup, never an independent action: a result may
    -- disappear only together with (in the same transaction as) its operation being tombstoned.
    SELECT lifecycle_state, tombstoned_at INTO op_lifecycle_state, op_tombstoned_at
    FROM receipt_analysis_operation
    WHERE operation_id = OLD.operation_id;

    IF op_lifecycle_state IS DISTINCT FROM 'TERMINAL' OR op_tombstoned_at IS NULL THEN
        RAISE EXCEPTION 'receipt_analysis_result can only be deleted as part of tombstoning a TERMINAL operation (operation_id=%)', OLD.operation_id;
    END IF;

    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER receipt_analysis_result_guard
    BEFORE UPDATE OR DELETE ON receipt_analysis_result
    FOR EACH ROW EXECUTE FUNCTION reject_result_mutation();

CREATE FUNCTION guard_payload_mutation() RETURNS trigger AS $$
DECLARE
    op_lifecycle_state text;
    op_tombstoned_at   timestamptz;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'receipt_analysis_payload rows are never updated (operation_id=%)', OLD.operation_id;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT tombstoned_at INTO op_tombstoned_at
        FROM receipt_analysis_operation
        WHERE operation_id = NEW.operation_id;

        IF op_tombstoned_at IS NOT NULL THEN
            RAISE EXCEPTION 'cannot insert payload for a tombstoned operation (operation_id=%)', NEW.operation_id;
        END IF;

        RETURN NEW;
    END IF;

    -- DELETE
    SELECT lifecycle_state INTO op_lifecycle_state
    FROM receipt_analysis_operation
    WHERE operation_id = OLD.operation_id;

    IF op_lifecycle_state IS DISTINCT FROM 'TERMINAL' THEN
        RAISE EXCEPTION 'receipt_analysis_payload can only be deleted once its operation is TERMINAL (operation_id=%)', OLD.operation_id;
    END IF;

    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER receipt_analysis_payload_guard
    BEFORE INSERT OR UPDATE OR DELETE ON receipt_analysis_payload
    FOR EACH ROW EXECUTE FUNCTION guard_payload_mutation();

-- ---------------------------------------------------------------------------------------------
-- G: cross-table invariants. Deferred (checked at COMMIT, not per-statement) so a single
-- transaction may pass through transiently-inconsistent intermediate states -- e.g. inserting an
-- operation, its payload, and its RESERVED ledger entry as three statements in one transaction.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION validate_receipt_analysis_operation_invariants(p_operation_id uuid) RETURNS void AS $$
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
BEGIN
    SELECT * INTO op FROM receipt_analysis_operation WHERE operation_id = p_operation_id;

    -- A deleted operation is never possible (no DELETE is ever issued against this table by this
    -- schema), but a NULL guard keeps this function total.
    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT count(*) INTO result_count
    FROM receipt_analysis_result WHERE operation_id = p_operation_id;

    -- Result/tombstone rules: exactly one result for a SUCCEEDED, not-yet-tombstoned operation;
    -- zero for any tombstoned operation (SUCCEEDED included -- cleanup already ran); zero for a
    -- FAILED/FAILED_NO_PROVIDER operation (there was never a document to persist); zero while
    -- non-terminal. Never just "a result may exist" -- its presence is required, not optional,
    -- exactly when Succeeded(document) is the persisted outcome.
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

    -- Payload availability: non-terminal operations always have exactly one payload row;
    -- tombstoned operations always have zero; terminal-non-tombstoned may have zero or one.
    IF op.lifecycle_state <> 'TERMINAL' AND payload_count <> 1 THEN
        RAISE EXCEPTION 'non-terminal operation must have exactly one payload row (operation_id=%, found=%)', p_operation_id, payload_count;
    END IF;

    IF op.tombstoned_at IS NOT NULL AND payload_count <> 0 THEN
        RAISE EXCEPTION 'tombstoned operation must have zero payload rows (operation_id=%)', p_operation_id;
    END IF;

    -- Financial-effect cardinality: every operation has exactly one RESERVED entry. grant_id is
    -- uuid, which has no max() aggregate -- credit_ledger_entry_reserved_uq already guarantees at
    -- most one matching row, so a plain LIMIT 1 lookup is safe.
    SELECT count(*) INTO reserved_count
    FROM credit_ledger_entry WHERE operation_id = p_operation_id AND effect = 'RESERVED';

    IF reserved_count <> 1 THEN
        RAISE EXCEPTION 'operation must have exactly one RESERVED ledger entry (operation_id=%, found=%)', p_operation_id, reserved_count;
    END IF;

    SELECT grant_id, user_id INTO reserved_grant_id, reserved_user_id
    FROM credit_ledger_entry WHERE operation_id = p_operation_id AND effect = 'RESERVED'
    LIMIT 1;

    -- credit_ledger_entry_terminal_uq guarantees at most one matching row.
    SELECT count(*) INTO terminal_effect_count
    FROM credit_ledger_entry WHERE operation_id = p_operation_id AND effect IN ('DEBITED', 'RELEASED');

    SELECT effect, grant_id, user_id INTO terminal_effect, terminal_grant_id, terminal_user_id
    FROM credit_ledger_entry WHERE operation_id = p_operation_id AND effect IN ('DEBITED', 'RELEASED')
    LIMIT 1;

    IF op.lifecycle_state = 'TERMINAL' THEN
        IF terminal_effect_count <> 1 THEN
            RAISE EXCEPTION 'TERMINAL operation must have exactly one DEBITED/RELEASED entry (operation_id=%, found=%)', p_operation_id, terminal_effect_count;
        END IF;

        -- Exact terminal_kind <-> effect correspondence, not just cardinality.
        IF (op.terminal_kind IN ('SUCCEEDED', 'FAILED') AND terminal_effect <> 'DEBITED')
            OR (op.terminal_kind = 'FAILED_NO_PROVIDER' AND terminal_effect <> 'RELEASED')
        THEN
            RAISE EXCEPTION 'terminal_kind % does not match ledger effect % (operation_id=%)', op.terminal_kind, terminal_effect, p_operation_id;
        END IF;

        -- grant_id/user_id consistency between the RESERVED entry and the terminal entry.
        IF terminal_grant_id IS DISTINCT FROM reserved_grant_id OR terminal_user_id IS DISTINCT FROM reserved_user_id THEN
            RAISE EXCEPTION 'terminal ledger entry does not match its RESERVED entry''s grant_id/user_id (operation_id=%)', p_operation_id;
        END IF;
    ELSE
        IF terminal_effect_count <> 0 THEN
            RAISE EXCEPTION 'non-TERMINAL operation must have zero DEBITED/RELEASED entries (operation_id=%)', p_operation_id;
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION validate_credit_grant_invariants(p_grant_id uuid) RETURNS void AS $$
DECLARE
    grant_row       credit_grant%ROWTYPE;
    granted_count   integer;
    granted_user_id text;
    reserved_count  integer;
    released_count  integer;
    expected_remaining integer;
BEGIN
    SELECT * INTO grant_row FROM credit_grant WHERE grant_id = p_grant_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT count(*), max(user_id) INTO granted_count, granted_user_id
    FROM credit_ledger_entry WHERE grant_id = p_grant_id AND effect = 'GRANTED';

    IF granted_count <> 1 THEN
        RAISE EXCEPTION 'grant must have exactly one GRANTED ledger entry (grant_id=%, found=%)', p_grant_id, granted_count;
    END IF;

    IF granted_user_id IS DISTINCT FROM grant_row.user_id THEN
        RAISE EXCEPTION 'GRANTED ledger entry user_id does not match credit_grant.user_id (grant_id=%)', p_grant_id;
    END IF;

    -- remaining_credits is a projection: granted minus every RESERVED (spend intent), plus every
    -- RELEASED (returned). DEBITED confirms an already-reserved spend and changes nothing further.
    SELECT count(*) FILTER (WHERE effect = 'RESERVED'), count(*) FILTER (WHERE effect = 'RELEASED')
        INTO reserved_count, released_count
    FROM credit_ledger_entry WHERE grant_id = p_grant_id;

    expected_remaining := grant_row.credits_granted - reserved_count + released_count;

    IF grant_row.remaining_credits <> expected_remaining THEN
        RAISE EXCEPTION 'credit_grant.remaining_credits (%) does not match the ledger-derived projection (%) (grant_id=%)', grant_row.remaining_credits, expected_remaining, p_grant_id;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Thin wrapper triggers -- each just locates the affected operation_id/grant_id and delegates to
-- the two functions above.

CREATE FUNCTION trg_validate_operation_from_operation() RETURNS trigger AS $$
BEGIN
    PERFORM validate_receipt_analysis_operation_invariants(NEW.operation_id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER receipt_analysis_operation_invariants
    AFTER INSERT OR UPDATE ON receipt_analysis_operation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION trg_validate_operation_from_operation();

CREATE FUNCTION trg_validate_operation_from_result() RETURNS trigger AS $$
BEGIN
    PERFORM validate_receipt_analysis_operation_invariants(COALESCE(NEW.operation_id, OLD.operation_id));
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER receipt_analysis_result_invariants
    AFTER INSERT OR DELETE ON receipt_analysis_result
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION trg_validate_operation_from_result();

CREATE FUNCTION trg_validate_operation_from_payload() RETURNS trigger AS $$
BEGIN
    PERFORM validate_receipt_analysis_operation_invariants(COALESCE(NEW.operation_id, OLD.operation_id));
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER receipt_analysis_payload_invariants
    AFTER INSERT OR DELETE ON receipt_analysis_payload
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION trg_validate_operation_from_payload();

CREATE FUNCTION trg_validate_grant_from_grant() RETURNS trigger AS $$
BEGIN
    PERFORM validate_credit_grant_invariants(NEW.grant_id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER credit_grant_invariants
    AFTER INSERT OR UPDATE ON credit_grant
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION trg_validate_grant_from_grant();

CREATE FUNCTION trg_validate_from_ledger_entry() RETURNS trigger AS $$
BEGIN
    PERFORM validate_credit_grant_invariants(NEW.grant_id);

    IF NEW.operation_id IS NOT NULL THEN
        PERFORM validate_receipt_analysis_operation_invariants(NEW.operation_id);
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER credit_ledger_entry_invariants
    AFTER INSERT ON credit_ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION trg_validate_from_ledger_entry();
