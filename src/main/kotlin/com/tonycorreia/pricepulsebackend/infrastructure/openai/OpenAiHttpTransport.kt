package com.tonycorreia.pricepulsebackend.infrastructure.openai

/**
 * Injected into [OpenAiReceiptAnalysisProviderAdapter]. Only a future real implementation (out of
 * this slice) depends on an actual HTTP client -- a deterministic fake is the only implementation
 * used in offline tests. Any send failure before a definitive HTTP response exists (timeout,
 * connection lost/refused, DNS, or any other network exception) must be normalized to
 * [OpenAiHttpTransportResult.NoResponse] -- never thrown. An uncatchable exception escaping [send]
 * would break the orchestration's state machine, which only ever expects a
 * [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome]
 * back, never a transport exception (6.10.21). [kotlinx.coroutines.CancellationException] is the
 * one exception that must always keep propagating, same invariant already in force across the
 * orchestration (6.10.21). See receiptanalysis-slice-report.md 6.10.43.1/6.10.43.2.
 */
fun interface OpenAiHttpTransport {
    suspend fun send(request: OpenAiOutboundRequest): OpenAiHttpTransportResult
}
