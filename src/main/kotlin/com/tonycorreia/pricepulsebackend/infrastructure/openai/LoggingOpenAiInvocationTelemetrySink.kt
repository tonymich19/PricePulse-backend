package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory

/** Fixed logger name, bound by `logback.xml`. Never the class name: refactoring must not rebind it. */
internal const val TELEMETRY_LOGGER_NAME = "pricepulse.telemetry.openai"

/** Version of the log line's own format -- distinct from the HTTP envelope and the document schema. */
private const val EVENT_VERSION = 1

private const val EVENT_NAME = "openai_invocation"

/**
 * Deterministic caps on the two fields that arrive from a remote response. A value longer than any
 * legitimate model id or status is not trustworthy, so the key is **omitted** rather than truncated:
 * truncating would publish a plausible-looking but false value.
 */
internal const val MAX_EFFECTIVE_MODEL_LENGTH = 64
internal const val MAX_STATUS_LENGTH = 32

/**
 * The production destination of [OpenAiInvocationTelemetry]: one JSON object per invocation, emitted
 * as a single line so the log is machine-readable without pre-processing
 * (receiptanalysis-slice-report.md 6.10.66).
 *
 * The event is assembled **field by field with an allowlist**, never by serializing the DTO: if a
 * property were added to [OpenAiInvocationTelemetry] later, automatic serialization would publish it
 * to the log with nobody deciding that.
 *
 * **No `try/catch` here, deliberately.** [OpenAiReceiptAnalysisProviderAdapter] is the single
 * boundary that isolates ordinary sink failures and always lets `CancellationException` propagate. A
 * second layer would be a second policy, free to diverge from the first.
 *
 * @param emit injected so tests can assert the exact line; production logs to
 *   [TELEMETRY_LOGGER_NAME], which `logback.xml` binds to a non-additive, message-only appender.
 */
class LoggingOpenAiInvocationTelemetrySink(
    private val emit: (String) -> Unit = { line -> LoggerFactory.getLogger(TELEMETRY_LOGGER_NAME).info(line) }
) : OpenAiInvocationTelemetrySink {

    private val mapper = ObjectMapper()

    override fun record(telemetry: OpenAiInvocationTelemetry) {
        val event = mapper.createObjectNode()
        event.put("event", EVENT_NAME)
        event.put("v", EVENT_VERSION)
        // `.value` explicitly, never toString(): another type in this project uses toString() to
        // redact, so depending on it here would tie the wire format to an unrelated decision.
        event.put("attemptId", telemetry.attemptId.value)
        event.put("correlationPresent", telemetry.correlationPresent)

        telemetry.effectiveModel
            ?.takeIf { it.length <= MAX_EFFECTIVE_MODEL_LENGTH }
            ?.let { event.put("effectiveModel", it) }

        telemetry.status
            ?.takeIf { it.length <= MAX_STATUS_LENGTH }
            ?.let { event.put("status", it) }

        telemetry.usage?.let { event.set<ObjectNode>("usage", usageNode(it)) }

        // Jackson performs the JSON escaping, including control characters -- nothing is concatenated
        // by hand, so a hostile remote `status` cannot break the line's structure.
        emit(mapper.writeValueAsString(event))
    }

    /**
     * A null count omits only its own key. An absent key and an explicit `null` would otherwise be
     * two representations of the same fact, forcing every consumer to handle both.
     */
    private fun usageNode(usage: OpenAiInvocationUsage): ObjectNode {
        val node = mapper.createObjectNode()
        usage.inputTokens?.let { node.put("inputTokens", it) }
        usage.outputTokens?.let { node.put("outputTokens", it) }
        usage.totalTokens?.let { node.put("totalTokens", it) }
        usage.cachedTokens?.let { node.put("cachedTokens", it) }
        usage.cacheWriteTokens?.let { node.put("cacheWriteTokens", it) }
        usage.reasoningTokens?.let { node.put("reasoningTokens", it) }
        return node
    }
}
