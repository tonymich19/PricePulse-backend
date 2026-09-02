package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId

/**
 * Only-infrastructure cost-telemetry event for a single OpenAI invocation attempt
 * (`infrastructure/openai`, never `application/`) -- exactly the 5 fields below, nothing else.
 * Deliberately never carries
 * [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference]
 * itself: that type exposes `value()` publicly (used by the real persistence layer), so
 * `toString()` redaction alone is not a safe boundary for a log/sink that might call `value()`
 * directly -- [correlationPresent] only signals whether a correlation existed, never its value.
 * [attemptId] alone is enough to correlate this event back to the operation. Never carries the
 * image, raw HTTP bodies, extraction instructions, the OpenAI credential, or a Firebase token.
 * See receiptanalysis-slice-report.md 6.10.55.
 */
data class OpenAiInvocationTelemetry(
    val attemptId: ReceiptAnalysisAttemptId,
    val correlationPresent: Boolean,
    val effectiveModel: String?,
    val status: String?,
    val usage: OpenAiInvocationUsage?
)
