package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderRejectionReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttempt
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Entirely offline -- no network, no OpenAI call. Implements the literal 21-row classification
 * table + credential-absent case of receiptanalysis-slice-report.md 6.10.43.3.
 */
class OpenAiReceiptAnalysisProviderAdapterTest {

    private val mapper = ObjectMapper()

    private val testCredential = OpenAiCredential("test-secret-value")
    private val presentCredentialProvider = OpenAiCredentialProvider { testCredential }
    private val absentCredentialProvider = OpenAiCredentialProvider { null }

    private val config = OpenAiReceiptAnalysisRequestConfig(
        model = "gpt-5.6",
        extractionInstructions = "Extract merchant, total, purchasedAt and items.",
        schemaName = "receipt_analysis",
        imageDetail = "auto",
        maxOutputTokens = 4000,
        reasoningEffort = "low"
    )
    private val requestFactory = OpenAiResponsesRequestFactory(config)

    private val attemptId = ReceiptAnalysisAttemptId("attempt-1")
    private val attempt = ReceiptAnalysisAttempt(attemptId, PreparedReceiptImage(byteArrayOf(1, 2, 3), "image/png"))

    private val validV1Json =
        """{"schemaVersion":"v1","documentStatus":"UNREADABLE","merchantName":{"status":"missing"},"purchasedAt":{"status":"missing"},"total":{"status":"missing"},"items":[]}"""

    // ---------------------------------------------------------------------------------------
    // Fake transport
    // ---------------------------------------------------------------------------------------

    private class RecordingTransport(
        private val result: OpenAiHttpTransportResult? = null,
        private val toThrow: Throwable? = null
    ) : OpenAiHttpTransport {
        var invocationCount = 0
            private set
        var lastRequest: OpenAiOutboundRequest? = null
            private set

        override suspend fun send(request: OpenAiOutboundRequest): OpenAiHttpTransportResult {
            invocationCount++
            lastRequest = request
            toThrow?.let { throw it }
            return result!!
        }
    }

    private class RecordingTelemetrySink(private val toThrow: Throwable? = null) : OpenAiInvocationTelemetrySink {
        val recorded = mutableListOf<OpenAiInvocationTelemetry>()

        override fun record(telemetry: OpenAiInvocationTelemetry) {
            toThrow?.let { throw it }
            recorded.add(telemetry)
        }
    }

    private fun adapterWith(
        transport: OpenAiHttpTransport,
        credentialProvider: OpenAiCredentialProvider = presentCredentialProvider,
        telemetrySink: OpenAiInvocationTelemetrySink = OpenAiInvocationTelemetrySink { }
    ) = OpenAiReceiptAnalysisProviderAdapter(requestFactory, credentialProvider, transport, telemetrySink)

    // ---------------------------------------------------------------------------------------
    // Fixture builders
    // ---------------------------------------------------------------------------------------

    private fun jsonBytes(build: ObjectNode.() -> Unit): ByteArray {
        val node = mapper.createObjectNode()
        node.build()
        return mapper.writeValueAsBytes(node)
    }

    private fun errorFixture(code: String?): ByteArray = jsonBytes {
        putObject("error").apply {
            if (code != null) put("code", code) else putNull("code")
            put("message", "sample error")
        }
    }

    private fun statusFixture(status: String, id: String? = "resp_1"): ByteArray = jsonBytes {
        if (id != null) put("id", id)
        put("status", status)
    }

    private fun statusFixtureNumericId(status: String, id: Int): ByteArray = jsonBytes {
        put("id", id)
        put("status", status)
    }

    private fun completedFixture(outputBuilder: ArrayNode.() -> Unit): ByteArray = jsonBytes {
        put("id", "resp_1")
        put("status", "completed")
        putArray("output").apply(outputBuilder)
    }

    private fun ArrayNode.assistantMessage(contentBuilder: ArrayNode.() -> Unit) {
        addObject().apply {
            put("type", "message")
            put("role", "assistant")
            putArray("content").apply(contentBuilder)
        }
    }

    private fun ArrayNode.outputTextItem(text: String) {
        addObject().apply {
            put("type", "output_text")
            put("text", text)
        }
    }

    private fun ArrayNode.refusalItem() {
        addObject().apply {
            put("type", "refusal")
            put("refusal", "cannot assist with that request")
        }
    }

    private fun received(statusCode: Int, body: ByteArray, bodyTruncated: Boolean = false) =
        OpenAiHttpTransportResult.Received(statusCode, body, bodyTruncated)

    // ---------------------------------------------------------------------------------------
    // Credencial ausente
    // ---------------------------------------------------------------------------------------

    @Test
    fun `credential absent resolves to NotInvoked PROVIDER_CREDENTIAL_UNAVAILABLE, transport never called`() = runBlocking {
        val transport = RecordingTransport(result = OpenAiHttpTransportResult.NoResponse)
        val adapter = adapterWith(transport, absentCredentialProvider)

        val outcome = adapter(attempt)

        val notInvoked = assertIs<ReceiptAnalysisProviderOutcome.NotInvoked>(outcome)
        assertEquals(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE, notInvoked.reason)
        assertEquals(0, transport.invocationCount)
    }

    // ---------------------------------------------------------------------------------------
    // Linha 1 -- NoResponse
    // ---------------------------------------------------------------------------------------

    @Test
    fun `row 1 -- NoResponse becomes InvocationUncertain, exactly one transport call`() = runBlocking {
        val transport = RecordingTransport(result = OpenAiHttpTransportResult.NoResponse)
        val adapter = adapterWith(transport)

        val outcome = adapter(attempt)

        val uncertain = assertIs<ReceiptAnalysisProviderOutcome.InvocationUncertain>(outcome)
        assertEquals(attemptId, uncertain.attemptId)
        assertEquals(1, transport.invocationCount)
    }

    // ---------------------------------------------------------------------------------------
    // Linhas 2-4 -- rejeições síncronas
    // ---------------------------------------------------------------------------------------

    @Test
    fun `row 2 -- HTTP 400 becomes InvocationRejected MALFORMED_REQUEST`() = runBlocking {
        val transport = RecordingTransport(result = received(400, errorFixture(null)))
        val outcome = adapterWith(transport)(attempt)

        val rejected = assertIs<ReceiptAnalysisProviderOutcome.InvocationRejected>(outcome)
        assertEquals(attemptId, rejected.attemptId)
        assertEquals(ProviderRejectionReason.MALFORMED_REQUEST, rejected.reason)
        assertEquals(1, transport.invocationCount)
    }

    @Test
    fun `row 3 -- HTTP 401 becomes InvocationRejected AUTHENTICATION_REJECTED`() = runBlocking {
        val transport = RecordingTransport(result = received(401, errorFixture(null)))
        val outcome = adapterWith(transport)(attempt)

        val rejected = assertIs<ReceiptAnalysisProviderOutcome.InvocationRejected>(outcome)
        assertEquals(ProviderRejectionReason.AUTHENTICATION_REJECTED, rejected.reason)
    }

    @Test
    fun `row 4 -- HTTP 403 becomes InvocationRejected ACCESS_FORBIDDEN`() = runBlocking {
        val transport = RecordingTransport(result = received(403, errorFixture(null)))
        val outcome = adapterWith(transport)(attempt)

        val rejected = assertIs<ReceiptAnalysisProviderOutcome.InvocationRejected>(outcome)
        assertEquals(ProviderRejectionReason.ACCESS_FORBIDDEN, rejected.reason)
    }

    // ---------------------------------------------------------------------------------------
    // Linhas 5-7 -- 429/500/503
    // ---------------------------------------------------------------------------------------

    @Test
    fun `row 5 -- HTTP 429 with a billing-or-quota error code becomes BILLING_OR_QUOTA_EXHAUSTED`() = runBlocking {
        val transport = RecordingTransport(result = received(429, errorFixture("credit_balance_exhausted")))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(attemptId, terminal.attemptId)
        assertEquals(ProviderTerminalFailureReason.BILLING_OR_QUOTA_EXHAUSTED, terminal.reason)
    }

    @Test
    fun `row 6 -- HTTP 429 without a billing-or-quota error code becomes RATE_LIMITED`() = runBlocking {
        val transport = RecordingTransport(result = received(429, errorFixture(null)))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.RATE_LIMITED, terminal.reason)
    }

    @Test
    fun `row 7 -- HTTP 500 becomes TRANSIENT_PROVIDER_FAILURE`() = runBlocking {
        val transport = RecordingTransport(result = received(500, errorFixture(null)))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE, terminal.reason)
    }

    @Test
    fun `row 7 -- HTTP 503 becomes TRANSIENT_PROVIDER_FAILURE`() = runBlocking {
        val transport = RecordingTransport(result = received(503, errorFixture(null)))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE, terminal.reason)
    }

    // ---------------------------------------------------------------------------------------
    // Linha 8 -- status HTTP não reconhecido
    // ---------------------------------------------------------------------------------------

    @Test
    fun `row 8 -- an unrecognized HTTP status becomes UNRECOGNIZED_RESPONSE`() = runBlocking {
        val transport = RecordingTransport(result = received(418, errorFixture(null)))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE, terminal.reason)
    }

    // ---------------------------------------------------------------------------------------
    // Linha 9 -- corpo não parseia como JSON
    // ---------------------------------------------------------------------------------------

    @Test
    fun `row 9 -- HTTP 200 with a body that is not valid JSON becomes InvocationConfirmedInvalidResponse`() = runBlocking {
        val transport = RecordingTransport(result = received(200, "not json at all".toByteArray()))
        val outcome = adapterWith(transport)(attempt)

        val invalid = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
        assertEquals(attemptId, invalid.attemptId)
    }

    // ---------------------------------------------------------------------------------------
    // Corpo truncado -- classificação do adapter (6.10.45/6.10.46, exigido pelo parecer 187)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `HTTP 200 with a truncated body becomes InvocationConfirmedInvalidResponse, never parsed, even if the prefix looks like a valid V1 document`(): Unit = runBlocking {
        val transport = RecordingTransport(
            result = received(200, completedFixture { assistantMessage { outputTextItem(validV1Json) } }, bodyTruncated = true)
        )
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    @Test
    fun `HTTP 429 with a truncated body always becomes RATE_LIMITED, never inspecting error code`() = runBlocking {
        val transport = RecordingTransport(
            result = received(429, errorFixture("credit_balance_exhausted"), bodyTruncated = true)
        )
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.RATE_LIMITED, terminal.reason)
    }

    // ---------------------------------------------------------------------------------------
    // Linha 10 -- status ausente/não reconhecido
    // ---------------------------------------------------------------------------------------

    @Test
    fun `row 10 -- HTTP 200 with a missing status field becomes UNRECOGNIZED_RESPONSE`() = runBlocking {
        val transport = RecordingTransport(result = received(200, jsonBytes { put("id", "resp_1") }))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE, terminal.reason)
    }

    @Test
    fun `row 10 -- HTTP 200 with an unrecognized status value becomes UNRECOGNIZED_RESPONSE`() = runBlocking {
        val transport = RecordingTransport(result = received(200, statusFixture("unknown_future_value")))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE, terminal.reason)
    }

    // ---------------------------------------------------------------------------------------
    // Linhas 11-13 -- failed/cancelled/incomplete
    // ---------------------------------------------------------------------------------------

    @Test
    fun `row 11 -- status failed becomes RESPONSE_FAILED`() = runBlocking {
        val transport = RecordingTransport(result = received(200, statusFixture("failed")))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.RESPONSE_FAILED, terminal.reason)
    }

    @Test
    fun `row 12 -- status cancelled becomes RESPONSE_CANCELLED`() = runBlocking {
        val transport = RecordingTransport(result = received(200, statusFixture("cancelled")))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.RESPONSE_CANCELLED, terminal.reason)
    }

    @Test
    fun `row 13 -- status incomplete becomes InvocationConfirmedInvalidResponse`(): Unit = runBlocking {
        val transport = RecordingTransport(
            result = received(200, jsonBytes {
                put("id", "resp_1")
                put("status", "incomplete")
                putObject("incomplete_details").put("reason", "max_output_tokens")
            })
        )
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    // ---------------------------------------------------------------------------------------
    // Linhas 14/14b/14c/14d -- queued/in_progress
    // ---------------------------------------------------------------------------------------

    @Test
    fun `row 14 -- status queued with a non-blank id becomes InvocationConfirmedPendingResponse with the correlation reference`() = runBlocking {
        val transport = RecordingTransport(result = received(200, statusFixture("queued", id = "resp_1")))
        val outcome = adapterWith(transport)(attempt)

        val pending = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse>(outcome)
        assertEquals(attemptId, pending.attemptId)
        assertEquals(ProviderCorrelationReference("resp_1"), pending.correlationReference)
    }

    @Test
    fun `row 14 -- status in_progress with a non-blank id becomes InvocationConfirmedPendingResponse with the correlation reference`() = runBlocking {
        val transport = RecordingTransport(result = received(200, statusFixture("in_progress", id = "resp_2")))
        val outcome = adapterWith(transport)(attempt)

        val pending = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse>(outcome)
        assertEquals(ProviderCorrelationReference("resp_2"), pending.correlationReference)
    }

    @Test
    fun `row 14b -- status queued with no id becomes UNRECOGNIZED_RESPONSE, never pending, never uncertain`() = runBlocking {
        val transport = RecordingTransport(result = received(200, statusFixture("queued", id = null)))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE, terminal.reason)
    }

    @Test
    fun `row 14c -- status in_progress with a blank id becomes UNRECOGNIZED_RESPONSE`() = runBlocking {
        val transport = RecordingTransport(result = received(200, statusFixture("in_progress", id = "")))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE, terminal.reason)
    }

    @Test
    fun `row 14d -- status queued with a non-string id becomes UNRECOGNIZED_RESPONSE`() = runBlocking {
        val transport = RecordingTransport(result = received(200, statusFixtureNumericId("queued", id = 123)))
        val outcome = adapterWith(transport)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE, terminal.reason)
    }

    // ---------------------------------------------------------------------------------------
    // Linhas 15-21 -- completed, regra determinística de extração de output_text
    // ---------------------------------------------------------------------------------------

    @Test
    fun `row 15 -- a refusal takes precedence over a valid output_text present in the same message`(): Unit = runBlocking {
        val transport = RecordingTransport(
            result = received(200, completedFixture {
                assistantMessage {
                    refusalItem()
                    outputTextItem(validV1Json)
                }
            })
        )
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    @Test
    fun `row 16 -- exactly one output_text that does not validate against the canonical V1 schema becomes InvocationConfirmedInvalidResponse`(): Unit = runBlocking {
        val transport = RecordingTransport(
            result = received(200, completedFixture {
                assistantMessage { outputTextItem("""{"not":"a valid v1 document"}""") }
            })
        )
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    @Test
    fun `row 17 -- exactly one output_text that validates against the canonical V1 schema becomes Completed`() = runBlocking {
        val transport = RecordingTransport(
            result = received(200, completedFixture {
                assistantMessage { outputTextItem(validV1Json) }
            })
        )
        val outcome = adapterWith(transport)(attempt)

        val completed = assertIs<ReceiptAnalysisProviderOutcome.Completed>(outcome)
        assertEquals(mapper.readTree(validV1Json), mapper.readTree(completed.document.serialize()))
    }

    @Test
    fun `row 18 -- an empty output array becomes InvocationConfirmedInvalidResponse`(): Unit = runBlocking {
        val transport = RecordingTransport(result = received(200, completedFixture { }))
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    @Test
    fun `row 19 -- two distinct output_text items never silently pick one, becomes InvocationConfirmedInvalidResponse`(): Unit = runBlocking {
        val transport = RecordingTransport(
            result = received(200, completedFixture {
                assistantMessage {
                    outputTextItem(validV1Json)
                    outputTextItem("""{"schemaVersion":"v1","documentStatus":"NOT_A_RECEIPT","merchantName":{"status":"missing"},"purchasedAt":{"status":"missing"},"total":{"status":"missing"},"items":[]}""")
                }
            })
        )
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    @Test
    fun `row 20 -- output_text present only outside a message-assistant element is ignored, becomes InvocationConfirmedInvalidResponse`(): Unit = runBlocking {
        val transport = RecordingTransport(
            result = received(200, completedFixture {
                addObject().apply {
                    put("type", "function_call")
                    putArray("content").apply { outputTextItem(validV1Json) }
                }
            })
        )
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    @Test
    fun `row 21 -- a message-assistant element with content missing becomes InvocationConfirmedInvalidResponse`(): Unit = runBlocking {
        val transport = RecordingTransport(
            result = received(200, completedFixture {
                addObject().apply {
                    put("type", "message")
                    put("role", "assistant")
                }
            })
        )
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    @Test
    fun `row 21 -- a message-assistant element with a non-array content becomes InvocationConfirmedInvalidResponse`(): Unit = runBlocking {
        val transport = RecordingTransport(
            result = received(200, completedFixture {
                addObject().apply {
                    put("type", "message")
                    put("role", "assistant")
                    put("content", "not an array")
                }
            })
        )
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    // ---------------------------------------------------------------------------------------
    // Sem output array utilizável -- nunca lança
    // ---------------------------------------------------------------------------------------

    @Test
    fun `completed status with no output field at all becomes InvocationConfirmedInvalidResponse, never throws`(): Unit = runBlocking {
        val transport = RecordingTransport(result = received(200, statusFixture("completed")))
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    @Test
    fun `completed status with a non-array output field becomes InvocationConfirmedInvalidResponse, never throws`(): Unit = runBlocking {
        val transport = RecordingTransport(
            result = received(200, jsonBytes {
                put("id", "resp_1")
                put("status", "completed")
                put("output", "not an array")
            })
        )
        val outcome = adapterWith(transport)(attempt)

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    // ---------------------------------------------------------------------------------------
    // `Received` -- validação de status e redação
    // ---------------------------------------------------------------------------------------

    @Test
    fun `Received rejects a statusCode outside the valid HTTP range`() {
        assertFailsWith<IllegalArgumentException> { OpenAiHttpTransportResult.Received(99, ByteArray(0), false) }
        assertFailsWith<IllegalArgumentException> { OpenAiHttpTransportResult.Received(600, ByteArray(0), false) }
    }

    @Test
    fun `Received toString never reveals the body`() {
        val result = OpenAiHttpTransportResult.Received(500, "sensitive body content".toByteArray(), false)
        assertEquals("Received(statusCode=500, bodyTruncated=false, body=<redacted>)", result.toString())
    }

    @Test
    fun `Received exposes bodyTruncated exactly as constructed`() {
        val truncated = OpenAiHttpTransportResult.Received(200, ByteArray(1), true)
        val notTruncated = OpenAiHttpTransportResult.Received(200, ByteArray(1), false)

        assertTrue(truncated.bodyTruncated)
        assertFalse(notTruncated.bodyTruncated)
    }

    @Test
    fun `OpenAiOutboundRequest toString never reveals the authorization header or the body`() {
        val body = requestFactory(attempt)
        val request = OpenAiOutboundRequest(body, testCredential)

        val text = request.toString()

        assertFalse(text.contains(testCredential.value()))
        assertFalse(text.contains("Bearer"))
        assertEquals(
            "OpenAiOutboundRequest(method=POST, path=/v1/responses, contentType=application/json, authorization=<redacted>, body=<redacted>)",
            text
        )
    }

    @Test
    fun `OpenAiCredential toString never reveals the value`() {
        assertEquals("OpenAiCredential(<redacted>)", testCredential.toString())
        assertFalse(testCredential.toString().contains("test-secret-value"))
    }

    // ---------------------------------------------------------------------------------------
    // Requisição montada, cancelamento, e invariantes gerais
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the adapter builds the outbound request with the exact method, path, content type, and authorization`() = runBlocking {
        val transport = RecordingTransport(result = OpenAiHttpTransportResult.NoResponse)
        adapterWith(transport)(attempt)

        val request = transport.lastRequest!!
        assertEquals("POST", request.method)
        assertEquals("/v1/responses", request.path)
        assertEquals("application/json", request.contentType)
        assertEquals("Bearer " + testCredential.value(), request.authorizationHeaderValue())
    }

    @Test
    fun `a CancellationException thrown by the transport propagates out of the adapter, never converted to an outcome`() {
        val transport = RecordingTransport(toThrow = CancellationException("cancelled"))
        val adapter = adapterWith(transport)

        assertFailsWith<CancellationException> {
            runBlocking { adapter(attempt) }
        }
        assertEquals(1, transport.invocationCount)
    }

    @Test
    fun `every confirmed outcome preserves the exact attemptId from the attempt, never invents one`() = runBlocking {
        val differentAttempt = ReceiptAnalysisAttempt(
            ReceiptAnalysisAttemptId("attempt-differs"),
            PreparedReceiptImage(byteArrayOf(9), "image/jpeg")
        )
        val transport = RecordingTransport(result = received(500, errorFixture(null)))

        val outcome = adapterWith(transport)(differentAttempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ReceiptAnalysisAttemptId("attempt-differs"), terminal.attemptId)
    }

    @Test
    fun `exactly one transport call per attempt, even on error and timeout scenarios`() = runBlocking {
        val errorTransport = RecordingTransport(result = received(500, errorFixture(null)))
        adapterWith(errorTransport)(attempt)
        assertEquals(1, errorTransport.invocationCount)

        val timeoutTransport = RecordingTransport(result = OpenAiHttpTransportResult.NoResponse)
        adapterWith(timeoutTransport)(attempt)
        assertEquals(1, timeoutTransport.invocationCount)

        val successTransport = RecordingTransport(
            result = received(200, completedFixture { assistantMessage { outputTextItem(validV1Json) } })
        )
        adapterWith(successTransport)(attempt)
        assertEquals(1, successTransport.invocationCount)
    }

    @Test
    fun `image bytes and extraction instructions never appear in any toString involved in building the request`() = runBlocking {
        val transport = RecordingTransport(result = OpenAiHttpTransportResult.NoResponse)
        adapterWith(transport)(attempt)

        val request = transport.lastRequest!!
        assertTrue(!request.toString().contains(config.extractionInstructions))
    }

    // ---------------------------------------------------------------------------------------
    // Telemetria de custo (6.10.55) -- quando registrar
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a Received response of any status generates exactly one telemetry event`() = runBlocking {
        listOf(
            received(200, completedFixture { assistantMessage { outputTextItem(validV1Json) } }),
            received(429, errorFixture(null)),
            received(500, errorFixture(null))
        ).forEach { result ->
            val sink = RecordingTelemetrySink()
            adapterWith(RecordingTransport(result = result), telemetrySink = sink)(attempt)
            assertEquals(1, sink.recorded.size)
        }
    }

    @Test
    fun `NoResponse generates zero telemetry events`() = runBlocking {
        val sink = RecordingTelemetrySink()
        adapterWith(RecordingTransport(result = OpenAiHttpTransportResult.NoResponse), telemetrySink = sink)(attempt)

        assertEquals(0, sink.recorded.size)
    }

    @Test
    fun `credential absent generates zero telemetry events, transport never called`() = runBlocking {
        val sink = RecordingTelemetrySink()
        val transport = RecordingTransport(result = OpenAiHttpTransportResult.NoResponse)

        adapterWith(transport, absentCredentialProvider, sink)(attempt)

        assertEquals(0, sink.recorded.size)
        assertEquals(0, transport.invocationCount)
    }

    // ---------------------------------------------------------------------------------------
    // Telemetria -- nunca expõe identificador remoto/imagem/instruções/credencial
    // ---------------------------------------------------------------------------------------

    @Test
    fun `telemetry for a pending response signals correlationPresent without ever carrying the raw remote id`() = runBlocking {
        val sink = RecordingTelemetrySink()
        val transport = RecordingTransport(result = received(200, statusFixture("queued", id = "resp_secret_id")))

        adapterWith(transport, telemetrySink = sink)(attempt)

        val telemetry = sink.recorded.single()
        assertEquals(attemptId, telemetry.attemptId)
        assertTrue(telemetry.correlationPresent)
        // OpenAiInvocationTelemetry has no field capable of carrying the raw id, the image bytes,
        // extraction instructions, or the credential -- structural proof by the type's own shape.
    }

    @Test
    fun `telemetry for a response without an id signals correlationPresent false`() = runBlocking {
        val sink = RecordingTelemetrySink()
        adapterWith(RecordingTransport(result = received(429, errorFixture(null))), telemetrySink = sink)(attempt)

        assertFalse(sink.recorded.single().correlationPresent)
    }

    // ---------------------------------------------------------------------------------------
    // Telemetria -- extração de usage por campo, nunca descarta o evento inteiro
    // ---------------------------------------------------------------------------------------

    @Test
    fun `telemetry usage is entirely null when the response body has no usage object`() = runBlocking {
        val sink = RecordingTelemetrySink()
        adapterWith(RecordingTransport(result = received(429, errorFixture(null))), telemetrySink = sink)(attempt)

        assertEquals(null, sink.recorded.single().usage)
    }

    @Test
    fun `telemetry usage extracts each field independently -- invalid or missing fields become null without discarding the event`() = runBlocking {
        val sink = RecordingTelemetrySink()
        val body = jsonBytes {
            put("id", "resp_1")
            put("status", "completed")
            put("model", "gpt-5.6-luna-2026-01-01")
            putArray("output")
            putObject("usage").apply {
                put("input_tokens", 120)
                put("output_tokens", -5) // negative -- becomes null, never a thrown exception
                // total_tokens absent -- becomes null
                putObject("input_tokens_details").apply {
                    put("cached_tokens", "not-a-number") // wrong type -- becomes null
                    put("cache_write_tokens", 10)
                }
                putObject("output_tokens_details").apply {
                    put("reasoning_tokens", 30)
                }
            }
        }

        adapterWith(RecordingTransport(result = received(200, body)), telemetrySink = sink)(attempt)

        val telemetry = sink.recorded.single()
        assertEquals("gpt-5.6-luna-2026-01-01", telemetry.effectiveModel)
        assertEquals("completed", telemetry.status)
        val usage = telemetry.usage!!
        assertEquals(120L, usage.inputTokens)
        assertEquals(null, usage.outputTokens)
        assertEquals(null, usage.totalTokens)
        assertEquals(null, usage.cachedTokens)
        assertEquals(10L, usage.cacheWriteTokens)
        assertEquals(30L, usage.reasoningTokens)
    }

    @Test
    fun `telemetry usage field with a JSON integer above Long MAX_VALUE becomes null, never a silently overflowed value`() = runBlocking {
        val sink = RecordingTelemetrySink()
        val aboveLongRange = java.math.BigInteger("18446744073709551616") // 2^64, outside Long's range
        val body = jsonBytes {
            put("id", "resp_1")
            put("status", "completed")
            putArray("output")
            putObject("usage").apply {
                put("input_tokens", 42) // stays valid alongside the malformed field
                put("output_tokens", aboveLongRange)
            }
        }

        adapterWith(RecordingTransport(result = received(200, body)), telemetrySink = sink)(attempt)

        val telemetry = sink.recorded.single()
        val usage = telemetry.usage!!
        assertEquals(42L, usage.inputTokens)
        assertEquals(null, usage.outputTokens)
    }

    // ---------------------------------------------------------------------------------------
    // Telemetria -- corpo truncado nunca interpretado, mesmo com prefixo aparentemente válido
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a truncated body generates only the minimal telemetry event, never interpreting the prefix even if it looks like valid usage`() = runBlocking {
        val sink = RecordingTelemetrySink()
        val looksValidBody = jsonBytes {
            put("id", "resp_1")
            put("status", "completed")
            put("model", "gpt-5.6-luna")
            putObject("usage").apply { put("input_tokens", 100) }
        }
        val transport = RecordingTransport(result = received(200, looksValidBody, bodyTruncated = true))

        adapterWith(transport, telemetrySink = sink)(attempt)

        val telemetry = sink.recorded.single()
        assertEquals(attemptId, telemetry.attemptId)
        assertFalse(telemetry.correlationPresent)
        assertEquals(null, telemetry.effectiveModel)
        assertEquals(null, telemetry.status)
        assertEquals(null, telemetry.usage)
    }

    // ---------------------------------------------------------------------------------------
    // Telemetria -- isolamento de falha: exceção comum absorvida, cancelamento propaga
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a non-cancellable exception from the telemetry sink is absorbed, never changes the outcome`() = runBlocking {
        val sink = RecordingTelemetrySink(toThrow = IllegalStateException("log backend down"))
        val transport = RecordingTransport(result = received(500, errorFixture(null)))

        val outcome = adapterWith(transport, telemetrySink = sink)(attempt)

        val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE, terminal.reason)
    }

    @Test
    fun `a CancellationException from the telemetry sink propagates out of the adapter`() {
        val sink = RecordingTelemetrySink(toThrow = CancellationException("cancelled"))
        val transport = RecordingTransport(result = received(500, errorFixture(null)))

        assertFailsWith<CancellationException> {
            runBlocking { adapterWith(transport, telemetrySink = sink)(attempt) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The same policy, exercised through the REAL production sink
    //
    // The two tests above use a fake sink. These use LoggingOpenAiInvocationTelemetrySink itself
    // with a throwing emitter, which is what proves the production sink adds no try/catch of its
    // own: were it to swallow the failure, the first test would still pass but the second would
    // fail, because cancellation would never reach the adapter.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an emitter failure inside the real logging sink is absorbed by the adapter, outcome unchanged`() =
        runBlocking {
            val sink = LoggingOpenAiInvocationTelemetrySink { error("log backend down") }
            val transport = RecordingTransport(result = received(500, errorFixture(null)))

            val outcome = adapterWith(transport, telemetrySink = sink)(attempt)

            val terminal = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
            assertEquals(ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE, terminal.reason)
        }

    @Test
    fun `a CancellationException from the real logging sink's emitter still propagates`() {
        val sink = LoggingOpenAiInvocationTelemetrySink { throw CancellationException("cancelled") }
        val transport = RecordingTransport(result = received(500, errorFixture(null)))

        assertFailsWith<CancellationException> {
            runBlocking { adapterWith(transport, telemetrySink = sink)(attempt) }
        }
    }
}
