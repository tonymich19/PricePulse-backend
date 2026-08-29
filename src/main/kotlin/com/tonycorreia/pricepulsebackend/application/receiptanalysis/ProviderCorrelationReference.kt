package com.tonycorreia.pricepulsebackend.application.receiptanalysis

/**
 * Opaque, provider-neutral identifier for a specific confirmed-but-pending provider invocation
 * (`queued`/`in_progress`) -- nowhere in this layer or its callers does this type or its field
 * names reference OpenAI or `response.id`; only a future infrastructure adapter knows that
 * mapping. [toString] never exposes [value] -- accidental exposure in a log line or exception
 * message never leaks it in plain text, the same discipline already required for the provider
 * credential. See receiptanalysis-slice-report.md 6.10.36/6.10.38/6.10.39.
 */
class ProviderCorrelationReference private constructor(private val value: String) {

    fun value(): String = value

    override fun toString(): String = "ProviderCorrelationReference(<redacted>)"

    override fun equals(other: Any?): Boolean =
        other is ProviderCorrelationReference && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        operator fun invoke(value: String): ProviderCorrelationReference {
            require(value.isNotBlank()) { "ProviderCorrelationReference must not be blank" }
            return ProviderCorrelationReference(value)
        }
    }
}
