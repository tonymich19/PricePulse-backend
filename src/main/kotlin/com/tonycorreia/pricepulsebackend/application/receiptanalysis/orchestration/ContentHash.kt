package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import java.security.MessageDigest

/**
 * Canonical wire encoding fixed in receiptanalysis-slice-report.md 6.10.16.A: SHA-256 over the
 * raw image bytes exactly as received, lowercase hex, no prefix, always 64 characters.
 */
@JvmInline
value class ContentHash(val value: String) {
    init {
        require(CANONICAL_PATTERN.matches(value)) {
            "ContentHash must be a lowercase 64-character hex SHA-256 digest, was: $value"
        }
    }

    companion object {
        private val CANONICAL_PATTERN = Regex("^[0-9a-f]{64}$")

        fun sha256Of(bytes: ByteArray): ContentHash {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return ContentHash(digest.joinToString(separator = "") { byte -> "%02x".format(byte) })
        }
    }
}
