package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

/**
 * GTIN in canonical form: exactly 14 ASCII digits, left-padded with zeros, valid GS1 check digit
 * (S2 design §5). Created only by [parse], which judges the raw value as supplied: it never trims or
 * repairs it, and accepts only ASCII `0`–`9`, never another script's digits.
 */
@JvmInline
value class Gtin private constructor(val value: String) {

    init {
        require(value.length == CANONICAL_LENGTH && value.all { it in '0'..'9' }) { "not a canonical GTIN" }
    }

    companion object {
        private const val CANONICAL_LENGTH = 14
        private val SUPPORTED_LENGTHS = setOf(8, 12, 13, 14)

        /** Applies G1–G4 in declaration order of [GtinRejection] and reports the first failure. Never throws. */
        fun parse(raw: String): GtinParseResult {
            if (!raw.all { it in '0'..'9' }) return GtinParseResult.Invalid(GtinRejection.NON_DIGIT)
            if (raw.length !in SUPPORTED_LENGTHS) return GtinParseResult.Invalid(GtinRejection.UNSUPPORTED_LENGTH)
            if (raw.all { it == '0' }) return GtinParseResult.Invalid(GtinRejection.ALL_ZEROS)
            val canonical = raw.padStart(CANONICAL_LENGTH, '0')
            if (canonical.last().digitToInt() != checkDigit(canonical)) {
                return GtinParseResult.Invalid(GtinRejection.INVALID_CHECK_DIGIT)
            }
            return GtinParseResult.Valid(Gtin(canonical))
        }

        /** GS1 mod 10 over positions 1–13, weighted 3, 1, 3, … from the left. */
        private fun checkDigit(canonical: String): Int {
            val sum = (0 until CANONICAL_LENGTH - 1).sumOf { i ->
                canonical[i].digitToInt() * if (i % 2 == 0) 3 else 1
            }
            return (10 - sum % 10) % 10
        }
    }
}

sealed interface GtinParseResult {
    data class Valid(val gtin: Gtin) : GtinParseResult
    data class Invalid(val rejection: GtinRejection) : GtinParseResult
}

/** Declaration order is the reporting order (as [PackageMeasureAmbiguity]). Diagnostic only. */
enum class GtinRejection { NON_DIGIT, UNSUPPORTED_LENGTH, ALL_ZEROS, INVALID_CHECK_DIGIT }
