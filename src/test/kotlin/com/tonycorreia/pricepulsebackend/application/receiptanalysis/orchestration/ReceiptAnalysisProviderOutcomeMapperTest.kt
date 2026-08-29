package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderRejectionReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ReceiptAnalysisProviderOutcomeMapperTest {

    private val expectedAttemptId = ReceiptAnalysisAttemptId("attempt-expected")
    private val divergentAttemptId = ReceiptAnalysisAttemptId("attempt-divergent")

    private fun sampleDocument(): ValidatedReceiptAnalysisResultV1 {
        val projectRoot = File(System.getProperty("user.dir"))
        val fixture = File(projectRoot, "contracts/fixtures/valid/complete-with-items.json")
        val document = ValidatedReceiptAnalysisResultV1.from(fixture.readBytes())
        assertNotNull(document)
        return document
    }

    @Test
    fun `completed becomes succeeded carrying the same document`() {
        val document = sampleDocument()

        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.Completed(document)
        )

        val succeeded = assertIs<OutcomeApplication.Succeeded>(application)
        assertEquals(document.serialize().toList(), succeeded.document.serialize().toList())
    }

    @Test
    fun `notInvoked becomes failedNoProvider carrying the same reason`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.NotInvoked(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE)
        )

        val failedNoProvider = assertIs<OutcomeApplication.FailedNoProvider>(application)
        assertEquals(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE, failedNoProvider.reason)
    }

    @Test
    fun `invocationConfirmedInvalidResponse with a matching attemptId becomes failed`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(expectedAttemptId)
        )

        assertIs<OutcomeApplication.Failed>(application)
    }

    @Test
    fun `invocationConfirmedInvalidResponse with a divergent attemptId never becomes failed`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(divergentAttemptId)
        )

        val mismatch = assertIs<OutcomeApplication.AttemptIdMismatch>(application)
        assertEquals(expectedAttemptId, mismatch.expectedAttemptId)
        assertEquals(divergentAttemptId, mismatch.receivedAttemptId)
    }

    @Test
    fun `invocationUncertain with a matching attemptId becomes reconcilingDetected`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationUncertain(expectedAttemptId)
        )

        assertIs<OutcomeApplication.ReconcilingDetected>(application)
    }

    @Test
    fun `invocationUncertain with a divergent attemptId never becomes reconcilingDetected`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationUncertain(divergentAttemptId)
        )

        val mismatch = assertIs<OutcomeApplication.AttemptIdMismatch>(application)
        assertEquals(expectedAttemptId, mismatch.expectedAttemptId)
        assertEquals(divergentAttemptId, mismatch.receivedAttemptId)
    }

    @Test
    fun `invocationRejected with a matching attemptId becomes failedNoProvider carrying MALFORMED_REQUEST`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationRejected(expectedAttemptId, ProviderRejectionReason.MALFORMED_REQUEST)
        )

        val failedNoProvider = assertIs<OutcomeApplication.FailedNoProvider>(application)
        assertEquals(ProviderRejectionReason.MALFORMED_REQUEST, failedNoProvider.reason)
    }

    @Test
    fun `invocationRejected with a matching attemptId becomes failedNoProvider carrying AUTHENTICATION_REJECTED`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationRejected(expectedAttemptId, ProviderRejectionReason.AUTHENTICATION_REJECTED)
        )

        val failedNoProvider = assertIs<OutcomeApplication.FailedNoProvider>(application)
        assertEquals(ProviderRejectionReason.AUTHENTICATION_REJECTED, failedNoProvider.reason)
    }

    @Test
    fun `invocationRejected with a matching attemptId becomes failedNoProvider carrying ACCESS_FORBIDDEN`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationRejected(expectedAttemptId, ProviderRejectionReason.ACCESS_FORBIDDEN)
        )

        val failedNoProvider = assertIs<OutcomeApplication.FailedNoProvider>(application)
        assertEquals(ProviderRejectionReason.ACCESS_FORBIDDEN, failedNoProvider.reason)
    }

    @Test
    fun `invocationRejected with a divergent attemptId never becomes failedNoProvider`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationRejected(divergentAttemptId, ProviderRejectionReason.MALFORMED_REQUEST)
        )

        val mismatch = assertIs<OutcomeApplication.AttemptIdMismatch>(application)
        assertEquals(expectedAttemptId, mismatch.expectedAttemptId)
        assertEquals(divergentAttemptId, mismatch.receivedAttemptId)
    }

    @Test
    fun `invocationConfirmedTerminalFailure with a matching attemptId becomes failedNoProvider carrying the reason`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(
                expectedAttemptId,
                ProviderTerminalFailureReason.RETRY_EXHAUSTED
            )
        )

        val failedNoProvider = assertIs<OutcomeApplication.FailedNoProvider>(application)
        assertEquals(ProviderTerminalFailureReason.RETRY_EXHAUSTED, failedNoProvider.reason)
    }

    @Test
    fun `invocationConfirmedTerminalFailure with RATE_LIMITED and a matching attemptId becomes failedNoProvider carrying RATE_LIMITED`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(
                expectedAttemptId,
                ProviderTerminalFailureReason.RATE_LIMITED
            )
        )

        val failedNoProvider = assertIs<OutcomeApplication.FailedNoProvider>(application)
        assertEquals(ProviderTerminalFailureReason.RATE_LIMITED, failedNoProvider.reason)
    }

    @Test
    fun `invocationConfirmedTerminalFailure with TRANSIENT_PROVIDER_FAILURE and a matching attemptId becomes failedNoProvider carrying TRANSIENT_PROVIDER_FAILURE`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(
                expectedAttemptId,
                ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE
            )
        )

        val failedNoProvider = assertIs<OutcomeApplication.FailedNoProvider>(application)
        assertEquals(ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE, failedNoProvider.reason)
    }

    @Test
    fun `invocationConfirmedTerminalFailure with a divergent attemptId never becomes failedNoProvider`() {
        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(
                divergentAttemptId,
                ProviderTerminalFailureReason.BILLING_OR_QUOTA_EXHAUSTED
            )
        )

        val mismatch = assertIs<OutcomeApplication.AttemptIdMismatch>(application)
        assertEquals(expectedAttemptId, mismatch.expectedAttemptId)
        assertEquals(divergentAttemptId, mismatch.receivedAttemptId)
    }

    @Test
    fun `invocationConfirmedPendingResponse with a matching attemptId becomes reconcilingWithProviderCorrelation carrying the reference`() {
        val reference = ProviderCorrelationReference("test-correlation-reference")

        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse(expectedAttemptId, reference)
        )

        val reconciling = assertIs<OutcomeApplication.ReconcilingWithProviderCorrelation>(application)
        assertEquals(reference, reconciling.reference)
    }

    @Test
    fun `invocationConfirmedPendingResponse with a divergent attemptId never becomes reconcilingWithProviderCorrelation, never carries the reference`() {
        val reference = ProviderCorrelationReference("test-correlation-reference")

        val application = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse(divergentAttemptId, reference)
        )

        val mismatch = assertIs<OutcomeApplication.AttemptIdMismatch>(application)
        assertEquals(expectedAttemptId, mismatch.expectedAttemptId)
        assertEquals(divergentAttemptId, mismatch.receivedAttemptId)
    }

    @Test
    fun `notInvoked and invocationRejected reasons are never assignable to each other's variant`() {
        val notInvoked = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.NotInvoked(ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED)
        )
        val rejected = mapReceiptAnalysisProviderOutcome(
            expectedAttemptId,
            ReceiptAnalysisProviderOutcome.InvocationRejected(expectedAttemptId, ProviderRejectionReason.MALFORMED_REQUEST)
        )

        val notInvokedReason = assertIs<OutcomeApplication.FailedNoProvider>(notInvoked).reason
        val rejectedReason = assertIs<OutcomeApplication.FailedNoProvider>(rejected).reason

        assertIs<ProviderCallFailureReason>(notInvokedReason)
        assertIs<ProviderRejectionReason>(rejectedReason)
    }
}
