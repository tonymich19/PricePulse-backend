package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

/** The form of a market product key. Exactly two (PO-8); each maps to exactly one identity level. */
enum class ProductKeyBasis(val identityLevel: ProductIdentityLevel) {
    GTIN(ProductIdentityLevel.EXACT_IDENTITY),
    ATTRIBUTE_SIGNATURE(ProductIdentityLevel.ATTRIBUTE_IDENTITY)
}

/** Market canonical identity. Exactly two forms (PO-8); the basis is the form, so it cannot be lost. */
sealed interface ProductKey {
    val basis: ProductKeyBasis

    /** Structural variant, for `is`/`when` matching. Built only through [ProductKey.fromGtin] (SR-5). */
    @ConsistentCopyVisibility
    data class GtinKey internal constructor(val gtin: Gtin) : ProductKey {
        override val basis get() = ProductKeyBasis.GTIN
    }

    /** Structural variant, for `is`/`when` matching. Built only through [ProductKey.fromAttributes] (SR-5). */
    @ConsistentCopyVisibility
    data class AttributeKey internal constructor(val signature: AttributeSignature) : ProductKey {
        override val basis get() = ProductKeyBasis.ATTRIBUTE_SIGNATURE
    }

    companion object {
        /** Public construction path of a GTIN key — delivery plan §6 S2 «Produz». */
        fun fromGtin(gtin: Gtin): ProductKey = GtinKey(gtin)

        /** Public construction path of an attribute-signature key — delivery plan §6 S2 «Produz». */
        fun fromAttributes(signature: AttributeSignature): ProductKey = AttributeKey(signature)
    }
}

/** No identity level on the interface: only the variants that carry one declare it (S2 design §11). */
sealed interface ProductKeyResult {

    /**
     * Identity established. Two shapes, so that an attribute key and a second, different signature can
     * never coexist (SR-6): each shape stores only its identity material, and [key] is derived from it
     * through the factories.
     */
    sealed interface Resolved : ProductKeyResult {
        val key: ProductKey
        /** Attribute key: the key's own signature. GTIN key: the best-effort auxiliary signature (PO-7). */
        val attributeSignature: AttributeSignature?
        val identityLevel: ProductIdentityLevel get() = key.basis.identityLevel

        @ConsistentCopyVisibility
        data class ByGtin internal constructor(
            val gtin: Gtin,
            val auxiliaryAttributeSignature: AttributeSignature?
        ) : Resolved {
            override val key: ProductKey get() = ProductKey.fromGtin(gtin)
            override val attributeSignature: AttributeSignature? get() = auxiliaryAttributeSignature
        }

        @ConsistentCopyVisibility
        data class ByAttributes internal constructor(val signature: AttributeSignature) : Resolved {
            override val key: ProductKey get() = ProductKey.fromAttributes(signature)
            override val attributeSignature: AttributeSignature get() = signature
        }
    }

    /** Acceptable input from which no sufficient identity can be established (PO-5, PO-2). */
    data class Unresolved(val reason: UnresolvedReason) : ProductKeyResult {
        val identityLevel: ProductIdentityLevel get() = ProductIdentityLevel.AMBIGUOUS_IDENTITY
    }

    /** Structurally invalid input (PO-6, SR-1, blank title). Carries no identity level (S2 design §11). */
    data class Rejected(val reason: InputRejection) : ProductKeyResult
}

enum class UnresolvedReason { PACKAGE_MEASURE_ABSENT, PACKAGE_MEASURE_AMBIGUOUS, EMPTY_CANONICAL_NAME }

sealed interface InputRejection {
    data class InvalidGtin(val rejection: GtinRejection) : InputRejection
    data object BlankTitle : InputRejection
    data object BlankBrand : InputRejection
    data object BlankVariant : InputRejection
}

/**
 * The only entry point for external data (S2 design §9.1). Pure and deterministic.
 *
 * A supplied GTIN is evaluated first and alone (design §9.3): an invalid one is rejected with no fallback
 * to the signature (PO-6); a valid one establishes the identity, and the signature path only fills the
 * best-effort auxiliary signature, whose failure never reaches the result (PO-7).
 */
fun resolveProductKey(title: String, gtin: String?, brand: String?, variant: String?): ProductKeyResult {
    if (gtin == null) return signaturePath(title, brand, variant)
    return when (val parsed = Gtin.parse(gtin)) {
        is GtinParseResult.Invalid -> ProductKeyResult.Rejected(InputRejection.InvalidGtin(parsed.rejection))
        is GtinParseResult.Valid -> ProductKeyResult.Resolved.ByGtin(
            parsed.gtin,
            auxiliaryAttributeSignature = (signaturePath(title, brand, variant) as? ProductKeyResult.Resolved)?.attributeSignature
        )
    }
}
