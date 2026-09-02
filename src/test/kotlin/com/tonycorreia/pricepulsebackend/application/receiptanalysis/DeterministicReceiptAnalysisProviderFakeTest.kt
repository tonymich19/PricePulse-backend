package com.tonycorreia.pricepulsebackend.application.receiptanalysis

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DeterministicReceiptAnalysisProviderFakeTest {

    private val projectRoot = File(System.getProperty("user.dir"))

    private fun sampleAttempt(id: String) = ReceiptAnalysisAttempt(
        attemptId = ReceiptAnalysisAttemptId(id),
        image = PreparedReceiptImage(byteArrayOf(1, 2, 3), "image/jpeg")
    )

    @Test
    fun `completed scenario returns the document configured at construction, never network`(): Unit = runBlocking {
        val fixture = File(projectRoot, "contracts/fixtures/valid/complete-with-items.json")
        val document = ValidatedReceiptAnalysisResultV1.from(fixture.readBytes())
        assertNotNull(document)
        val fake = DeterministicReceiptAnalysisProviderFake.completed(document)

        val outcome = fake(sampleAttempt("attempt-completed"))

        val completed = assertIs<ReceiptAnalysisProviderOutcome.Completed>(outcome)
        assertEquals(document.serialize().toList(), completed.document.serialize().toList())
        assertEquals(1, fake.invocationCount)
    }

    @Test
    fun `notInvoked scenario never depends on the attempt content`(): Unit = runBlocking {
        val fake = DeterministicReceiptAnalysisProviderFake.notInvoked(ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED)

        val outcome = fake(sampleAttempt("attempt-not-invoked"))

        assertIs<ReceiptAnalysisProviderOutcome.NotInvoked>(outcome)
    }

    @Test
    fun `invocationUncertain echoes the attemptId it received, never inventing one`(): Unit = runBlocking {
        val fake = DeterministicReceiptAnalysisProviderFake.invocationUncertain()

        val outcome = fake(sampleAttempt("attempt-echo-uncertain"))

        val uncertain = assertIs<ReceiptAnalysisProviderOutcome.InvocationUncertain>(outcome)
        assertEquals(ReceiptAnalysisAttemptId("attempt-echo-uncertain"), uncertain.attemptId)
    }

    @Test
    fun `invocationConfirmedInvalidResponse echoes the attemptId it received`(): Unit = runBlocking {
        val fake = DeterministicReceiptAnalysisProviderFake.invocationConfirmedInvalidResponse()

        val outcome = fake(sampleAttempt("attempt-echo-confirmed"))

        val confirmed = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse>(outcome)
        assertEquals(ReceiptAnalysisAttemptId("attempt-echo-confirmed"), confirmed.attemptId)
    }

    @Test
    fun `invocationRejected echoes the attemptId it received and carries the configured reason`(): Unit = runBlocking {
        val fake = DeterministicReceiptAnalysisProviderFake.invocationRejected(ProviderRejectionReason.AUTHENTICATION_REJECTED)

        val outcome = fake(sampleAttempt("attempt-echo-rejected"))

        val rejected = assertIs<ReceiptAnalysisProviderOutcome.InvocationRejected>(outcome)
        assertEquals(ReceiptAnalysisAttemptId("attempt-echo-rejected"), rejected.attemptId)
        assertEquals(ProviderRejectionReason.AUTHENTICATION_REJECTED, rejected.reason)
    }

    @Test
    fun `invocationConfirmedTerminalFailure echoes the attemptId it received and carries the configured reason`(): Unit = runBlocking {
        val fake = DeterministicReceiptAnalysisProviderFake.invocationConfirmedTerminalFailure(
            ProviderTerminalFailureReason.RESPONSE_FAILED
        )

        val outcome = fake(sampleAttempt("attempt-echo-terminal-failure"))

        val terminalFailure = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ReceiptAnalysisAttemptId("attempt-echo-terminal-failure"), terminalFailure.attemptId)
        assertEquals(ProviderTerminalFailureReason.RESPONSE_FAILED, terminalFailure.reason)
    }

    @Test
    fun `invocationConfirmedPendingResponse echoes the attemptId it received and carries the configured correlation reference`(): Unit =
        runBlocking {
            val reference = ProviderCorrelationReference("test-correlation-reference")
            val fake = DeterministicReceiptAnalysisProviderFake.invocationConfirmedPendingResponse(reference)

            val outcome = fake(sampleAttempt("attempt-echo-pending"))

            val pending = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse>(outcome)
            assertEquals(ReceiptAnalysisAttemptId("attempt-echo-pending"), pending.attemptId)
            assertEquals(reference, pending.correlationReference)
        }

    @Test
    fun `no field on the attempt controls which scenario runs -- only construction does`(): Unit = runBlocking {
        val fake = DeterministicReceiptAnalysisProviderFake.notInvoked(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE)

        val outcomeA = fake(sampleAttempt("attempt-a"))
        val outcomeB = fake(sampleAttempt("attempt-b"))

        assertIs<ReceiptAnalysisProviderOutcome.NotInvoked>(outcomeA)
        assertIs<ReceiptAnalysisProviderOutcome.NotInvoked>(outcomeB)
        assertEquals(2, fake.invocationCount)
    }
}
