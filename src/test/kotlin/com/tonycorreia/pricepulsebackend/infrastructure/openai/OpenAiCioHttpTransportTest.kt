package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttempt
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.mock.respondRedirect
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Entirely offline -- never a real network call. Uses Ktor's `MockEngine` (test-only dependency)
 * injected at the engine level -- the adapter-level classification of a truncated body (200/429)
 * is covered separately in `OpenAiReceiptAnalysisProviderAdapterTest`; this file only proves the
 * transport itself. See receiptanalysis-slice-report.md 6.10.45/6.10.46.
 */
class OpenAiCioHttpTransportTest {

    private val credential = OpenAiCredential("test-secret-value")
    private val requestFactory = OpenAiResponsesRequestFactory(
        OpenAiReceiptAnalysisRequestConfig(
            model = "gpt-5.6",
            extractionInstructions = "Extract merchant, total, purchasedAt and items.",
            schemaName = "receipt_analysis",
            imageDetail = "auto",
            maxOutputTokens = 4000,
            reasoningEffort = "low"
        )
    )
    private val attempt = ReceiptAnalysisAttempt(
        ReceiptAnalysisAttemptId("attempt-1"),
        PreparedReceiptImage(byteArrayOf(1, 2, 3), "image/png")
    )

    private fun outboundRequest() = OpenAiOutboundRequest(requestFactory(attempt), credential)

    private fun transport(
        engine: MockEngine,
        requestTimeoutMs: Long = 30_000,
        connectTimeoutMs: Long = 10_000,
        socketTimeoutMs: Long = 10_000,
        bodyLimitBytes: Long = 1_000_000
    ) = OpenAiCioHttpTransport(engine, requestTimeoutMs, connectTimeoutMs, socketTimeoutMs, bodyLimitBytes)

    // ---------------------------------------------------------------------------------------
    // Validação de configuração -- positivos exigidos
    // ---------------------------------------------------------------------------------------

    @Test
    fun `rejects a non-positive requestTimeoutMs, connectTimeoutMs, socketTimeoutMs, or bodyLimitBytes`() {
        val engine = MockEngine { respondOk() }
        assertFailsWith<IllegalArgumentException> { transport(engine, requestTimeoutMs = 0) }
        assertFailsWith<IllegalArgumentException> { transport(engine, connectTimeoutMs = -1) }
        assertFailsWith<IllegalArgumentException> { transport(engine, socketTimeoutMs = 0) }
        assertFailsWith<IllegalArgumentException> { transport(engine, bodyLimitBytes = 0) }
    }

    @Test
    fun `rejects a bodyLimitBytes above the safe representable range, never silently truncating or overflowing`() {
        val engine = MockEngine { respondOk() }
        assertFailsWith<IllegalArgumentException> {
            transport(engine, bodyLimitBytes = Int.MAX_VALUE.toLong())
        }
        assertFailsWith<IllegalArgumentException> {
            transport(engine, bodyLimitBytes = Long.MAX_VALUE)
        }
    }

    @Test
    fun `accepts the largest safe bodyLimitBytes, exactly Int MAX_VALUE minus 1`() {
        val engine = MockEngine { respondOk() }
        transport(engine, bodyLimitBytes = Int.MAX_VALUE.toLong() - 1).close()
    }

    // ---------------------------------------------------------------------------------------
    // URL, método, headers, corpo repassados sem alteração
    // ---------------------------------------------------------------------------------------

    @Test
    fun `sends the exact URL, method, Authorization, Content-Type, and body from OpenAiOutboundRequest`() = runBlocking {
        val outbound = outboundRequest()
        val engine = MockEngine { request ->
            respond("{}".toByteArray(), HttpStatusCode.OK)
        }
        transport(engine).use {
            it.send(outbound)
        }

        val captured = engine.requestHistory.single()
        assertEquals("https://api.openai.com/v1/responses", captured.url.toString())
        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("Bearer test-secret-value", captured.headers[HttpHeaders.Authorization])
        assertEquals(ContentType.Application.Json, captured.body.contentType)
        val sentBytes = (captured.body as OutgoingContent.ByteArrayContent).bytes()
        assertEquals(outbound.bodyBytes().toList(), sentBytes.toList())
    }

    // ---------------------------------------------------------------------------------------
    // 4xx/5xx entregues como Received, nunca como exceção (expectSuccess = false)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a 429 response is delivered as Received, never thrown, expectSuccess is false`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests, "rate limited") }

        val result = transport(engine).use { it.send(outboundRequest()) }

        val received = assertIs<OpenAiHttpTransportResult.Received>(result)
        assertEquals(429, received.statusCode)
    }

    @Test
    fun `a 500 response is delivered as Received, never thrown`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "server error") }

        val result = transport(engine).use { it.send(outboundRequest()) }

        val received = assertIs<OpenAiHttpTransportResult.Received>(result)
        assertEquals(500, received.statusCode)
    }

    // ---------------------------------------------------------------------------------------
    // Redirect nunca seguido
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a redirect response is delivered as Received with the redirect statusCode, never followed automatically`() = runBlocking {
        val engine = MockEngine { respondRedirect("https://attacker.example/steal") }

        val result = transport(engine).use { it.send(outboundRequest()) }

        val received = assertIs<OpenAiHttpTransportResult.Received>(result)
        assertEquals(307, received.statusCode) // MockEngine's respondRedirect uses 307 Temporary Redirect
        // followRedirects = false: only the original request was ever made -- never a 2nd call
        // to the redirect target, which would have leaked Authorization to an untrusted host.
        assertEquals(1, engine.requestHistory.size)
    }

    // ---------------------------------------------------------------------------------------
    // Exatamente uma tentativa
    // ---------------------------------------------------------------------------------------

    @Test
    fun `exactly one attempt is made per send, even across repeated calls with the same transport`() = runBlocking {
        val engine = MockEngine { respond("{}".toByteArray(), HttpStatusCode.OK) }
        val realTransport = transport(engine)

        realTransport.use {
            it.send(outboundRequest())
            it.send(outboundRequest())
        }

        assertEquals(2, engine.requestHistory.size)
    }

    // ---------------------------------------------------------------------------------------
    // NoResponse e cancelamento
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a non-cancellable exception from the engine is normalized to NoResponse, never thrown`() = runBlocking {
        val engine = MockEngine { throw java.io.IOException("connection reset") }

        val result = transport(engine).use { it.send(outboundRequest()) }

        assertEquals(OpenAiHttpTransportResult.NoResponse, result)
    }

    @Test
    fun `a CancellationException from the engine always propagates, never normalized to NoResponse`() {
        val engine = MockEngine { throw CancellationException("cancelled") }
        val realTransport = transport(engine)

        assertFailsWith<CancellationException> {
            runBlocking { realTransport.send(outboundRequest()) }
        }
        realTransport.close()
    }

    /**
     * The *second* failure boundary of [OpenAiCioHttpTransport.send]: the response itself is
     * created successfully -- MockEngine's `respond(ByteReadChannel, ...)` overload hands back a
     * real `200 OK` whose body has already started -- and the exception only surfaces later, inside
     * `readBoundedBody`. Throwing from the engine handler would exercise the first boundary
     * instead, which the two tests above already cover.
     */
    @Test
    fun `a body that fails mid-read is normalized to NoResponse, never thrown`() = runBlocking {
        val engine = MockEngine {
            val channel = ByteChannel(autoFlush = true)
            channel.writeByteArray(byteArrayOf('{'.code.toByte()))
            channel.flush()
            channel.cancel(java.io.IOException("body stream broke"))
            respond(channel, HttpStatusCode.OK)
        }

        val result = transport(engine).use { it.send(outboundRequest()) }

        assertEquals(OpenAiHttpTransportResult.NoResponse, result)
    }

    @Test
    fun `a CancellationException while reading the body always propagates, never normalized to NoResponse`() {
        val engine = MockEngine {
            val channel = ByteChannel(autoFlush = true)
            channel.writeByteArray(byteArrayOf('{'.code.toByte()))
            channel.flush()
            channel.cancel(CancellationException("cancelled mid-body"))
            respond(channel, HttpStatusCode.OK)
        }
        val realTransport = transport(engine)

        assertFailsWith<CancellationException> {
            runBlocking { realTransport.send(outboundRequest()) }
        }
        realTransport.close()
    }

    // ---------------------------------------------------------------------------------------
    // Corpo exatamente no limite vs. truncado
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a body exactly at the configured limit is not truncated`() = runBlocking {
        val exactBody = ByteArray(5) { 'a'.code.toByte() }
        val engine = MockEngine { respond(exactBody, HttpStatusCode.OK) }

        val result = transport(engine, bodyLimitBytes = 5).use { it.send(outboundRequest()) }

        val received = assertIs<OpenAiHttpTransportResult.Received>(result)
        assertFalse(received.bodyTruncated)
        assertEquals(exactBody.toList(), received.bodyBytes().toList())
    }

    @Test
    fun `a body over the configured limit is truncated to exactly the limit, never using Content-Length as proof`() = runBlocking {
        val overBody = ByteArray(8) { 'a'.code.toByte() }
        val engine = MockEngine {
            // Content-Length here deliberately LIES, claiming only 5 bytes when 8 are actually
            // sent -- an implementation that trusted this header would stop at 5 and (wrongly)
            // report bodyTruncated == false. The transport must detect truncation from the real
            // proof-byte read alone, never from this header.
            respond(overBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5"))
        }

        val result = transport(engine, bodyLimitBytes = 5).use { it.send(outboundRequest()) }

        val received = assertIs<OpenAiHttpTransportResult.Received>(result)
        assertTrue(received.bodyTruncated)
        assertEquals(5, received.bodyBytes().size)
        assertEquals(overBody.copyOf(5).toList(), received.bodyBytes().toList())
    }

    // ---------------------------------------------------------------------------------------
    // Ciclo de vida do HttpClient
    // ---------------------------------------------------------------------------------------

    @Test
    fun `close never throws, and can be called after use`() = runBlocking {
        val engine = MockEngine { respond("{}".toByteArray(), HttpStatusCode.OK) }
        val realTransport = transport(engine)

        realTransport.send(outboundRequest())
        realTransport.close()
    }
    // -----------------------------------------------------------------------------------------
    // Failure observation -- NoResponse stays the outcome, but never without saying why.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a send failure is reported with the send phase before NoResponse is returned`() = runBlocking {
        val observed = mutableListOf<Pair<String, Throwable>>()
        val boom = IllegalStateException("connection refused")
        val transport = OpenAiCioHttpTransport(
            engine = MockEngine { throw boom },
            requestTimeoutMs = 5_000L,
            connectTimeoutMs = 5_000L,
            socketTimeoutMs = 5_000L,
            bodyLimitBytes = 1_048_576L,
            onFailure = { phase, failure -> observed += phase to failure }
        )

        val result = transport.send(outboundRequest())
        transport.close()

        assertIs<OpenAiHttpTransportResult.NoResponse>(result)
        assertEquals(1, observed.size, "a swallowed transport failure must not vanish")
        assertEquals(OpenAiCioHttpTransport.PHASE_SEND, observed.single().first)
        // Type and message, not instance identity: the client may rethrow a wrapper, and the log
        // line is built from exactly these two, never from the object.
        assertIs<IllegalStateException>(observed.single().second)
        assertEquals(boom.message, observed.single().second.message)
    }

    @Test
    fun `a successful exchange reports no failure at all`() = runBlocking {
        val observed = mutableListOf<Pair<String, Throwable>>()
        val transport = OpenAiCioHttpTransport(
            engine = MockEngine { respond("{}", HttpStatusCode.OK) },
            requestTimeoutMs = 5_000L,
            connectTimeoutMs = 5_000L,
            socketTimeoutMs = 5_000L,
            bodyLimitBytes = 1_048_576L,
            onFailure = { phase, failure -> observed += phase to failure }
        )

        val result = transport.send(outboundRequest())
        transport.close()

        assertIs<OpenAiHttpTransportResult.Received>(result)
        assertEquals(emptyList(), observed, "a healthy call never logs a failure")
    }

}
