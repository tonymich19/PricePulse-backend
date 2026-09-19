package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AttributeSignatureTest {

    private fun key(text: String) = MarketTextKey.from(text)
    private val bar = NormalizedPackageMeasure(90, MeasureUnit.MASS, 1)

    // Task 3 — design §6, §10.

    @Test
    fun `equal components give equal signatures and hash codes`() {
        val a = AttributeSignature(key("Dove"), key("sabonete"), null, bar)
        val b = AttributeSignature(key("DOVE"), key("Sabonete"), null, NormalizedPackageMeasure(90, MeasureUnit.MASS, 1))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `absent brand never equals a present brand`() {
        assertNotEquals(AttributeSignature(null, key("sabonete"), null, bar), AttributeSignature(key("Dove"), key("sabonete"), null, bar))
    }

    @Test
    fun `absent variant never equals a present variant`() {
        assertNotEquals(AttributeSignature(null, key("coca-cola"), null, bar), AttributeSignature(null, key("coca-cola"), key("zero"), bar))
    }

    @Test
    fun `every measure field takes part in equality, pack count 1 included`() {
        val base = AttributeSignature(null, key("dove"), null, bar)
        assertNotEquals(base, base.copy(packageMeasure = NormalizedPackageMeasure(90, MeasureUnit.MASS, 6)))
        assertNotEquals(base, base.copy(packageMeasure = NormalizedPackageMeasure(90, MeasureUnit.VOLUME, 1)))
        assertNotEquals(base, base.copy(packageMeasure = NormalizedPackageMeasure(91, MeasureUnit.MASS, 1)))
    }

    private fun signatureOf(title: String, brand: String? = null, variant: String? = null): AttributeSignature {
        val result = assertIs<ProductKeyResult.Resolved>(resolveProductKey(title, gtin = null, brand = brand, variant = variant))
        return assertIs<ProductKey.AttributeKey>(result.key).signature
    }
    private fun nameOf(title: String) = signatureOf(title).canonicalName.value
    private fun measureOf(title: String) = signatureOf(title).packageMeasure

    // Task 5 — design §7, §8, PO-1, PO-2. Values observed with the real S1a/S1b during discovery.

    @Test
    fun `a measure at the end, start or middle leaves the same name`() {
        assertEquals("arroz camil", nameOf("Arroz Camil 5kg"))
        assertEquals("arroz camil", nameOf("5kg Arroz Camil"))
        assertEquals("arroz camil", nameOf("Arroz 5 kg Camil"))
        assertEquals(NormalizedPackageMeasure(5000, MeasureUnit.MASS, 1), measureOf("5kg Arroz Camil"))
    }

    @Test
    fun `two spans are both removed`() {
        assertEquals("dove", nameOf("Dove 90g 6un"))
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), measureOf("Dove 90g 6un"))
        assertEquals("cerveja", nameOf("Cerveja 350 ml 12 un")) // hypothetical
    }

    @Test
    fun `a multipack expression is one span`() {
        assertEquals("dove", nameOf("Dove 6 x 90 g"))
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), measureOf("Dove 6 x 90 g"))
    }

    @Test
    fun `6x90g and 90g 6un give the same signature`() {
        assertEquals(signatureOf("Dove 6x90g"), signatureOf("Dove 90g 6un"))
    }

    @Test
    fun `decimal measures are canonical`() {
        assertEquals(signatureOf("Cafe Pilao 500g"), signatureOf("Cafe Pilao 0,5 kg"))
        assertEquals(NormalizedPackageMeasure(1500, MeasureUnit.VOLUME, 1), measureOf("Leite 1,5 L"))
    }

    @Test
    fun `punctuation around a removed span is kept`() {
        assertEquals("vagem emb /", nameOf("VAGEM EMB / 250G")) // receipt line C001, adversarial
        assertEquals("arroz -", nameOf("Arroz - 5kg"))
    }

    @Test
    fun `words such as com are kept`() {
        // POC B carrefour-canonical.json.
        assertEquals("papel higienico mimmo folha dupla com", nameOf("Papel Higienico Mimmo Folha Dupla com 4 Rolos"))
    }

    @Test
    fun `numbers that are not measures stay in the name`() {
        assertEquals("cafe torrado e moido pilao 252 graus vacuo", nameOf("Cafe Torrado e Moido Pilao 252 Graus Vacuo 500g"))
    }

    @Test
    fun `count is an ordinary measure unit`() {
        assertEquals(NormalizedPackageMeasure(12, MeasureUnit.COUNT, 1), measureOf("Ovos 12 unidades"))
        assertEquals(signatureOf("Papel Higienico 4 Rolos"), signatureOf("Papel Higienico 4 un"))
    }

    @Test
    fun `span table rows of the design resolve as observed`() {
        // Design §8 table; values observed with the real S1a/S1b during discovery.
        assertEquals("arroz branco", nameOf("Arroz Branco 5KG"))
        assertEquals(NormalizedPackageMeasure(5000, MeasureUnit.MASS, 1), measureOf("Arroz Branco 5KG"))
        assertEquals("leite integral", nameOf("Leite Integral 1 L"))
        assertEquals(NormalizedPackageMeasure(1000, MeasureUnit.VOLUME, 1), measureOf("Leite Integral 1 L"))
        assertEquals("café pilão", nameOf("Café Pilão 500G"))
        assertEquals(NormalizedPackageMeasure(500, MeasureUnit.MASS, 1), measureOf("Café Pilão 500G"))
    }

    @Test
    fun `a title made only of a measure leaves an empty name and no signature key`() {
        // Design §7, §9.0: well-formed but insufficient input is Unresolved, not Rejected (PO-2).
        assertEquals(
            ProductKeyResult.Unresolved(UnresolvedReason.EMPTY_CANONICAL_NAME),
            resolveProductKey("500g", gtin = null, brand = null, variant = null)
        )
    }

    // Task 6 — design §7, §13, G-2.

    @Test
    fun `border nbsp, narrow nbsp and figure space do not hide the measure`() {
        // ADR-016 open item 5.
        val clean = signatureOf("Cafe 500G")
        listOf("\u00a0Cafe 500G\u00a0", "Cafe 500G\u00a0", "\u202fCafe 500G\u202f", "\u2007Cafe 500G\u2007", "\u2003Cafe 500G\u2003")
            .forEach { assertEquals(clean, signatureOf(it)) }
    }

    @Test
    fun `internal nbsp stays distinct`() {
        // Not collapsed by MarketTextKey; S9 sanitizes (C5).
        assertNotEquals(signatureOf("Arroz Camil 5kg"), signatureOf("Arroz\u00a0Camil 5kg"))
    }

    @Test
    fun `zwsp is kept in the name`() {
        assertEquals("\u200bcafe", nameOf("\u200bCafe 500G"))
    }

    @Test
    fun `case and whitespace runs do not matter`() {
        assertEquals(signatureOf("cafe  pilao 500g"), signatureOf("CAFE PILAO\t500G"))
    }

    @Test
    fun `accents are preserved`() {
        assertEquals("café pilão", nameOf("Café Pilão 500G"))
        assertNotEquals(signatureOf("Cafe Pilao 500g"), signatureOf("Café Pilão 500g"))
    }

    @Test
    fun `a brand written in the title stays in the name and is not structured`() {
        // PO-4, G-9.
        val signature = signatureOf("Arroz Branco Camil Tipo 1 5kg")
        assertEquals("arroz branco camil tipo 1", signature.canonicalName.value)
        assertNull(signature.brand)
    }
}
