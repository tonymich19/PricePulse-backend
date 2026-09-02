package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import java.time.Instant

/**
 * One operation stuck in [ReceiptAnalysisOperationLifecycle.Reconciling], carrying everything a
 * resolver needs and nothing more -- never the user, the request id, the content hash or the
 * stored payload, none of which a resolution decision depends on.
 *
 * [correlationReference] is null for the uncertain-invocation and attempt-mismatch paths, which
 * never recorded one: those operations can only be settled by a policy that does not require
 * asking the provider. This is the first and only place [ProviderCorrelationReference] becomes
 * readable outside the persistence layer, deliberately (receiptanalysis-slice-report.md 6.10.39
 * reserved it for exactly this reconciliation work) -- it stays internal backend metadata and must
 * never reach a wire response.
 *
 * [reconcilingSince] is the operation's `updated_at`, i.e. when it entered RECONCILING, and is the
 * only input an age-based policy needs.
 */
data class ReconciliationCandidate(
    val operationId: ReceiptAnalysisOperationId,
    val attemptId: ReceiptAnalysisAttemptId,
    val correlationReference: ProviderCorrelationReference?,
    val reconcilingSince: Instant
)
