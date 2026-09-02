package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Offline, secret-free tests for the retrieval adapter -- never a real network call, exactly like
 * the invocation adapter's own tests. No case here needs a valid receipt document: every
 * assertion uses a response status that carries none, so the schema fixture stays out of it.
 */
class OpenAiReceiptAnalysisRetrievalAdapterTest {

    private val attemptId = ReceiptAnalysisAttemptId("attempt-1")
    private val credential = OpenAiCredential("sk-test")

    private fun adapter(transport: OpenAiHttpTransport) =
        OpenAiReceiptAnalysisRetrievalAdapter(OpenAiCredentialProvider { credential }, transport)

    @Test
    fun `a retrieval is a GET on the response path, with no request body`(): Unit = runBlocking {
        var seen: OpenAiOutboundRequest? = null
        val body = """{"id":"resp_abc123","status":"failed"}""".toByteArray()

        val outcome = adapter(
            FakeTransport { request ->
                seen = request
                OpenAiHttpTransportResult.Received(200, body, false)
            }
        ).retrieve(attemptId, ProviderCorrelationReference("resp_abc123"))

        val request = requireNotNull(seen)
        assertEquals("GET", request.method)
        assertEquals("/v1/responses/resp_abc123", request.path)
        assertEquals(0, request.bodyBytes().size, "a retrieval never sends a body")
        val failure = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.RESPONSE_FAILED, failure.reason)
    }

    @Test
    fun `a still-queued retrieval stays pending and preserves the reference`(): Unit = runBlocking {
        val body = """{"id":"resp_abc123","status":"queued"}""".toByteArray()

        val outcome = adapter(FakeTransport { OpenAiHttpTransportResult.Received(200, body, false) })
            .retrieve(attemptId, ProviderCorrelationReference("resp_abc123"))

        val pending = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse>(outcome)
        assertEquals(ProviderCorrelationReference("resp_abc123"), pending.correlationReference)
        assertEquals(attemptId, pending.attemptId)
    }

    @Test
    fun `no response leaves the invocation uncertain`(): Unit = runBlocking {
        val outcome = adapter(FakeTransport { OpenAiHttpTransportResult.NoResponse })
            .retrieve(attemptId, ProviderCorrelationReference("resp_abc123"))

        assertIs<ReceiptAnalysisProviderOutcome.InvocationUncertain>(outcome)
    }

    @Test
    fun `an absent credential never reaches the transport`(): Unit = runBlocking {
        var called = false
        val adapter = OpenAiReceiptAnalysisRetrievalAdapter(
            OpenAiCredentialProvider { null },
            FakeTransport {
                called = true
                OpenAiHttpTransportResult.NoResponse
            }
        )

        val outcome = adapter.retrieve(attemptId, ProviderCorrelationReference("resp_abc123"))

        assertFalse(called)
        val notInvoked = assertIs<ReceiptAnalysisProviderOutcome.NotInvoked>(outcome)
        assertEquals(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE, notInvoked.reason)
    }

    @Test
    fun `a reference that is not a safe path segment never reaches the transport`(): Unit = runBlocking {
        val hostile = listOf(
            "../../secrets",
            "resp/../x",
            "resp abc",
            "https://evil.example/x",
            "resp?x=1",
            "resp#frag",
            "resp%2F..",
            "a".repeat(129)
        )

        for (raw in hostile) {
            var called = false
            val outcome = adapter(
                FakeTransport {
                    called = true
                    OpenAiHttpTransportResult.NoResponse
                }
            ).retrieve(attemptId, ProviderCorrelationReference(raw))

            assertFalse(called, "the transport must never be reached for reference: $raw")
            val notInvoked = assertIs<ReceiptAnalysisProviderOutcome.NotInvoked>(outcome)
            assertEquals(
                ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED,
                notInvoked.reason,
                "hostile reference: $raw"
            )
        }
    }

    @Test
    fun `a reference of exactly the accepted shape and maximum length is allowed through`(): Unit = runBlocking {
        val accepted = listOf("resp_abc123", "RESP-123_x", "a", "a".repeat(128))

        for (raw in accepted) {
            var seenPath: String? = null
            adapter(
                FakeTransport { request ->
                    seenPath = request.path
                    OpenAiHttpTransportResult.Received(200, """{"id":"x","status":"failed"}""".toByteArray(), false)
                }
            ).retrieve(attemptId, ProviderCorrelationReference(raw))

            assertEquals("/v1/responses/$raw", seenPath, "accepted reference: $raw")
        }
    }

    @Test
    fun `a 404 is an unrecognized response rather than a silent success`(): Unit = runBlocking {
        val outcome = adapter(FakeTransport { OpenAiHttpTransportResult.Received(404, "{}".toByteArray(), false) })
            .retrieve(attemptId, ProviderCorrelationReference("resp_abc123"))

        val failure = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE, failure.reason)
    }

    @Test
    fun `a truncated body is never parsed`(): Unit = runBlocking {
        val outcome = adapter(
            FakeTransport {
                OpenAiHttpTransportResult.Received(200, """{"id":"x","status":"failed"}""".toByteArray(), true)
            }
        ).retrieve(attemptId, ProviderCorrelationReference("resp_abc123"))

        assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
    }

    /**
     * Explicit named fake, matching `OpenAiReceiptAnalysisProviderAdapterTest.RecordingTransport`.
     * Never a SAM lambda: no test in this repository SAM-converts a `suspend fun interface`.
     */
    private class FakeTransport(
        private val respond: (OpenAiOutboundRequest) -> OpenAiHttpTransportResult
    ) : OpenAiHttpTransport {
        override suspend fun send(request: OpenAiOutboundRequest): OpenAiHttpTransportResult = respond(request)
    }
}
