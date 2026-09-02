package com.tonycorreia.pricepulsebackend.infrastructure.openai

/**
 * Opaque, offline-built `POST /responses` request body -- only [OpenAiResponsesRequestFactory]
 * constructs one, from an already-serialized JSON tree. [toString] never discloses the embedded
 * base64 image or extraction instructions -- same discipline already required for
 * `ProviderCorrelationReference` and the provider credential.
 */
class OpenAiResponsesRequestBody private constructor(private val bytesCopy: ByteArray) {

    /** Always a fresh copy -- mutating the returned array never affects this instance. */
    fun bytes(): ByteArray = bytesCopy.copyOf()

    override fun toString(): String = "OpenAiResponsesRequestBody(<redacted>)"

    companion object {
        /** Defensively copies [bytes] on construction -- mutating the original afterwards has no effect. */
        internal operator fun invoke(bytes: ByteArray): OpenAiResponsesRequestBody =
            OpenAiResponsesRequestBody(bytes.copyOf())
    }
}
