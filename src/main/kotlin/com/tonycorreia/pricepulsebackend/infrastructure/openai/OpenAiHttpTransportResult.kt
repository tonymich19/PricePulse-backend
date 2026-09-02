package com.tonycorreia.pricepulsebackend.infrastructure.openai

/**
 * Closed distinction between no definitive HTTP response and a received one -- required by
 * receiptanalysis-slice-report.md 6.10.43.2. [NoResponse] means any transport failure before a
 * definitive HTTP response arrived (timeout, connection lost/refused, DNS, or any other network
 * exception) -- [OpenAiHttpTransport.send] must normalize all of those to this case, never let an
 * exception escape (except [kotlinx.coroutines.CancellationException], which always propagates).
 * [Received] means a definitive HTTP status arrived, with a (possibly truncated, "bounded body
 * handling") body -- never [NoResponse] just because the body was cut short.
 */
sealed interface OpenAiHttpTransportResult {

    data object NoResponse : OpenAiHttpTransportResult

    /**
     * [bodyTruncated] is `true` when the transport stopped reading before the body finished
     * (bounded body handling, a defensive byte limit) -- required, no default, because a
     * truncated prefix can accidentally be syntactically valid JSON on its own (6.10.45/6.10.46):
     * only the transport that actually stopped reading knows this, never the adapter.
     */
    class Received private constructor(
        val statusCode: Int,
        private val bodyBytesCopy: ByteArray,
        val bodyTruncated: Boolean
    ) : OpenAiHttpTransportResult {

        /** Always a fresh copy -- mutating the returned array never affects this instance. */
        fun bodyBytes(): ByteArray = bodyBytesCopy.copyOf()

        override fun toString(): String = "Received(statusCode=$statusCode, bodyTruncated=$bodyTruncated, body=<redacted>)"

        companion object {
            /** Defensively copies [bodyBytes] on construction. Rejects a statusCode outside the valid HTTP range. */
            operator fun invoke(statusCode: Int, bodyBytes: ByteArray, bodyTruncated: Boolean): Received {
                require(statusCode in 100..599) { "statusCode must be a valid HTTP status (100-599), was: $statusCode" }
                return Received(statusCode, bodyBytes.copyOf(), bodyTruncated)
            }
        }
    }
}
