package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttempt
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderPort
import kotlinx.coroutines.CancellationException

/**
 * Offline-safe: no HTTP client/SDK, no network call, no credential loading -- everything real
 * comes from the injected [OpenAiHttpTransport]/[OpenAiCredentialProvider]. This is the only
 * class in the backend allowed to depend on OpenAI transport/credential concerns
 * (receiptanalysis-slice-report.md 6.10.41.1). Never decides credit or terminal state -- that
 * stays exclusively the orchestration's responsibility (`StartReceiptAnalysisUseCase`/
 * `ReceiptAnalysisOperationStore`), unaffected by this adapter. Classification of a received
 * response lives in [OpenAiResponseClassifier], shared with [OpenAiReceiptAnalysisRetrievalAdapter]
 * so both paths interpret an identical payload identically; the credential-absent case of
 * 6.10.43.3 stays here, since only an invocation can lack a credential before sending. [telemetrySink] receives exactly
 * one [OpenAiInvocationTelemetry] event per [OpenAiHttpTransportResult.Received] -- never for
 * [OpenAiHttpTransportResult.NoResponse] or an absent credential -- emitted only after the
 * outcome is already decided, so a sink failure can never influence it (6.10.55).
 */
class OpenAiReceiptAnalysisProviderAdapter(
    private val requestFactory: OpenAiResponsesRequestFactory,
    private val credentialProvider: OpenAiCredentialProvider,
    private val transport: OpenAiHttpTransport,
    private val telemetrySink: OpenAiInvocationTelemetrySink
) : ReceiptAnalysisProviderPort {

    override suspend fun invoke(attempt: ReceiptAnalysisAttempt): ReceiptAnalysisProviderOutcome {
        val credential = credentialProvider.credential()
            ?: return ReceiptAnalysisProviderOutcome.NotInvoked(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE)

        val request = OpenAiOutboundRequest(requestFactory(attempt), credential)

        return when (val result = transport.send(request)) {
            is OpenAiHttpTransportResult.NoResponse ->
                ReceiptAnalysisProviderOutcome.InvocationUncertain(attempt.attemptId)
            is OpenAiHttpTransportResult.Received -> {
                val outcome = OpenAiResponseClassifier.classify(attempt.attemptId, result)
                emitTelemetry(attempt.attemptId, result)
                outcome
            }
        }
    }

    /**
     * Never reads/parses [OpenAiHttpTransportResult.Received.bodyBytes] when
     * [OpenAiHttpTransportResult.Received.bodyTruncated] is true -- same invariant already real
     * for classification (6.10.46): a truncated prefix is never interpreted, even if it happens
     * to look like valid JSON. A sink failure never escapes this call, except cooperative
     * cancellation, which always propagates.
     */
    private fun emitTelemetry(attemptId: ReceiptAnalysisAttemptId, received: OpenAiHttpTransportResult.Received) {
        val telemetry = if (received.bodyTruncated) {
            OpenAiInvocationTelemetry(
                attemptId = attemptId,
                correlationPresent = false,
                effectiveModel = null,
                status = null,
                usage = null
            )
        } else {
            val root = parseJsonOrNull(received.bodyBytes())
            OpenAiInvocationTelemetry(
                attemptId = attemptId,
                correlationPresent = root?.get("id")?.takeIf { it.isTextual }?.asText()?.isNotBlank() == true,
                effectiveModel = root?.get("model")?.takeIf { it.isTextual }?.asText(),
                status = root?.get("status")?.takeIf { it.isTextual }?.asText(),
                usage = root?.get("usage")?.takeIf { it.isObject }?.let(::parseUsage)
            )
        }

        try {
            telemetrySink.record(telemetry)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Exception) {
            // Telemetry is best-effort -- a sink/log failure never affects the outcome, retry, or credit.
        }
    }

    private fun parseUsage(usageNode: JsonNode): OpenAiInvocationUsage {
        val inputDetails = usageNode.get("input_tokens_details")?.takeIf { it.isObject }
        val outputDetails = usageNode.get("output_tokens_details")?.takeIf { it.isObject }
        return OpenAiInvocationUsage(
            inputTokens = nonNegativeLongOrNull(usageNode.get("input_tokens")),
            outputTokens = nonNegativeLongOrNull(usageNode.get("output_tokens")),
            totalTokens = nonNegativeLongOrNull(usageNode.get("total_tokens")),
            cachedTokens = nonNegativeLongOrNull(inputDetails?.get("cached_tokens")),
            cacheWriteTokens = nonNegativeLongOrNull(inputDetails?.get("cache_write_tokens")),
            reasoningTokens = nonNegativeLongOrNull(outputDetails?.get("reasoning_tokens"))
        )
    }

    /**
     * `node.asLong()` alone is not safe here -- Jackson's `NumericNode.asLong()` (and, for a
     * `BigIntegerNode`, `BigInteger.longValue()` underneath it) silently overflows/wraps for an
     * integral value outside the `Long` range, instead of failing -- it could return an
     * arbitrary, possibly-negative-looking-positive value rather than `null`. `canConvertToLong()`
     * is the only check that catches this before ever calling `asLong()`; a field this large is
     * treated exactly like an absent/non-integral/negative one -- `null`, never fabricated.
     */
    private fun nonNegativeLongOrNull(node: JsonNode?): Long? {
        if (node == null || !node.isIntegralNumber || !node.canConvertToLong()) return null
        val value = node.asLong()
        return if (value >= 0) value else null
    }

    private fun parseJsonOrNull(bytes: ByteArray): JsonNode? = runCatching { MAPPER.readTree(bytes) }.getOrNull()

    companion object {
        private val MAPPER = ObjectMapper()
    }
}
