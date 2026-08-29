package com.tonycorreia.pricepulsebackend.infrastructure.openai

/**
 * Injected into [OpenAiReceiptAnalysisProviderAdapter] -- the seam a future destination (a
 * structured log this slice, possibly something else later) implements. [record] is called as a
 * pure side effect; the adapter always isolates a non-cancellable failure from [record] so it can
 * never alter the returned outcome, trigger a retry, or affect credit -- only
 * [kotlinx.coroutines.CancellationException] is ever allowed to propagate out of it. See
 * receiptanalysis-slice-report.md 6.10.55.
 */
fun interface OpenAiInvocationTelemetrySink {
    fun record(telemetry: OpenAiInvocationTelemetry)
}
