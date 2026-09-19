package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

/**
 * Market attribute signature (ADR-015 D4; S2 design §6). Absent brand/variant are null: explicit, never
 * equal to a value. Brand and variant are structured inputs only, never extracted from the title (PO-4).
 */
data class AttributeSignature(
    val brand: MarketTextKey?,
    val canonicalName: MarketTextKey,
    val variant: MarketTextKey?,
    val packageMeasure: NormalizedPackageMeasure
)

/**
 * Signature derivation from a market title (S2 design §7, §8). `analysisInput` is the title after
 * Kotlin's `trim()` — never the JVM's own trim or strip, which keep NBSP (design G-2). The measure comes
 * only from S1b; each of its spans is replaced by one ASCII space, last to first, and everything else is
 * kept as written before [MarketTextKey] is applied. The S1b ambiguity reason is never forwarded.
 *
 * Structured [brand] and [variant] are keyed with [MarketTextKey] as supplied: `null` is absent, a blank
 * value is rejected and never turned into `null` (SR-1). The first failing step of design §9.1 wins (SR-8).
 */
internal fun signaturePath(title: String, brand: String?, variant: String?): ProductKeyResult {
    if (title.isBlank()) return ProductKeyResult.Rejected(InputRejection.BlankTitle)
    if (brand != null && brand.isBlank()) return ProductKeyResult.Rejected(InputRejection.BlankBrand)
    if (variant != null && variant.isBlank()) return ProductKeyResult.Rejected(InputRejection.BlankVariant)
    val analysisInput = title.trim()
    val normalized = when (val measure = normalizePackageMeasure(analysisInput)) {
        is PackageMeasureResult.Absent -> return ProductKeyResult.Unresolved(UnresolvedReason.PACKAGE_MEASURE_ABSENT)
        is PackageMeasureResult.Ambiguous -> return ProductKeyResult.Unresolved(UnresolvedReason.PACKAGE_MEASURE_AMBIGUOUS)
        is PackageMeasureResult.Normalized -> measure
    }
    val remainder = normalized.spans.sortedByDescending { it.first }
        .fold(analysisInput) { text, span -> text.replaceRange(span, " ") }
    if (remainder.isBlank()) return ProductKeyResult.Unresolved(UnresolvedReason.EMPTY_CANONICAL_NAME)
    val signature = AttributeSignature(
        brand = brand?.let(MarketTextKey::from),
        canonicalName = MarketTextKey.from(remainder),
        variant = variant?.let(MarketTextKey::from),
        packageMeasure = normalized.measure
    )
    return ProductKeyResult.Resolved.ByAttributes(signature)
}
