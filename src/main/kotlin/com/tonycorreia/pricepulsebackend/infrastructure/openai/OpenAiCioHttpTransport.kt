package com.tonycorreia.pricepulsebackend.infrastructure.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import kotlinx.io.readByteArray

/**
 * The only production implementation of [OpenAiHttpTransport] -- Ktor Client, CIO engine (same
 * `ktorVersion` already used by the Ktor Server). Destination is the literal, non-configurable
 * OpenAI production endpoint -- never derived from any runtime-supplied base URL, closing the
 * credential-exfiltration risk a configurable destination would open (receiptanalysis-slice-report.md
 * 6.10.45/6.10.46). No model/prompt/credential-source/production timeout or body-limit value is
 * chosen here -- all four are required constructor parameters, validated positive, with no default.
 *
 * Owns one reusable [HttpClient] for its whole lifetime -- never one per [send] call. Callers must
 * [close] it exactly once when done (a single shared instance for the process, not per request).
 */
class OpenAiCioHttpTransport(
    engine: HttpClientEngine,
    requestTimeoutMs: Long,
    connectTimeoutMs: Long,
    socketTimeoutMs: Long,
    private val bodyLimitBytes: Long,
    /**
     * Called with the phase and the exception whenever [send] converts a failure into
     * [OpenAiHttpTransportResult.NoResponse]. Normalizing to NoResponse is still correct -- the
     * orchestration must only ever receive an outcome -- but discarding *why* left the backend
     * unable to tell a DNS failure from a timeout from a refused connection. Injectable only so a
     * test can assert it without capturing an appender; production uses the logging default.
     */
    private val onFailure: (String, Throwable) -> Unit = { phase, failure ->
        // TYPE ONLY -- never the message, never the exception object. This class is the one that
        // puts the credential into an Authorization header, and an HTTP client's exception message
        // routinely quotes the offending header or request material: Ktor's own
        // IllegalHeaderValueException embeds the full header value, which would print the API key
        // in plain text. The exception type plus the phase is what actually identifies the fault.
        LoggerFactory.getLogger(OpenAiCioHttpTransport::class.java).warn(
            "openai transport failed, reporting NoResponse phase={} failureType={}",
            phase,
            failure::class.qualifiedName ?: failure::class.java.name
        )
    }
) : OpenAiHttpTransport, AutoCloseable {

    init {
        require(requestTimeoutMs > 0) { "requestTimeoutMs must be positive" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(socketTimeoutMs > 0) { "socketTimeoutMs must be positive" }
        require(bodyLimitBytes > 0) { "bodyLimitBytes must be positive" }
        require(bodyLimitBytes <= MAX_BODY_LIMIT_BYTES) {
            "bodyLimitBytes must be at most $MAX_BODY_LIMIT_BYTES -- the proof-byte read adds 1 " +
                "and the truncated result is copied into an Int-sized ByteArray, so any larger " +
                "value cannot be represented safely"
        }
    }

    private val client = HttpClient(engine) {
        // Never let the client throw on a non-2xx status -- the adapter classifies by the real
        // statusCode (6.10.44), an exception here would hide it behind a generic client error.
        expectSuccess = false
        // The OpenAI endpoint never requires a redirect; following an arbitrary Location would
        // resend the Authorization header to an untrusted destination -- never acceptable.
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = connectTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
        // No retry plugin installed -- retry, if it ever exists, is the adapter/orchestration's
        // decision (6.10.36/6.10.41), never the transport's own.
    }

    override suspend fun send(request: OpenAiOutboundRequest): OpenAiHttpTransportResult {
        val response = try {
            client.request(PRODUCTION_ORIGIN + request.path) {
                method = HttpMethod.parse(request.method)
                header(HttpHeaders.Authorization, request.authorizationHeaderValue())
                // A retrieval carries no body; declaring a Content-Type for an empty request
                // would describe content that does not exist.
                val body = request.bodyBytes()
                if (body.isNotEmpty()) {
                    contentType(ContentType.parse(request.contentType))
                    setBody(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(PHASE_SEND, e)
            return OpenAiHttpTransportResult.NoResponse
        }

        return try {
            val (bytes, truncated) = readBoundedBody(response)
            OpenAiHttpTransportResult.Received(response.status.value, bytes, truncated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(PHASE_READ_BODY, e)
            OpenAiHttpTransportResult.NoResponse
        }
    }

    /**
     * Reads at most [bodyLimitBytes] into the result, plus one extra proof byte purely to detect
     * truncation -- `Content-Length` is never trusted as sufficient proof of completeness (a
     * dishonest or absent header would defeat that check). See 6.10.46.
     */
    private suspend fun readBoundedBody(response: HttpResponse): Pair<ByteArray, Boolean> {
        val channel = response.bodyAsChannel()
        val readWithProofByte = channel.readRemaining(bodyLimitBytes + 1).readByteArray()
        return if (readWithProofByte.size > bodyLimitBytes) {
            readWithProofByte.copyOf(bodyLimitBytes.toInt()) to true
        } else {
            readWithProofByte to false
        }
    }

    override fun close() {
        client.close()
    }

    companion object {
        /** The request never produced a definitive HTTP status. */
        internal const val PHASE_SEND = "send"

        /** A status arrived; reading the body failed. */
        internal const val PHASE_READ_BODY = "read-body"

        // Origin only, still a literal: the destination host can never come from configuration,
        // from a runtime value, or from anything the provider returned. Only the path varies, and
        // only after OpenAiOutboundRequest has validated it as a single safe segment -- the same
        // credential-exfiltration guard as before, now expressed one level down.
        private const val PRODUCTION_ORIGIN = "https://api.openai.com"

        // Largest value for which `bodyLimitBytes + 1` (the proof-byte read) and the later
        // `bodyLimitBytes.toInt()` (the truncated-array size) both stay exact -- never Int.MAX_VALUE
        // itself, to keep a 1-byte margin for the proof read.
        private val MAX_BODY_LIMIT_BYTES: Long = Int.MAX_VALUE.toLong() - 1
    }
}
