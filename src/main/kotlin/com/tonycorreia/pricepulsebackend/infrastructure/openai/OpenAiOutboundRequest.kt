package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference

/**
 * Opaque outbound HTTP request value -- built only by [OpenAiReceiptAnalysisProviderAdapter],
 * never by [OpenAiHttpTransport]. Carries the fixed `POST /v1/responses` target, the already
 * opaque [OpenAiResponsesRequestBody] (6.10.40), and an `Authorization` value derived from an
 * injected [OpenAiCredential] -- never read from environment by the transport. [toString] never
 * discloses the authorization header or the body. See receiptanalysis-slice-report.md 6.10.43.1.
 */
class OpenAiOutboundRequest private constructor(
    val method: String,
    val path: String,
    val contentType: String,
    private val authorizationHeaderValue: String,
    private val bodyBytesCopy: ByteArray
) {
    /** Always a fresh copy -- mutating the returned array never affects this instance. */
    fun bodyBytes(): ByteArray = bodyBytesCopy.copyOf()

    /** Only for the test seam to assert header presence/value without touching toString()/logs. */
    internal fun authorizationHeaderValue(): String = authorizationHeaderValue

    override fun toString(): String =
        "OpenAiOutboundRequest(method=$method, path=$path, contentType=$contentType, authorization=<redacted>, body=<redacted>)"

    companion object {
        /**
         * The only shape a correlation reference may take before it can become part of a URL
         * path. The value originates with the provider, not with this backend, so it is never
         * trusted unchecked: `../`, an absolute URL, a query string, a fragment, a percent-encoded
         * separator or whitespace would each redirect a request that carries the `Authorization`
         * header to a destination this backend never intended. 128 characters is a deliberate
         * ceiling, not an observed provider limit.
         */
        private val SAFE_REFERENCE = Regex("^[A-Za-z0-9_-]{1,128}$")

        operator fun invoke(body: OpenAiResponsesRequestBody, credential: OpenAiCredential): OpenAiOutboundRequest =
            OpenAiOutboundRequest("POST", "/v1/responses", "application/json", "Bearer " + credential.value(), body.bytes())

        /**
         * A read of one already-pending response. Returns null -- never a request -- when
         * [reference] is not a single safe path segment, so an unsafe value can never reach the
         * transport at all; the caller reports that as `REQUEST_CONSTRUCTION_FAILED`.
         *
         * Carries an empty body: a retrieval sends nothing, and the transport omits `Content-Type`
         * accordingly.
         */
        fun retrieval(reference: ProviderCorrelationReference, credential: OpenAiCredential): OpenAiOutboundRequest? {
            val value = reference.value()
            if (!SAFE_REFERENCE.matches(value)) return null
            return OpenAiOutboundRequest(
                "GET",
                "/v1/responses/$value",
                "application/json",
                "Bearer " + credential.value(),
                ByteArray(0)
            )
        }
    }
}
