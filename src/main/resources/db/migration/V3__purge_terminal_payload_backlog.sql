-- ---------------------------------------------------------------------------------------------
-- One-off purge of the receipt-payload backlog accumulated before ADR-003.
--
-- Until this version, nothing in the backend ever deleted receipt_analysis_payload. V1 described
-- the table as holding the provider bytes "only while an operation might still need them" and
-- guard_payload_mutation already authorized the delete for a TERMINAL operation, but no code ever
-- issued it -- so every uploaded receipt image was retained indefinitely, by omission rather than
-- by decision. Deleting on terminal resolution is now part of applyTerminalTransition; this
-- migration settles the rows that predate that change.
--
-- Deliberately limited to TERMINAL operations, for two independent reasons: guard_payload_mutation
-- rejects the delete for any other lifecycle state, and a non-terminal operation may still
-- legitimately need its payload for crash recovery. Rows belonging to operations stuck in
-- RECONCILING are therefore NOT removed here -- the reconciliation sweep resolves those, and their
-- payload is then deleted through the ordinary terminal path.
--
-- Idempotent by construction: re-running it deletes nothing, because the rows are already gone.
-- ---------------------------------------------------------------------------------------------

DELETE FROM receipt_analysis_payload p
USING receipt_analysis_operation o
WHERE p.operation_id = o.operation_id
  AND o.lifecycle_state = 'TERMINAL';
