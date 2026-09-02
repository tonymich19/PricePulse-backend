package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.FailedNoProviderReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1

/**
 * Combined state transition + financial effect for one [ReceiptAnalysisOperation], applied
 * atomically by [ReceiptAnalysisOperationStore.applyOutcome] -- never as two separate calls.
 * See receiptanalysis-slice-report.md 6.10.16.B.
 */
sealed interface OutcomeApplication {
    /** -> SUCCEEDED + DEBITED */
    data class Succeeded(val document: ValidatedReceiptAnalysisResultV1) : OutcomeApplication

    /** -> FAILED + DEBITED (provider confirmed invocation, response was invalid) */
    data object Failed : OutcomeApplication

    /**
     * -> FAILED_NO_PROVIDER + RELEASED. [reason] is one of 3 categories: a
     * [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason]
     * (the call never left this backend), a [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderRejectionReason]
     * (the application received a definitive HTTP rejection from the provider), or a
     * [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason]
     * (a confirmed response conclusively had no usable result, 6.10.37) -- never confused, since
     * each is only ever constructible from a distinct [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome]
     * variant. Product decision: all 3 release credit (6.10.31/6.10.36) -- a policy choice, not a
     * claim about whether the provider performed model inference or incurred cost in any of them.
     */
    data class FailedNoProvider(val reason: FailedNoProviderReason) : OutcomeApplication

    /** -> RECONCILING, reserve intact, no financial effect yet. Used for [InvocationUncertain]
     * (no HTTP response received) and an attempt-ID mismatch -- never for a confirmed pending
     * response, which carries a [ProviderCorrelationReference] instead ([ReconcilingWithProviderCorrelation]). */
    data object ReconcilingDetected : OutcomeApplication

    /**
     * -> RECONCILING, reserve intact, no financial effect yet -- same non-terminal effect as
     * [ReconcilingDetected], but for a confirmed pending response (`queued`/`in_progress`,
     * `InvocationConfirmedPendingResponse`) whose [reference] is durably persisted only while the
     * operation remains RECONCILING, removed atomically on any terminal resolution. Never exposed
     * through [ReceiptAnalysisOperation]/[ReceiptAnalysisOperationLifecycle]/`StartOutcome`/
     * `RequestLookup`, or any public/wire response -- internal backend persistence metadata only,
     * until a separately authorized manual-reconciliation slice. See
     * receiptanalysis-slice-report.md 6.10.38/6.10.39.
     */
    data class ReconcilingWithProviderCorrelation(val reference: ProviderCorrelationReference) : OutcomeApplication

    /**
     * The provider answered for an attemptId this operation never issued. Never resolves to
     * Succeeded/Failed/FailedNoProvider -- treated as conservatively as [ReconcilingDetected]
     * (RECONCILING, no financial effect), but kept as a distinct, explicitly observable variant
     * so a future orchestrator can escalate the violation instead of silently absorbing it. Never
     * carries a [ProviderCorrelationReference], even for a mismatched pending response -- a
     * mismatch is never trusted enough to persist anything about it.
     */
    data class AttemptIdMismatch(
        val expectedAttemptId: ReceiptAnalysisAttemptId,
        val receivedAttemptId: ReceiptAnalysisAttemptId
    ) : OutcomeApplication
}
