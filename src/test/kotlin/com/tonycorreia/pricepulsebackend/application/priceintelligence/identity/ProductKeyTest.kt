package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductKeyTest {

    // Task 1 — design §11, PO-8.

    @Test
    fun `each basis maps to exactly one identity level`() {
        assertEquals(ProductIdentityLevel.EXACT_IDENTITY, ProductKeyBasis.GTIN.identityLevel)
        assertEquals(ProductIdentityLevel.ATTRIBUTE_IDENTITY, ProductKeyBasis.ATTRIBUTE_SIGNATURE.identityLevel)
    }

    @Test
    fun `there are exactly two bases and no source-id basis`() {
        // PO-8, C2: no SOURCE_ID basis, no third form of key.
        assertEquals(listOf("GTIN", "ATTRIBUTE_SIGNATURE"), ProductKeyBasis.entries.map { it.name })
    }

    @Test
    fun `there are exactly three identity levels`() {
        assertEquals(
            listOf("EXACT_IDENTITY", "ATTRIBUTE_IDENTITY", "AMBIGUOUS_IDENTITY"),
            ProductIdentityLevel.entries.map { it.name }
        )
    }

    // Task 3 — design §4.2, §4.4, §10, PO-10, SR-5. Keys are built only through the factories.

    private val signature = AttributeSignature(null, MarketTextKey.from("dove"), null, NormalizedPackageMeasure(90, MeasureUnit.MASS, 1))
    private fun gtin(raw: String) = (Gtin.parse(raw) as GtinParseResult.Valid).gtin

    @Test
    fun `the factories build the two structural variants`() {
        val byGtin: ProductKey = ProductKey.fromGtin(gtin("7896006711155"))
        val byAttributes: ProductKey = ProductKey.fromAttributes(signature)
        assertEquals(gtin("7896006711155"), assertIs<ProductKey.GtinKey>(byGtin).gtin)
        assertEquals(signature, assertIs<ProductKey.AttributeKey>(byAttributes).signature)
    }

    @Test
    fun `the basis is the form of the key`() {
        assertEquals(ProductKeyBasis.GTIN, ProductKey.fromGtin(gtin("7896006711155")).basis)
        assertEquals(ProductKeyBasis.ATTRIBUTE_SIGNATURE, ProductKey.fromAttributes(signature).basis)
    }

    @Test
    fun `same canonical gtin gives the same key and hash code`() {
        assertEquals(ProductKey.fromGtin(gtin("036000291452")), ProductKey.fromGtin(gtin("00036000291452")))
        assertEquals(ProductKey.fromGtin(gtin("036000291452")).hashCode(), ProductKey.fromGtin(gtin("00036000291452")).hashCode())
    }

    @Test
    fun `different gtins give different keys`() {
        assertNotEquals(ProductKey.fromGtin(gtin("7896006711155")), ProductKey.fromGtin(gtin("7896006744115")))
    }

    @Test
    fun `same signature gives the same attribute key and hash code`() {
        val other = signature.copy()
        assertEquals(ProductKey.fromAttributes(signature), ProductKey.fromAttributes(other))
        assertEquals(ProductKey.fromAttributes(signature).hashCode(), ProductKey.fromAttributes(other).hashCode())
    }

    @Test
    fun `a gtin key never equals an attribute key`() {
        assertNotEquals(ProductKey.fromGtin(gtin("7896006711155")), ProductKey.fromAttributes(signature))
    }

    // Task 4 — design §4.2, §9, §11, PO-11, SR-6.

    @Test
    fun `a resolved result derives key, level and signature from its own data`() {
        val byGtin = ProductKeyResult.Resolved.ByGtin(gtin("7896006711155"), auxiliaryAttributeSignature = signature)
        assertEquals(ProductKey.fromGtin(gtin("7896006711155")), byGtin.key)
        assertEquals(ProductIdentityLevel.EXACT_IDENTITY, byGtin.identityLevel)
        assertEquals(signature, byGtin.attributeSignature)

        val byAttributes = ProductKeyResult.Resolved.ByAttributes(signature)
        assertEquals(ProductKey.fromAttributes(signature), byAttributes.key)
        assertEquals(ProductIdentityLevel.ATTRIBUTE_IDENTITY, byAttributes.identityLevel)
        assertEquals(signature, byAttributes.attributeSignature)
    }

    @Test
    fun `the auxiliary signature never touches the gtin key`() {
        // SR-6: not in GtinKey equality/hash, basis or level.
        val withAux = ProductKeyResult.Resolved.ByGtin(gtin("7896006711155"), auxiliaryAttributeSignature = signature)
        val withoutAux = ProductKeyResult.Resolved.ByGtin(gtin("7896006711155"), auxiliaryAttributeSignature = null)
        assertEquals(withAux.key, withoutAux.key)
        assertEquals(withAux.key.hashCode(), withoutAux.key.hashCode())
        assertEquals(withAux.key.basis, withoutAux.key.basis)
        assertEquals(withAux.identityLevel, withoutAux.identityLevel)
    }

    @Test
    fun `an attribute result cannot hold a second signature`() {
        // SR-6: the inconsistent state has no field to exist in. Java reflection, no kotlin-reflect.
        fun fields(type: Class<*>) = type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("signature"), fields(ProductKeyResult.Resolved.ByAttributes::class.java))
        assertEquals(setOf("gtin", "auxiliaryAttributeSignature"), fields(ProductKeyResult.Resolved.ByGtin::class.java))
    }

    @Test
    fun `every unresolved result is ambiguous identity`() {
        // Delivery plan S2 RED: entrada insuficiente → AMBIGUOUS_IDENTITY (design §11).
        UnresolvedReason.entries.forEach {
            assertEquals(ProductIdentityLevel.AMBIGUOUS_IDENTITY, ProductKeyResult.Unresolved(it).identityLevel)
        }
    }

    @Test
    fun `a rejected result carries no identity level`() {
        // Design §11: no normative text assigns a level to invalid input. Java reflection, no kotlin-reflect.
        assertTrue(ProductKeyResult.Rejected::class.java.methods.none { it.name == "getIdentityLevel" })
        assertTrue(ProductKeyResult::class.java.methods.none { it.name == "getIdentityLevel" })
    }

    @Test
    fun `a gtin result may carry no signature`() {
        assertNull(ProductKeyResult.Resolved.ByGtin(gtin("7896006711155"), auxiliaryAttributeSignature = null).attributeSignature)
    }

    // Task 6 — design §9.2, PO-5, G-5.

    private fun resolve(title: String, gtin: String? = null, brand: String? = null, variant: String? = null) =
        resolveProductKey(title, gtin, brand, variant)

    @Test
    fun `every ambiguity reason of s1b is treated alike`() {
        val byReason = mapOf(
            PackageMeasureAmbiguity.AMBIGUOUS_DECIMAL_SEPARATOR to "Cafe 1.000 g",
            PackageMeasureAmbiguity.OUT_OF_RANGE to "Agua 99999999999999999999 ml",
            PackageMeasureAmbiguity.ZERO_QUANTITY to "Cafe 0 g",
            PackageMeasureAmbiguity.NON_EXACT_QUANTITY to "Cafe 1,5 g",
            PackageMeasureAmbiguity.MULTIPLE_SIZES to "Refrigerante Coca-Cola 2 L e Fanta 2 L",
            PackageMeasureAmbiguity.UNPARSED_PACK_SIGNAL to "Kit Dove 90g 6un"
        )
        assertEquals(PackageMeasureAmbiguity.entries.toSet(), byReason.keys)
        byReason.forEach { (reason, title) ->
            assertEquals(PackageMeasureResult.Ambiguous(reason), normalizePackageMeasure(title.trim()))
            assertEquals(ProductKeyResult.Unresolved(UnresolvedReason.PACKAGE_MEASURE_AMBIGUOUS), resolve(title))
        }
    }

    @Test
    fun `an absent measure gives no key`() {
        listOf("PAO FRANCES KG", "Arroz Tipo 1", "Cafe 500G\u200b", "Cafe 500\u00a0g")
            .forEach { assertEquals(ProductKeyResult.Unresolved(UnresolvedReason.PACKAGE_MEASURE_ABSENT), resolve(it)) }
    }

    @Test
    fun `a count not next to its size gives no key`() {
        assertEquals(ProductKeyResult.Unresolved(UnresolvedReason.PACKAGE_MEASURE_AMBIGUOUS), resolve("6un Dove 90g"))
    }

    @Test
    fun `a title made only of a measure gives no key`() {
        listOf("500g", "\u00a0500g\u00a0", "6 x 90 g", "90g 6un")
            .forEach { assertEquals(ProductKeyResult.Unresolved(UnresolvedReason.EMPTY_CANONICAL_NAME), resolve(it)) }
    }

    // Task 7 — design §4.3, §9.1, PO-4, SR-1, SR-8.

    @Test
    fun `brand and variant may be absent`() {
        val result = assertIs<ProductKeyResult.Resolved>(resolve("Sabonete Dove 90g"))
        val signature = assertIs<ProductKey.AttributeKey>(result.key).signature
        assertNull(signature.brand)
        assertNull(signature.variant)
        assertEquals(ProductIdentityLevel.ATTRIBUTE_IDENTITY, result.identityLevel)
        assertEquals(signature, result.attributeSignature)
    }

    @Test
    fun `structured brand and variant are keyed and kept apart`() {
        val result = assertIs<ProductKeyResult.Resolved>(resolve("Refrigerante 2L", brand = " Coca-Cola ", variant = "ZERO"))
        val signature = assertIs<ProductKey.AttributeKey>(result.key).signature
        assertEquals(MarketTextKey.from("coca-cola"), signature.brand)
        assertEquals(MarketTextKey.from("zero"), signature.variant)
        assertEquals("refrigerante", signature.canonicalName.value)
    }

    @Test
    fun `a different structured brand never matches`() {
        // Parent spec §6.2: marca diferente não é identidade.
        assertNotEquals(resolve("Arroz Branco 5kg", brand = "Camil"), resolve("Arroz Branco 5kg", brand = "Tio Joao"))
        assertNotEquals(resolve("Arroz Branco 5kg", brand = "Camil"), resolve("Arroz Branco 5kg"))
    }

    @Test
    fun `blank inputs are rejected on the signature path`() {
        assertEquals(ProductKeyResult.Rejected(InputRejection.BlankTitle), resolve(""))
        assertEquals(ProductKeyResult.Rejected(InputRejection.BlankTitle), resolve(" \u00a0 "))
        assertEquals(ProductKeyResult.Rejected(InputRejection.BlankBrand), resolve("Cafe 500g", brand = " "))
        assertEquals(ProductKeyResult.Rejected(InputRejection.BlankVariant), resolve("Cafe 500g", variant = ""))
    }

    @Test
    fun `the first failing step is reported`() {
        assertEquals(ProductKeyResult.Rejected(InputRejection.BlankTitle), resolve("", brand = ""))
        assertEquals(ProductKeyResult.Rejected(InputRejection.BlankBrand), resolve("PAO FRANCES KG", brand = ""))
    }

    // Task 8 — design §9, §10, PO-6, PO-7, SR-2, SR-3, G-3, G-4.

    @Test
    fun `a valid gtin resolves to an exact gtin key`() {
        val result = assertIs<ProductKeyResult.Resolved.ByGtin>(resolve("Arroz Branco 5kg", gtin = "7896006711155"))
        assertEquals(ProductKey.fromGtin(gtin("7896006711155")), result.key)
        assertEquals(ProductKeyBasis.GTIN, result.key.basis)
        assertEquals(ProductIdentityLevel.EXACT_IDENTITY, result.identityLevel)
    }

    @Test
    fun `same gtin with different titles gives the same key`() {
        val a = assertIs<ProductKeyResult.Resolved>(resolve("Arroz Branco Camil Tipo 1 5kg", gtin = "7896006711155")).key
        val b = assertIs<ProductKeyResult.Resolved>(resolve("ARROZ CAMIL 1KG", gtin = "07896006711155")).key
        assertEquals(a, b)
    }

    @Test
    fun `different gtins with the same title give different keys`() {
        val a = assertIs<ProductKeyResult.Resolved>(resolve("Arroz Branco 5kg", gtin = "7896006711155")).key
        val b = assertIs<ProductKeyResult.Resolved>(resolve("Arroz Branco 5kg", gtin = "7896006744115")).key
        assertNotEquals(a, b)
    }

    @Test
    fun `a gtin key never equals the attribute key of the same title`() {
        val byGtin = assertIs<ProductKeyResult.Resolved>(resolve("Arroz Branco 5kg", gtin = "7896006711155")).key
        val byAttributes = assertIs<ProductKeyResult.Resolved>(resolve("Arroz Branco 5kg")).key
        assertNotEquals(byGtin, byAttributes)
    }

    @Test
    fun `with a valid gtin the signature is computed best effort outside the key`() {
        val result = assertIs<ProductKeyResult.Resolved>(resolve("Arroz Branco 5kg", gtin = "7896006711155"))
        assertEquals(assertIs<ProductKeyResult.Resolved>(resolve("Arroz Branco 5kg")).attributeSignature, result.attributeSignature)
    }

    @Test
    fun `no failure of the auxiliary signature removes a valid gtin key`() {
        val expected = ProductKeyResult.Resolved.ByGtin(gtin("7896006711155"), auxiliaryAttributeSignature = null)
        listOf(
            resolve("PAO FRANCES KG", gtin = "7896006711155"),               // measure Absent
            resolve("Cafe 1.000 g", gtin = "7896006711155"),                 // measure Ambiguous
            resolve("500g", gtin = "7896006711155"),                         // empty canonical name
            resolve("", gtin = "7896006711155"),                             // blank title — SR-2
            resolve("Cafe 500g", gtin = "7896006711155", brand = ""),        // blank brand
            resolve("Cafe 500g", gtin = "7896006711155", variant = " ")      // blank variant
        ).forEach {
            assertEquals(expected, it)
        }
    }

    @Test
    fun `a blank structured value is not rescued as absent on the gtin path`() {
        // SR-1 + design §9.3: with brand = null the auxiliary signature exists; with brand = "" it does not,
        // and the GTIN key is untouched in both cases.
        val withAbsentBrand = assertIs<ProductKeyResult.Resolved>(resolve("Cafe 500g", gtin = "7896006711155"))
        val withBlankBrand = assertIs<ProductKeyResult.Resolved>(resolve("Cafe 500g", gtin = "7896006711155", brand = ""))
        assertNotNull(withAbsentBrand.attributeSignature)
        assertNull(withBlankBrand.attributeSignature)
        assertEquals(withAbsentBrand.key, withBlankBrand.key)
    }

    @Test
    fun `the gtin is evaluated before any signature step`() {
        // Design §9.3, SR-8: every signature input is also defective, yet the GTIN decides alone.
        assertEquals(
            ProductKeyResult.Rejected(InputRejection.InvalidGtin(GtinRejection.INVALID_CHECK_DIGIT)),
            resolve("", gtin = "7896006711156", brand = "", variant = "")
        )
        assertEquals(
            ProductKeyResult.Resolved.ByGtin(gtin("7896006711155"), auxiliaryAttributeSignature = null),
            resolve("", gtin = "7896006711155", brand = "", variant = "")
        )
    }

    @Test
    fun `a valid gtin is never unresolved or rejected`() {
        // Design §9.3 / AC9: any title, brand, variant — including every case of this file.
        val titles = listOf("", "500g", "PAO FRANCES KG", "Cafe 1.000 g", "6un Dove 90g", "Arroz Branco 5kg")
        for (title in titles) for (brand in listOf(null, "", "Camil")) for (variant in listOf(null, " ", "Zero")) {
            val result = assertIs<ProductKeyResult.Resolved.ByGtin>(resolve(title, "7896006711155", brand, variant))
            assertEquals(ProductKey.fromGtin(gtin("7896006711155")), result.key)
            assertEquals(ProductIdentityLevel.EXACT_IDENTITY, result.identityLevel)
        }
    }

    @Test
    fun `an invalid gtin never falls back to the signature`() {
        // G-3: the title alone would resolve.
        assertIs<ProductKeyResult.Resolved>(resolve("Arroz Branco 5kg"))
        mapOf(
            "7896006711156" to GtinRejection.INVALID_CHECK_DIGIT,
            "0000000000000" to GtinRejection.ALL_ZEROS,
            "789600671115x" to GtinRejection.NON_DIGIT,
            "1234567" to GtinRejection.UNSUPPORTED_LENGTH,
            "" to GtinRejection.UNSUPPORTED_LENGTH                           // SR-3: empty is present
        ).forEach { (raw, rejection) ->
            assertEquals(ProductKeyResult.Rejected(InputRejection.InvalidGtin(rejection)), resolve("Arroz Branco 5kg", gtin = raw))
        }
    }

    @Test
    fun `the auxiliary signature carries the structured brand and the title name`() {
        // Design §9.3 «everything well-formed» row; the brand in the title stays in the name (PO-4).
        val result = assertIs<ProductKeyResult.Resolved.ByGtin>(resolve("Café Pilão 500G", gtin = "7896006711155", brand = "Pilão"))
        assertEquals(ProductKey.fromGtin(gtin("7896006711155")), result.key)
        assertEquals(
            AttributeSignature(MarketTextKey.from("pilão"), MarketTextKey.from("café pilão"), null, NormalizedPackageMeasure(500, MeasureUnit.MASS, 1)),
            result.attributeSignature
        )
    }

    @Test
    fun `an invalid gtin dominates every other defect`() {
        // Design §9.2 row «GTIN present and invalid, whatever the other inputs»; SR-3: never trimmed.
        assertEquals(
            ProductKeyResult.Rejected(InputRejection.InvalidGtin(GtinRejection.INVALID_CHECK_DIGIT)),
            resolve("Cafe 1.000 g", gtin = "7896006711156")
        )
        assertEquals(
            ProductKeyResult.Rejected(InputRejection.InvalidGtin(GtinRejection.UNSUPPORTED_LENGTH)),
            resolve("Cafe 500g", gtin = "", brand = " ", variant = "")
        )
        assertEquals(
            ProductKeyResult.Rejected(InputRejection.InvalidGtin(GtinRejection.NON_DIGIT)),
            resolve("Arroz Branco 5kg", gtin = " 7896006711155")
        )
    }

    // Task 9 — design §12. None of these pairs may share a key.

    private fun keyOf(title: String, gtin: String? = null) = assertIs<ProductKeyResult.Resolved>(resolve(title, gtin)).key

    @Test
    fun `protected pairs never collide`() {
        listOf(
            "Cafe Pilão 500g" to "Cafe Pilão 1kg",                            // quantity
            "Dove 90g" to "Dove 90g 6un",                                     // packCount
            "Coca-Cola 2L" to "Coca-Cola Zero 2L",                           // text only (design §12)
            "Arroz Tio João 5kg" to "Arroz Camil 5kg",                       // text only (design §12)
            "Ovos 12 unidades" to "Ovos 30 unidades",                        // quantity, COUNT
            "Sabonete 6 x 90g" to "Sabonete 540g",                           // never multiplied
            "Leite 1L" to "Leite 1kg"                                        // MASS vs VOLUME
        ).forEach { (left, right) -> assertNotEquals(keyOf(left), keyOf(right), "$left | $right") }
        assertNotEquals(keyOf("Arroz 5kg", gtin = "7896006711155"), keyOf("Arroz 5kg", gtin = "7896006744115"))
    }

    @Test
    fun `expected merges happen`() {
        listOf(
            "Arroz Branco 5kg" to "Arroz Branco 5 kg",
            "Leite Integral Piracanjuba 1 Litro" to "Leite Integral Piracanjuba 1L",
            "Cafe Pilao 500g Tradicional" to "Cafe Pilao Tradicional 500g",
            "\u00a0Cafe 500G\u00a0" to "Cafe 500g",
            // Task 9 additions: design §8, §10.
            "Cafe Pilao 0,5 kg" to "Cafe Pilao 500g",
            "Dove 6 x 90 g" to "Dove 90g 6un",
            "5kg Arroz Camil" to "Arroz 5 kg Camil"
        ).forEach { (left, right) -> assertEquals(keyOf(left), keyOf(right), "$left | $right") }
        assertEquals(keyOf("Arroz 5kg", gtin = "036000291452"), keyOf("Feijao 1kg", gtin = "00036000291452"))
    }

    @Test
    fun `known false splits stay split`() {
        // Design §13: accepted false splits, recorded and not repaired in S2.
        listOf(
            "Cafe Pilao 500g" to "Café Pilão 500g",                          // accents preserved
            "Cafe Pilao 500g" to "Pilao Cafe 500g",                          // word order preserved
            "Refrigerante Coca-Cola Garrafa 2 L" to "Coca-Cola 2L",          // descriptive words kept
            "Arroz - 5kg" to "Arroz 5kg",                                    // punctuation kept
            "Papel Higienico com 4 Rolos" to "Papel Higienico 4 Rolos",      // com kept
            "Arroz\u00a0Camil 5kg" to "Arroz Camil 5kg"                     // internal NBSP, S9 sanitizes
        ).forEach { (left, right) -> assertNotEquals(keyOf(left), keyOf(right), "$left | $right") }
        assertNotEquals(keyOf("Arroz Branco 5kg", gtin = "7896006711155"), keyOf("Arroz Branco 5kg")) // C4
    }

    @Test
    fun `the real corpus resolves to the recorded signatures`() {
        // Design §16.1, POC B data/carrefour-canonical.json: 15 titles, no GTIN, no structured brand or variant.
        val corpus = listOf(
            Triple("Arroz Branco Camil Tipo 1 5kg", "arroz branco camil tipo 1", NormalizedPackageMeasure(5000, MeasureUnit.MASS, 1)),
            Triple("Feijao Carioca Tipo 1 Kicaldo 1Kg", "feijao carioca tipo 1 kicaldo", NormalizedPackageMeasure(1000, MeasureUnit.MASS, 1)),
            Triple("Leite Integral Piracanjuba 1 Litro", "leite integral piracanjuba", NormalizedPackageMeasure(1000, MeasureUnit.VOLUME, 1)),
            Triple("Cafe Torrado e Moido Pilao 252 Graus Vacuo 500g", "cafe torrado e moido pilao 252 graus vacuo", NormalizedPackageMeasure(500, MeasureUnit.MASS, 1)),
            Triple("Acucar Refinado Uniao 1kg", "acucar refinado uniao", NormalizedPackageMeasure(1000, MeasureUnit.MASS, 1)),
            Triple("Oleo de Soja Soya 900ml", "oleo de soja soya", NormalizedPackageMeasure(900, MeasureUnit.VOLUME, 1)),
            Triple("Macarrao Espaguete Carrefour Classic 500g", "macarrao espaguete carrefour classic", NormalizedPackageMeasure(500, MeasureUnit.MASS, 1)),
            Triple("Farinha de Trigo Integral Dona Benta Integral Premium 1 Kg", "farinha de trigo integral dona benta integral premium", NormalizedPackageMeasure(1000, MeasureUnit.MASS, 1)),
            Triple("Molho de Tomate Tradicional Tarantella Sache 300 g", "molho de tomate tradicional tarantella sache", NormalizedPackageMeasure(300, MeasureUnit.MASS, 1)),
            Triple("Leite Condensado Integral Carrefour Classic 395g", "leite condensado integral carrefour classic", NormalizedPackageMeasure(395, MeasureUnit.MASS, 1)),
            Triple("Detergente Liquido Carrefour Neutro 500ml", "detergente liquido carrefour neutro", NormalizedPackageMeasure(500, MeasureUnit.VOLUME, 1)),
            Triple("Sabonete em Barra Dove Karite e Baunilha 90g", "sabonete em barra dove karite e baunilha", NormalizedPackageMeasure(90, MeasureUnit.MASS, 1)),
            Triple("Papel Higienico Mimmo Folha Dupla com 4 Rolos", "papel higienico mimmo folha dupla com", NormalizedPackageMeasure(4, MeasureUnit.COUNT, 1)),
            Triple("Refrigerante Coca-Cola Garrafa 2 L", "refrigerante coca-cola garrafa", NormalizedPackageMeasure(2000, MeasureUnit.VOLUME, 1)),
            Triple("Agua Sanitaria Ype 1L", "agua sanitaria ype", NormalizedPackageMeasure(1000, MeasureUnit.VOLUME, 1))
        )
        assertEquals(15, corpus.size)
        corpus.forEach { (title, name, measure) ->
            val s = AttributeSignature(null, MarketTextKey.from(name), null, measure)
            assertEquals(ProductKeyResult.Resolved.ByAttributes(s), resolve(title), title)
            assertEquals(ProductKey.fromAttributes(s), keyOf(title), title)
        }
    }

    @Test
    fun `resolution never throws`() {
        val titles = listOf("", " ", "\u00a0", "\u200b", "500g", "x".repeat(10_000), "9".repeat(5_000) + " g", "Dove 90g 3000000000un")
        val gtins = listOf(null, "", "0", "7896006711155", "\u0661".repeat(13))
        for (title in titles) for (gtin in gtins) for (brand in listOf(null, "", "Dove")) resolve(title, gtin, brand)
    }

    @Test
    fun `every boundary combination follows the frozen order and is deterministic`() {
        // Design §9.1, §9.2, §9.3, SR-8: gtin × title × brand × variant boundaries; the expected result is
        // derived from the frozen step order, and every input resolves twice to an equal result.
        val titles = mapOf(
            "" to null,
            "Cafe Pilao 500g" to "resolved",
            "500g" to UnresolvedReason.EMPTY_CANONICAL_NAME,
            "PAO FRANCES KG" to UnresolvedReason.PACKAGE_MEASURE_ABSENT,
            "Cafe 1.000 g" to UnresolvedReason.PACKAGE_MEASURE_AMBIGUOUS
        )
        var cases = 0
        for (gtin in listOf(null, "7896006711155", "7896006711156")) for ((title, outcome) in titles)
            for (brand in listOf(null, "Camil", " ")) for (variant in listOf(null, "Zero", "")) {
                val result = resolve(title, gtin, brand, variant)
                assertEquals(result, resolve(title, gtin, brand, variant))
                assertEquals(result.hashCode(), resolve(title, gtin, brand, variant).hashCode())
                val expected: Any = when {
                    gtin == "7896006711155" -> ProductKeyResult.Resolved.ByGtin::class
                    gtin != null -> ProductKeyResult.Rejected(InputRejection.InvalidGtin(GtinRejection.INVALID_CHECK_DIGIT))
                    title.isBlank() -> ProductKeyResult.Rejected(InputRejection.BlankTitle)
                    brand != null && brand.isBlank() -> ProductKeyResult.Rejected(InputRejection.BlankBrand)
                    variant != null && variant.isBlank() -> ProductKeyResult.Rejected(InputRejection.BlankVariant)
                    outcome is UnresolvedReason -> ProductKeyResult.Unresolved(outcome)
                    else -> ProductKeyResult.Resolved.ByAttributes::class
                }
                if (expected is kotlin.reflect.KClass<*>) assertTrue(expected.isInstance(result), "$gtin | $title | $brand | $variant → $result")
                else assertEquals(expected, result, "$gtin | $title | $brand | $variant")
                cases++
            }
        assertEquals(135, cases)
    }
}
