package com.tonycorreia.pricepulsebackend.infrastructure.openai

/**
 * Injected into [OpenAiReceiptAnalysisProviderAdapter] -- implemented by whoever composes the
 * adapter (future DI, out of this slice's scope). Never reads an environment variable or secret
 * store directly from the adapter itself. `null` means "credential unavailable right now" -- the
 * adapter maps that to `NotInvoked(PROVIDER_CREDENTIAL_UNAVAILABLE)` before building any request.
 * See receiptanalysis-slice-report.md 6.10.43.1/6.10.43.2.
 */
fun interface OpenAiCredentialProvider {
    fun credential(): OpenAiCredential?
}
