package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

/**
 * Opaque identifier for one reservation, generated once by [ReceiptAnalysisOperationStore]
 * when a new (userId, requestId) reservation is created -- never derived from requestId alone,
 * which is only unique within a single user. See receiptanalysis-slice-report.md 6.10.16.B.
 */
@JvmInline
value class ReceiptAnalysisOperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "ReceiptAnalysisOperationId must not be blank" }
    }
}
