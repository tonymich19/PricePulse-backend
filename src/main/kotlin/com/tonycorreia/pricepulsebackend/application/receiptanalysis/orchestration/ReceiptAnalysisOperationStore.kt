package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import java.time.Instant

/**
 * Single port for the operation's persistence + financial lifecycle -- replaces the earlier
 * two-port design (a separate request store and ledger) precisely because that split allowed a
 * crash between "save state" and "debit/release" to leave them out of sync. See
 * receiptanalysis-slice-report.md 6.10.16.B.
 */
interface ReceiptAnalysisOperationStore {

    /**
     * Atomic: if (userId, requestId) does not exist, creates the operation in
     * [ReceiptAnalysisOperationLifecycle.Received], generates a new [ReceiptAnalysisOperationId] +
     * [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId],
     * and reserves credit -- returns Accepted(isNew = true). If it exists with the SAME hash,
     * returns Accepted(isNew = false) with the existing record (same operationId/attemptId as
     * before), no new reservation. If it exists with a different hash, returns HashConflict,
     * never calling the provider nor causing a financial effect. Never a get() followed by a
     * separate save().
     *
     * Takes [StartReceiptAnalysisOperationCommand.image] itself, not caller-supplied hash/size/
     * MIME -- a real (future) PostgreSQL adapter needs the actual bytes to persist as
     * `receipt_analysis_payload` in this same transaction, exactly as the physical schema
     * requires (receiptanalysis-slice-report.md 6.10.22.B/C/G, 6.10.25).
     */
    suspend fun startOrGetExisting(command: StartReceiptAnalysisOperationCommand): StartOutcome

    suspend fun findByRequestId(userId: UserId, requestId: RequestId): RequestLookup

    /**
     * Durable, exclusive reservation of the right to make the single call to
     * `ReceiptAnalysisProviderPort` for this operation. At most one concurrent caller receives
     * Claimed; every other caller (retry, concurrent request, reconciliation sweep before
     * timeout) receives AlreadyClaimedOrResolved and must never call the port.
     */
    suspend fun claimInvocation(operationId: ReceiptAnalysisOperationId): ClaimOutcome

    /**
     * Read-only. Returns at most [limit] non-tombstoned operations in
     * [ReceiptAnalysisOperationLifecycle.Reconciling] whose last update is at or before
     * [notUpdatedSince], oldest first -- never claims, locks or mutates anything, and never a
     * counterpart to [claimInvocation], whose exclusive reservation exists to stop a *second
     * provider invocation*. A reconciling operation already spent its single invocation, so there
     * is nothing left to reserve.
     *
     * Exclusivity is therefore deliberately not offered: two resolvers acting on the same
     * candidate are safe, because [applyOutcome] is the single authority -- it locks the row and
     * answers the loser with [ApplyOutcomeResult.AlreadyResolved], never a second ledger entry.
     *
     * [limit] must be positive.
     */
    suspend fun findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate>

    /**
     * Applies the final outcome in a single atomic operation covering both the record's
     * [ReceiptAnalysisOperationLifecycle] transition and its financial effect -- always together,
     * as one [ReceiptAnalysisOperationLifecycle.Terminal] value, so a resolved operation's result
     * is never lost: this call's own idempotent return, [findByRequestId], and a later
     * `RECONCILING` resolution all read the same persisted [ReceiptAnalysisOperationResult].
     * [ReceiptAnalysisOperationLifecycle.Terminal.ledgerEffect] is always derived from that
     * result, never a value this method (or any caller) supplies separately -- an incompatible
     * pairing cannot be constructed, by any implementation of this interface.
     * Idempotent: a second call for an already-[ReceiptAnalysisOperationLifecycle.Terminal]
     * operationId is a no-op that returns the already-recorded result, never a second ledger
     * entry. Enforces the state machine below; an application not valid for the operation's
     * current lifecycle is rejected -- never throws, never mutates state, result or ledger:
     *
     * - [ReceiptAnalysisOperationLifecycle.Received]: only [OutcomeApplication.FailedNoProvider]
     *   is valid (rejection before any provider call, zero calls ever made) -- terminal with
     *   [ReceiptAnalysisOperationResult.FailedNoProvider] + `RELEASED`. Every other application is
     *   [ApplyOutcomeResult.Rejected] -- it would let a caller record a financial effect without
     *   ever winning [claimInvocation].
     * - [ReceiptAnalysisOperationLifecycle.InvocationClaimed]: any [OutcomeApplication] is valid,
     *   applied once -- `Succeeded`/`Failed`/`FailedNoProvider` go terminal with the matching
     *   [ReceiptAnalysisOperationResult]; `ReconcilingDetected`/`AttemptIdMismatch` go to
     *   [ReceiptAnalysisOperationLifecycle.Reconciling] (no result yet).
     * - [ReceiptAnalysisOperationLifecycle.Reconciling]: only a later terminal resolution is valid
     *   -- [OutcomeApplication.Succeeded]/[OutcomeApplication.Failed]/[OutcomeApplication.FailedNoProvider]
     *   -- applied without a new claim or a new provider call.
     *   [OutcomeApplication.ReconcilingDetected]/[OutcomeApplication.AttemptIdMismatch] are
     *   [ApplyOutcomeResult.Rejected] from here.
     * - [ReceiptAnalysisOperationLifecycle.Terminal]: any application is
     *   [ApplyOutcomeResult.AlreadyResolved], returning the already-persisted result unchanged.
     */
    suspend fun applyOutcome(
        operationId: ReceiptAnalysisOperationId,
        application: OutcomeApplication
    ): ApplyOutcomeResult
}

/**
 * Never a raw content-hash/size/MIME triple supplied independently by the caller -- whichever
 * [ReceiptAnalysisOperationStore] receives this command always derives the hash, size and MIME of
 * the resulting [ReceiptAnalysisOperation] from [image] itself, never from separately-provided
 * fields that could diverge from the actual bytes.
 */
data class StartReceiptAnalysisOperationCommand(
    val userId: UserId,
    val requestId: RequestId,
    val image: PreparedReceiptImage
)

sealed interface StartOutcome {
    data class Accepted(val operation: ReceiptAnalysisOperation, val isNew: Boolean) : StartOutcome
    data object HashConflict : StartOutcome

    /** No grant (free period or purchased) had spare credit; nothing was created. See receiptanalysis-slice-report.md 6.10.22.E. */
    data object InsufficientCredits : StartOutcome

    /** (userId, requestId) belongs to a tombstoned operation; no reservation, claim, or provider call. See 6.10.22.F. */
    data object Tombstoned : StartOutcome
}

sealed interface RequestLookup {
    data class Found(val operation: ReceiptAnalysisOperation) : RequestLookup
    data object NotFound : RequestLookup

    /** The (userId, requestId) once existed but is now tombstoned -- never conflated with NotFound. See 6.10.22.F. */
    data object Tombstoned : RequestLookup
}

sealed interface ClaimOutcome {
    data class Claimed(val operation: ReceiptAnalysisOperation) : ClaimOutcome
    data object AlreadyClaimedOrResolved : ClaimOutcome
}

sealed interface ApplyOutcomeResult {
    data class Applied(val operation: ReceiptAnalysisOperation) : ApplyOutcomeResult
    data class AlreadyResolved(val operation: ReceiptAnalysisOperation) : ApplyOutcomeResult

    /** The application is not valid for the operation's current lifecycle; nothing is mutated. */
    data class Rejected(val operation: ReceiptAnalysisOperation) : ApplyOutcomeResult
}
