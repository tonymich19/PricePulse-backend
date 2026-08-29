package com.tonycorreia.pricepulsebackend.application.receiptanalysis

/**
 * Retrieves the current state of an invocation already confirmed as pending, identified only by
 * its opaque [ProviderCorrelationReference]. Provider-neutral by construction: no OpenAI type,
 * model identifier or endpoint appears in this signature, exactly like
 * [ReceiptAnalysisProviderPort].
 *
 * Never starts an invocation -- it reads one that already exists. That distinction is the reason
 * this is a separate port instead of a second method on [ReceiptAnalysisProviderPort]: a
 * reconciling operation has already spent its single invocation, and nothing reachable through
 * this interface may spend another.
 *
 * Returns the same [ReceiptAnalysisProviderOutcome] vocabulary the invocation path uses, so one
 * mapper serves both and a `completed` response retrieved here is validated by exactly the rules
 * that would have applied had it arrived synchronously.
 *
 * Implementations normalize every transport failure into an outcome and never throw, except
 * [kotlinx.coroutines.CancellationException], which always propagates -- the same invariant
 * already required of [ReceiptAnalysisProviderPort] and `OpenAiHttpTransport`.
 */
interface ReceiptAnalysisRetrievalPort {
    suspend fun retrieve(
        attemptId: ReceiptAnalysisAttemptId,
        reference: ProviderCorrelationReference
    ): ReceiptAnalysisProviderOutcome
}
