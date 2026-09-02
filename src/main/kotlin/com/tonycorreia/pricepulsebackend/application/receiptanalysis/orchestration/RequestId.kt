package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

@JvmInline
value class RequestId(val value: String) {
    init {
        require(value.isNotBlank()) { "RequestId must not be blank" }
    }
}
