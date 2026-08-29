package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderRejectionReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1

/**
 * The single interpretation of an OpenAI Responses payload, shared by
 * [OpenAiReceiptAnalysisProviderAdapter] (a synchronous invocation) and
 * [OpenAiReceiptAnalysisRetrievalAdapter] (a later read of the same invocation). Extracted from
 * the invocation adapter unchanged: a `completed` response must be validated by exactly the same
 * rules whichever way it reaches this backend, and two copies of the literal 21-row table of
 * 6.10.43.3 would eventually disagree.
 *
 * Never reads [OpenAiHttpTransportResult.Received.bodyBytes] when
 * [OpenAiHttpTransportResult.Received.bodyTruncated] is true (6.10.45/6.10.46): a truncated prefix
 * is never interpreted, even if it happens to look like valid JSON.
 */
internal object OpenAiResponseClassifier {

    fun classify(
        attemptId: ReceiptAnalysisAttemptId,
        received: OpenAiHttpTransportResult.Received
    ): ReceiptAnalysisProviderOutcome = when (received.statusCode) {
        400 -> ReceiptAnalysisProviderOutcome.InvocationRejected(attemptId, ProviderRejectionReason.MALFORMED_REQUEST)
        401 -> ReceiptAnalysisProviderOutcome.InvocationRejected(attemptId, ProviderRejectionReason.AUTHENTICATION_REJECTED)
        403 -> ReceiptAnalysisProviderOutcome.InvocationRejected(attemptId, ProviderRejectionReason.ACCESS_FORBIDDEN)
        429 -> classify429(attemptId, received)
        500, 503 -> ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(attemptId, ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE)
        200 -> classify200(attemptId, received)
        else -> ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(attemptId, ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE)
    }

    private fun classify429(
        attemptId: ReceiptAnalysisAttemptId,
        received: OpenAiHttpTransportResult.Received
    ): ReceiptAnalysisProviderOutcome {
        // A truncated body is never trusted for the billing/quota sub-cause -- 6.10.45/6.10.46:
        // a prefix could coincidentally contain a well-formed but stale/incomplete error.code.
        if (received.bodyTruncated) {
            return ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(attemptId, ProviderTerminalFailureReason.RATE_LIMITED)
        }
        val errorCode = parseJsonOrNull(received.bodyBytes())
            ?.get("error")?.get("code")
            ?.takeIf { it.isTextual }?.asText()
        val reason = if (errorCode in BILLING_OR_QUOTA_ERROR_CODES) {
            ProviderTerminalFailureReason.BILLING_OR_QUOTA_EXHAUSTED
        } else {
            ProviderTerminalFailureReason.RATE_LIMITED
        }
        return ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(attemptId, reason)
    }

    private fun classify200(
        attemptId: ReceiptAnalysisAttemptId,
        received: OpenAiHttpTransportResult.Received
    ): ReceiptAnalysisProviderOutcome {
        // A truncated body is never parsed at all -- 6.10.45/6.10.46: a cut prefix can
        // accidentally be syntactically valid JSON on its own, which would misclassify an
        // incomplete response as a real outcome.
        if (received.bodyTruncated) {
            return ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(attemptId)
        }

        val root = parseJsonOrNull(received.bodyBytes())
            ?: return ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(attemptId)

        return when (root.get("status")?.takeIf { it.isTextual }?.asText()) {
            "failed" -> ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(attemptId, ProviderTerminalFailureReason.RESPONSE_FAILED)
            "cancelled" -> ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(attemptId, ProviderTerminalFailureReason.RESPONSE_CANCELLED)
            "incomplete" -> ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(attemptId)
            "queued", "in_progress" -> classifyPending(attemptId, root)
            "completed" -> classifyCompleted(attemptId, root)
            else -> ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(attemptId, ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE)
        }
    }

    private fun classifyPending(attemptId: ReceiptAnalysisAttemptId, root: JsonNode): ReceiptAnalysisProviderOutcome {
        val id = root.get("id")?.takeIf { it.isTextual }?.asText()
        return if (!id.isNullOrBlank()) {
            ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse(attemptId, ProviderCorrelationReference(id))
        } else {
            ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(attemptId, ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE)
        }
    }

    /**
     * Deterministic `output_text` extraction (receiptanalysis-slice-report.md 6.10.43, the 5-step
     * rule fixed after the 179 blocker): walk every `output` element, consider only
     * `type == "message"`/`role == "assistant"` elements whose `content` is an array, a `refusal`
     * anywhere in those elements always wins, and exactly one `output_text` is required -- zero,
     * more than one, or a malformed candidate all fall through to the same invalid outcome below.
     */
    private fun classifyCompleted(attemptId: ReceiptAnalysisAttemptId, root: JsonNode): ReceiptAnalysisProviderOutcome {
        val output = root.get("output")?.takeIf { it.isArray }
            ?: return ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(attemptId)

        val assistantMessageContent = mutableListOf<JsonNode>()
        for (element in output) {
            if (!element.isObject) continue
            if (element.get("type")?.asText() != "message") continue
            if (element.get("role")?.asText() != "assistant") continue
            val content = element.get("content")?.takeIf { it.isArray } ?: continue
            assistantMessageContent.addAll(content)
        }

        val hasRefusal = assistantMessageContent.any { it.isObject && it.get("type")?.asText() == "refusal" }
        if (hasRefusal) return ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(attemptId)

        val outputTextItems = assistantMessageContent.filter { it.isObject && it.get("type")?.asText() == "output_text" }
        if (outputTextItems.size != 1) return ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(attemptId)

        val text = outputTextItems.single().get("text")?.takeIf { it.isTextual }?.asText()
            ?: return ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(attemptId)

        val document = ValidatedReceiptAnalysisResultV1.from(text.toByteArray(Charsets.UTF_8))
            ?: return ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(attemptId)

        return ReceiptAnalysisProviderOutcome.Completed(document)
    }

    private fun parseJsonOrNull(bytes: ByteArray): JsonNode? = runCatching { MAPPER.readTree(bytes) }.getOrNull()

    private val MAPPER = ObjectMapper()
    private val BILLING_OR_QUOTA_ERROR_CODES = setOf(
        "credit_balance_exhausted",
        "organization_spend_limit_exceeded",
        "project_spend_limit_exceeded",
        "organization_usage_limit_exceeded"
    )
}
