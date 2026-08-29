package com.tonycorreia.pricepulsebackend.application.receiptanalysis

/**
 * Test-only deterministic [ReceiptAnalysisProviderPort]. The scenario is fixed **only** by the
 * constructor/companion factory used to build it -- never by anything on the
 * [ReceiptAnalysisAttempt] itself (no field, header, environment variable, or route controls it).
 * Never touches a network. See receiptanalysis-slice-report.md 6.10.4/6.10.13.5.
 */
class DeterministicReceiptAnalysisProviderFake private constructor(
    private val scenario: (ReceiptAnalysisAttempt) -> ReceiptAnalysisProviderOutcome
) : ReceiptAnalysisProviderPort {

    var invocationCount = 0
        private set

    override suspend fun invoke(attempt: ReceiptAnalysisAttempt): ReceiptAnalysisProviderOutcome {
        invocationCount++
        return scenario(attempt)
    }

    companion object {
        fun completed(document: ValidatedReceiptAnalysisResultV1) =
            DeterministicReceiptAnalysisProviderFake { ReceiptAnalysisProviderOutcome.Completed(document) }

        fun notInvoked(reason: ProviderCallFailureReason) =
            DeterministicReceiptAnalysisProviderFake { ReceiptAnalysisProviderOutcome.NotInvoked(reason) }

        /** Echoes the attemptId it received -- never invents one of its own. */
        fun invocationUncertain() =
            DeterministicReceiptAnalysisProviderFake { attempt ->
                ReceiptAnalysisProviderOutcome.InvocationUncertain(attempt.attemptId)
            }

        /** Echoes the attemptId it received -- never invents one of its own. */
        fun invocationConfirmedInvalidResponse() =
            DeterministicReceiptAnalysisProviderFake { attempt ->
                ReceiptAnalysisProviderOutcome.InvocationConfirmedInvalidResponse(attempt.attemptId)
            }

        /** Echoes the attemptId it received -- never invents one of its own, never inspects any
         * other part of the attempt (no HTTP behavior, no dependency on image content). */
        fun invocationRejected(reason: ProviderRejectionReason) =
            DeterministicReceiptAnalysisProviderFake { attempt ->
                ReceiptAnalysisProviderOutcome.InvocationRejected(attempt.attemptId, reason)
            }

        /** Echoes the attemptId it received -- never invents one of its own, never inspects any
         * other part of the attempt (no HTTP behavior, no dependency on image content). */
        fun invocationConfirmedTerminalFailure(reason: ProviderTerminalFailureReason) =
            DeterministicReceiptAnalysisProviderFake { attempt ->
                ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(attempt.attemptId, reason)
            }

        /** Echoes the attemptId it received -- never invents one of its own, never inspects any
         * other part of the attempt (no HTTP behavior, no dependency on image content). */
        fun invocationConfirmedPendingResponse(correlationReference: ProviderCorrelationReference) =
            DeterministicReceiptAnalysisProviderFake { attempt ->
                ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse(attempt.attemptId, correlationReference)
            }
    }
}
