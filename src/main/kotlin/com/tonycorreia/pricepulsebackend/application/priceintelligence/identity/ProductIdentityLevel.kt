package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

/**
 * How firmly a market identity attempt established a product (S2 design §11). `EXACT_IDENTITY` and
 * `ATTRIBUTE_IDENTITY` follow from the key's basis; `AMBIGUOUS_IDENTITY` marks an attempt that could not
 * establish an identity.
 */
enum class ProductIdentityLevel { EXACT_IDENTITY, ATTRIBUTE_IDENTITY, AMBIGUOUS_IDENTITY }
