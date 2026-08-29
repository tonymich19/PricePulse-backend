package com.tonycorreia.pricepulsebackend.infrastructure.openai

/**
 * Opaque, redacted credential -- same pattern as [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference]
 * (6.10.39). [toString] never discloses [value] -- accidental exposure in a log line or exception
 * message never leaks it in plain text. Backend-only: never an Android input, database field,
 * request body, or exception message. See receiptanalysis-slice-report.md 6.10.43.1.
 */
class OpenAiCredential private constructor(private val value: String) {

    fun value(): String = value

    override fun toString(): String = "OpenAiCredential(<redacted>)"

    companion object {
        operator fun invoke(value: String): OpenAiCredential {
            require(value.isNotBlank()) { "OpenAiCredential must not be blank" }
            // A credential read from a file mounted on Windows keeps its CRLF ending, and a lone
            // trailing carriage return is enough for Ktor to reject the Authorization header before
            // opening a connection -- surfacing as an unexplainable instant failure, not as a bad
            // credential. Rejected here, at construction, so no such value can reach a request from
            // any path. The message names neither the character's position nor any part of [value].
            require(value.none { it.isISOControl() }) {
                "OpenAiCredential must not contain control characters (a CR/LF from a file ending is the usual cause)"
            }
            return OpenAiCredential(value)
        }
    }
}
