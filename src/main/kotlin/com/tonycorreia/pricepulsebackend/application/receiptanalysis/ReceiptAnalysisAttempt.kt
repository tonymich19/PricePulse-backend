package com.tonycorreia.pricepulsebackend.application.receiptanalysis

/**
 * Explicit request carrying both the durable attempt identifier and the image to analyze --
 * resolves the earlier documentation ambiguity of "part of the image or a sibling parameter"
 * (receiptanalysis-slice-report.md 6.10.13.3, rodada 71 correction).
 */
data class ReceiptAnalysisAttempt(
    val attemptId: ReceiptAnalysisAttemptId,
    val image: PreparedReceiptImage
)
