package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import java.util.Locale

/**
 * The single notion of text equality for market product identity, mirroring the app's
 * `ProductNameMatchKey` (ADR-012 D7). Trims with Kotlin's `trim()`, collapses runs of
 * `\p{javaWhitespace}` to one space, rejects an empty result and lowercases with a fixed [Locale.ROOT].
 *
 * The two whitespace notions differ, exactly as in the app: `trim()` removes every Kotlin
 * `Char.isWhitespace` character at the borders (NBSP, narrow NBSP and figure space included), while
 * the collapse leaves internal NBSP, narrow NBSP and figure space untouched. Zero width space is never
 * removed. Deliberately preserves accents, punctuation, hyphens, apostrophes, numbers, units and token
 * order, and applies no Unicode normalization.
 *
 * Do not replace `trim()` with `java.lang.String.trim()` or `strip()`: neither removes NBSP.
 *
 * The app's implementation cannot be imported: separate repositories, private constructor. Parity is
 * proved by [MarketTextKeyParityTest] and by the app's contract test, both against
 * `contracts/fixtures/text-key/parity-cases.v1.json`. That fixture, not this code, is the contract.
 */
@JvmInline
value class MarketTextKey private constructor(val value: String) {

    companion object {
        private val WHITESPACE = Regex("\\p{javaWhitespace}+")

        fun from(text: String): MarketTextKey {
            val collapsed = text.trim().replace(WHITESPACE, " ")
            require(collapsed.isNotEmpty()) { "text must not be blank" }
            return MarketTextKey(collapsed.lowercase(Locale.ROOT))
        }
    }
}
