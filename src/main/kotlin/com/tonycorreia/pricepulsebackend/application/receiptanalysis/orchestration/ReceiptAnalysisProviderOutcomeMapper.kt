package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome

/**
 * Pure, no I/O. Any confirmed outcome (InvocationUncertain/InvocationConfirmedInvalidResponse/
 * InvocationRejected/InvocationConfirmedTerminalFailure/InvocationConfirmedPendingResponse) whose
 * attemptId does not match [expectedAttemptId] never becomes Succeeded/Failed/FailedNoProvider/
 * ReconcilingDetected/ReconcilingWithProviderCorrelation -- see [OutcomeApplication.AttemptIdMismatch].
 * A mismatched [ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse] never carries
 * its correlation reference into [OutcomeApplication.AttemptIdMismatch] -- a mismatch is never
 * trusted enough to persist anything about it. See receiptanalysis-slice-report.md 6.10.16 code
 * slice, 6.10.32, 6.10.37, 6.10.39.
 */
fun mapReceiptAnalysisProviderOutcome(
    expectedAttemptId: ReceiptAnalysisAttemptId,
    outcome: ReceiptAnalysisProviderOutcome
): OutcomeApplication = when (outcome) {
    is ReceiptAnalysisProviderOutcome.Completed ->
        OutcomeApplication.Succeeded(outcome.document)

    is ReceiptAnalysisProviderOutcome.NotInvoked ->
        OutcomeApplication.FailedNoProvider(outcome.reason)

    is ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse ->
        if (outcome.attemptId == expectedAttemptId) {
            OutcomeApplication.Failed
        } else {
            OutcomeApplication.AttemptIdMismatch(expectedAttemptId, outcome.attemptId)
        }

    is ReceiptAnalysisProviderOutcome.InvocationUncertain ->
        if (outcome.attemptId == expectedAttemptId) {
            OutcomeApplication.ReconcilingDetected
        } else {
            OutcomeApplication.AttemptIdMismatch(expectedAttemptId, outcome.attemptId)
        }

    is ReceiptAnalysisProviderOutcome.InvocationRejected ->
        if (outcome.attemptId == expectedAttemptId) {
            OutcomeApplication.FailedNoProvider(outcome.reason)
        } else {
            OutcomeApplication.AttemptIdMismatch(expectedAttemptId, outcome.attemptId)
        }

    is ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure ->
        if (outcome.attemptId == expectedAttemptId) {
            OutcomeApplication.FailedNoProvider(outcome.reason)
        } else {
            OutcomeApplication.AttemptIdMismatch(expectedAttemptId, outcome.attemptId)
        }

    is ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse ->
        if (outcome.attemptId == expectedAttemptId) {
            OutcomeApplication.ReconcilingWithProviderCorrelation(outcome.correlationReference)
        } else {
            OutcomeApplication.AttemptIdMismatch(expectedAttemptId, outcome.attemptId)
        }
}
