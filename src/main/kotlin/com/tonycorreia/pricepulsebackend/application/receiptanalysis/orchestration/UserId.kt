package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) { "UserId must not be blank" }
    }
}
