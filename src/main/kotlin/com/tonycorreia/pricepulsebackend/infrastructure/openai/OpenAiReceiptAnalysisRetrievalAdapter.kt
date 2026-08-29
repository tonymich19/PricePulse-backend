package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisRetrievalPort

/**
 * Retrieves an already-pending OpenAI response by its id. Offline-safe in exactly the same sense
 * as [OpenAiReceiptAnalysisProviderAdapter]: no HTTP client, no credential loading, no network --
 * everything real arrives through the injected [OpenAiHttpTransport]/[OpenAiCredentialProvider].
 *
 * Reads only. It never sends a prompt, an image or a new invocation, so it cannot consume a second
 * provider invocation for an operation that already spent its single one. Shares
 * [OpenAiResponseClassifier] with the invocation adapter, so a `completed` response retrieved here
 * is validated by exactly the rules that would have applied had it arrived synchronously.
 *
 * Emits no telemetry, deliberately: an [OpenAiInvocationTelemetry] event asserts that an
 * invocation happened, and a retrieval is not one -- recording one here would double-count cost
 * that was already reported when the invocation was made.
 */
class OpenAiReceiptAnalysisRetrievalAdapter(
    private val credentialProvider: OpenAiCredentialProvider,
    private val transport: OpenAiHttpTransport
) : ReceiptAnalysisRetrievalPort {

    override suspend fun retrieve(
        attemptId: ReceiptAnalysisAttemptId,
        reference: ProviderCorrelationReference
    ): ReceiptAnalysisProviderOutcome {
        val credential = credentialProvider.credential()
            ?: return ReceiptAnalysisProviderOutcome.NotInvoked(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE)

        // Null here means the provider-supplied reference is not a safe path segment. Reported as
        // a construction failure rather than attempted: nothing leaves this backend.
        val request = OpenAiOutboundRequest.retrieval(reference, credential)
            ?: return ReceiptAnalysisProviderOutcome.NotInvoked(ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED)

        return when (val result = transport.send(request)) {
            is OpenAiHttpTransportResult.NoResponse ->
                ReceiptAnalysisProviderOutcome.InvocationUncertain(attemptId)
            is OpenAiHttpTransportResult.Received ->
                OpenAiResponseClassifier.classify(attemptId, result)
        }
    }
}
