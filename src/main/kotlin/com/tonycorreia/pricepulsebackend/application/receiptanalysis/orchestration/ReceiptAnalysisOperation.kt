package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.FailedNoProviderReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1

enum class ReceiptAnalysisLedgerEffect {
    DEBITED,
    RELEASED
}

/**
 * The payload a resolved operation carries -- only reachable through
 * [ReceiptAnalysisOperationLifecycle.Terminal], never lost once recorded. Mirrors the terminal
 * variants of [OutcomeApplication] (never the non-terminal `ReconcilingDetected`/
 * `ReconcilingWithProviderCorrelation`/`AttemptIdMismatch`, none of which ever produce a persisted
 * result here -- a provider correlation reference is durable persistence metadata of its own,
 * never part of this type, 6.10.39).
 */
sealed interface ReceiptAnalysisOperationResult {
    data class Succeeded(val document: ValidatedReceiptAnalysisResultV1) : ReceiptAnalysisOperationResult

    /** Provider confirmed invocation; the response was invalid (6.6 step 4b). */
    data object Failed : ReceiptAnalysisOperationResult

    /** [reason] preserves exactly which [FailedNoProviderReason] occurred -- the call never left
     * this backend, it reached the provider and was rejected outright, or a confirmed response
     * conclusively had no usable result (6.10.37). `RELEASED` is a PricePulse product policy
     * applied uniformly across all 3 categories -- never a claim about provider-side cost. */
    data class FailedNoProvider(val reason: FailedNoProviderReason) : ReceiptAnalysisOperationResult
}

/**
 * Only [Terminal] carries a [ReceiptAnalysisOperationResult] --
 * [Received]/[InvocationClaimed]/[Reconciling] have no field to hold one, so a resolved operation
 * cannot lose or fabricate its result by construction, not by convention of whichever store
 * implements [ReceiptAnalysisOperationStore]. See receiptanalysis-slice-report.md 6.10.19/6.10.20.
 */
sealed interface ReceiptAnalysisOperationLifecycle {
    data object Received : ReceiptAnalysisOperationLifecycle
    data object InvocationClaimed : ReceiptAnalysisOperationLifecycle
    data object Reconciling : ReceiptAnalysisOperationLifecycle

    /**
     * [ledgerEffect] is derived from [result], never a separate constructor parameter -- no
     * constructor or factory in this API can produce an incompatible pairing (e.g. `Succeeded` +
     * `RELEASED`); it is unrepresentable by the type, not merely avoided by convention. See
     * receiptanalysis-slice-report.md 6.10.20.
     */
    data class Terminal(val result: ReceiptAnalysisOperationResult) : ReceiptAnalysisOperationLifecycle {
        val ledgerEffect: ReceiptAnalysisLedgerEffect
            get() = when (result) {
                is ReceiptAnalysisOperationResult.Succeeded -> ReceiptAnalysisLedgerEffect.DEBITED
                is ReceiptAnalysisOperationResult.Failed -> ReceiptAnalysisLedgerEffect.DEBITED
                is ReceiptAnalysisOperationResult.FailedNoProvider -> ReceiptAnalysisLedgerEffect.RELEASED
            }
    }
}

/**
 * [operationId] and [attemptId] are generated together, once, when a new reservation is created
 * -- never reissued on a later lookup of the same (userId, requestId). [lifecycle] is always
 * written whole by the same [ReceiptAnalysisOperationStore.applyOutcome] call, so the terminal
 * result and its financial effect are never observable out of sync, and every reader
 * (`applyOutcome`'s own idempotent return, `findByRequestId`, a later `RECONCILING` resolution)
 * sees the same persisted value.
 */
data class ReceiptAnalysisOperation(
    val operationId: ReceiptAnalysisOperationId,
    val userId: UserId,
    val requestId: RequestId,
    val contentHash: ContentHash,
    val sizeBytes: Long,
    val mimeType: String,
    val attemptId: ReceiptAnalysisAttemptId,
    val lifecycle: ReceiptAnalysisOperationLifecycle
)
