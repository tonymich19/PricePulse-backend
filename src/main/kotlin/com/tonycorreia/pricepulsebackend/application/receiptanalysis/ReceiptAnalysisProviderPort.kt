package com.tonycorreia.pricepulsebackend.application.receiptanalysis

/**
 * Boundary to a receipt analysis provider (deterministic fake in tests today; a real OpenAI
 * adapter later replaces only the implementation -- this interface and outcome shape stay the
 * same). Never decides FAILED_NO_PROVIDER/RECONCILING/credit -- that is the orchestration's
 * responsibility (receiptanalysis-slice-report.md 6.10.3/6.10.13).
 */
interface ReceiptAnalysisProviderPort {
    suspend operator fun invoke(attempt: ReceiptAnalysisAttempt): ReceiptAnalysisProviderOutcome
}
