package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UnitNormalizationTest {

    // Task 1 — types and construction invariants (design §6, §7).

    @Test
    fun `rejects non-positive package content`() {
        assertFailsWith<IllegalArgumentException> { NormalizedPackageMeasure(0, MeasureUnit.MASS, 1) }
        assertFailsWith<IllegalArgumentException> { NormalizedPackageMeasure(-1, MeasureUnit.MASS, 1) }
    }

    @Test
    fun `rejects a pack count below one`() {
        assertFailsWith<IllegalArgumentException> { NormalizedPackageMeasure(90, MeasureUnit.MASS, 0) }
        assertFailsWith<IllegalArgumentException> { NormalizedPackageMeasure(90, MeasureUnit.MASS, -1) }
    }

    @Test
    fun `copy cannot bypass the invariants`() {
        val valid = NormalizedPackageMeasure(90, MeasureUnit.MASS, 1)
        assertFailsWith<IllegalArgumentException> { valid.copy(quantity = 0) }
        assertFailsWith<IllegalArgumentException> { valid.copy(packCount = 0) }
    }

    @Test
    fun `accepts the smallest valid measure`() {
        val smallest = NormalizedPackageMeasure(1, MeasureUnit.COUNT, 1)
        assertEquals(1, smallest.quantity)
        assertEquals(1, smallest.packCount)
    }

    @Test
    fun `holds mass in grams, volume in millilitres and count in units`() {
        val rice = NormalizedPackageMeasure(5000, MeasureUnit.MASS, 1)
        val juice = NormalizedPackageMeasure(1500, MeasureUnit.VOLUME, 1)
        val paper = NormalizedPackageMeasure(4, MeasureUnit.COUNT, 1)

        assertEquals(5000L, rice.quantity)
        assertEquals(MeasureUnit.MASS, rice.measureUnit)
        assertEquals(1500L, juice.quantity)
        assertEquals(MeasureUnit.VOLUME, juice.measureUnit)
        assertEquals(4L, paper.quantity)
        assertEquals(MeasureUnit.COUNT, paper.measureUnit)
    }

    @Test
    fun `equal content and pack count make equal measures`() {
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), NormalizedPackageMeasure(90, MeasureUnit.MASS, 6))
        assertEquals(
            NormalizedPackageMeasure(90, MeasureUnit.MASS, 6).hashCode(),
            NormalizedPackageMeasure(90, MeasureUnit.MASS, 6).hashCode()
        )
    }

    @Test
    fun `different measure units never make equal measures`() {
        assertNotEquals(NormalizedPackageMeasure(500, MeasureUnit.MASS, 1), NormalizedPackageMeasure(500, MeasureUnit.VOLUME, 1))
    }

    @Test
    fun `multipack never equals the single unit`() {
        // Dove 90g vs Dove 90g 6un (design §5.1).
        assertNotEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 1), NormalizedPackageMeasure(90, MeasureUnit.MASS, 6))
    }

    @Test
    fun `90 g times 6 is never 540 g`() {
        // quantity is the content of ONE unit; packCount is never multiplied into it (D4).
        assertNotEquals(NormalizedPackageMeasure(540, MeasureUnit.MASS, 1), NormalizedPackageMeasure(90, MeasureUnit.MASS, 6))
    }

    @Test
    fun `normalized result carries the measure and its spans`() {
        val measure = NormalizedPackageMeasure(90, MeasureUnit.MASS, 6)
        val spans = listOf(14..16, 18..20)
        val result = PackageMeasureResult.Normalized(measure, spans)

        assertEquals(measure, result.measure)
        assertEquals(spans, result.spans)
    }

    @Test
    fun `absent is a single state`() {
        assertSame(PackageMeasureResult.Absent, PackageMeasureResult.Absent)
    }

    @Test
    fun `ambiguous result carries its reason`() {
        val result = PackageMeasureResult.Ambiguous(PackageMeasureAmbiguity.MULTIPLE_SIZES)
        assertEquals(PackageMeasureAmbiguity.MULTIPLE_SIZES, result.reason)
    }

    @Test
    fun `a result is exactly one of normalized, absent or ambiguous`() {
        // Exhaustive `when` without `else`: a fourth state would stop this from compiling.
        fun state(result: PackageMeasureResult): String = when (result) {
            is PackageMeasureResult.Normalized -> "normalized"
            PackageMeasureResult.Absent -> "absent"
            is PackageMeasureResult.Ambiguous -> "ambiguous"
        }

        assertEquals("normalized", state(PackageMeasureResult.Normalized(NormalizedPackageMeasure(1, MeasureUnit.COUNT, 1), emptyList())))
        assertEquals("absent", state(PackageMeasureResult.Absent))
        assertEquals("ambiguous", state(PackageMeasureResult.Ambiguous(PackageMeasureAmbiguity.ZERO_QUANTITY)))
    }

    @Test
    fun `ambiguity reasons are declared in their reporting order`() {
        // Design §8: when several reasons apply, the first in declaration order is reported.
        assertEquals(
            listOf(
                PackageMeasureAmbiguity.AMBIGUOUS_DECIMAL_SEPARATOR,
                PackageMeasureAmbiguity.OUT_OF_RANGE,
                PackageMeasureAmbiguity.ZERO_QUANTITY,
                PackageMeasureAmbiguity.NON_EXACT_QUANTITY,
                PackageMeasureAmbiguity.MULTIPLE_SIZES,
                PackageMeasureAmbiguity.UNPARSED_PACK_SIGNAL
            ),
            PackageMeasureAmbiguity.entries
        )
    }

    // Task 2 — one size and exact conversion (design §6.2 rules 2–3, §6.4, §6.5).

    private fun measureOf(raw: String): NormalizedPackageMeasure =
        assertIs<PackageMeasureResult.Normalized>(normalizePackageMeasure(raw), "input '$raw'").measure

    @Test
    fun `normalizes mass to grams`() {
        assertEquals(NormalizedPackageMeasure(500, MeasureUnit.MASS, 1), measureOf("Cafe 500 g"))
        assertEquals(NormalizedPackageMeasure(500, MeasureUnit.MASS, 1), measureOf("Cafe 500g"))
        assertEquals(NormalizedPackageMeasure(500, MeasureUnit.MASS, 1), measureOf("Cafe 0,5 kg"))
        assertEquals(NormalizedPackageMeasure(500, MeasureUnit.MASS, 1), measureOf("Cafe 0.5 kg"))
        assertEquals(NormalizedPackageMeasure(1000, MeasureUnit.MASS, 1), measureOf("Arroz 1 kg"))
    }

    @Test
    fun `normalizes volume to millilitres`() {
        assertEquals(NormalizedPackageMeasure(1500, MeasureUnit.VOLUME, 1), measureOf("Suco 1,5 L"))
        assertEquals(NormalizedPackageMeasure(1500, MeasureUnit.VOLUME, 1), measureOf("Suco 1.5 L"))
        assertEquals(NormalizedPackageMeasure(330, MeasureUnit.VOLUME, 1), measureOf("Refrigerante 330 ml"))
        assertEquals(NormalizedPackageMeasure(330, MeasureUnit.VOLUME, 1), measureOf("Refrigerante 0,33 L"))
        assertEquals(NormalizedPackageMeasure(1000, MeasureUnit.VOLUME, 1), measureOf("Suco 1 L"))
    }

    @Test
    fun `every written form of 500 g is the same measure`() {
        val forms = listOf("Cafe 500 g", "Cafe 500g", "Cafe 0,5 kg", "Cafe 0.5 kg")
        assertEquals(1, forms.map(::measureOf).toSet().size)
    }

    @Test
    fun `reads the package size of the contract fixture, not its sale unit`() {
        // contracts/fixtures/valid/complete-with-items.json: description "ARROZ BRANCO 5KG", unit "UN".
        assertEquals(NormalizedPackageMeasure(5000, MeasureUnit.MASS, 1), measureOf("ARROZ BRANCO 5KG"))
    }

    @Test
    fun `normalizes real Carrefour names`() {
        // ../PricePulse/test-data/price-intelligence/geographic-price-variation/data/carrefour-canonical.json.
        // The count-only name «... com 4 Rolos» belongs to Task 5 (design §6.2 rule 5).
        val cases = mapOf(
            "Arroz Branco Camil Tipo 1 5kg" to NormalizedPackageMeasure(5000, MeasureUnit.MASS, 1),
            "Feijao Carioca Tipo 1 Kicaldo 1Kg" to NormalizedPackageMeasure(1000, MeasureUnit.MASS, 1),
            "Leite Integral Piracanjuba 1 Litro" to NormalizedPackageMeasure(1000, MeasureUnit.VOLUME, 1),
            "Cafe Torrado e Moido Pilao 252 Graus Vacuo 500g" to NormalizedPackageMeasure(500, MeasureUnit.MASS, 1),
            "Oleo de Soja Soya 900ml" to NormalizedPackageMeasure(900, MeasureUnit.VOLUME, 1),
            "Molho de Tomate Tradicional Tarantella Sache 300 g" to NormalizedPackageMeasure(300, MeasureUnit.MASS, 1),
            "Farinha de Trigo Integral Dona Benta Integral Premium 1 Kg" to NormalizedPackageMeasure(1000, MeasureUnit.MASS, 1),
            "Sabonete em Barra Dove Karite e Baunilha 90g" to NormalizedPackageMeasure(90, MeasureUnit.MASS, 1),
            "Refrigerante Coca-Cola Garrafa 2 L" to NormalizedPackageMeasure(2000, MeasureUnit.VOLUME, 1),
            "Agua Sanitaria Ype 1L" to NormalizedPackageMeasure(1000, MeasureUnit.VOLUME, 1)
        )
        cases.forEach { (name, expected) -> assertEquals(expected, measureOf(name), name) }
    }

    @Test
    fun `every approved mass and volume alias converts exactly, spaced and glued`() {
        // Design §6.4. COUNT aliases belong to count expressions (Task 5).
        val grams = listOf("g", "gr", "grs", "grama", "gramas")
        val kilograms = listOf("kg", "kgs", "kilo", "kilos", "quilo", "quilos", "quilograma", "quilogramas")
        val millilitres = listOf("ml")
        val litres = listOf("l", "lt", "lts", "litro", "litros")

        fun check(number: String, aliases: List<String>, expected: NormalizedPackageMeasure) =
            aliases.forEach { alias ->
                assertEquals(expected, measureOf("Produto $number $alias"), "spaced '$alias'")
                assertEquals(expected, measureOf("Produto $number$alias"), "glued '$alias'")
            }

        check("500", grams, NormalizedPackageMeasure(500, MeasureUnit.MASS, 1))
        check("5", kilograms, NormalizedPackageMeasure(5000, MeasureUnit.MASS, 1))
        check("500", millilitres, NormalizedPackageMeasure(500, MeasureUnit.VOLUME, 1))
        check("1", litres, NormalizedPackageMeasure(1000, MeasureUnit.VOLUME, 1))
    }

    @Test
    fun `1,5 g is refused, never rounded`() {
        // Design §6.5: a non-integer canonical value would need mg, which this slice does not support.
        assertEquals(
            PackageMeasureResult.Ambiguous(PackageMeasureAmbiguity.NON_EXACT_QUANTITY),
            normalizePackageMeasure("Tempero 1,5 g")
        )
    }

    @Test
    fun `the span of one measure is exactly the recognized text of the raw input`() {
        val spaced = "Cafe 0,5 kg"
        val spacedResult = assertIs<PackageMeasureResult.Normalized>(normalizePackageMeasure(spaced))
        assertEquals(listOf(5..10), spacedResult.spans)
        assertEquals(listOf("0,5 kg"), spacedResult.spans.map { spaced.substring(it) })

        val spelledOut = "Leite Integral Piracanjuba 1 Litro"
        val spelledOutResult = assertIs<PackageMeasureResult.Normalized>(normalizePackageMeasure(spelledOut))
        assertEquals(listOf("1 Litro"), spelledOutResult.spans.map { spelledOut.substring(it) })
    }

    // Task 3 — decimal grammar N1–N3 (design §6.3, D8). All inputs are hypothetical.

    private val ambiguousSeparator = PackageMeasureResult.Ambiguous(PackageMeasureAmbiguity.AMBIGUOUS_DECIMAL_SEPARATOR)

    @Test
    fun `1 dot 000 g is neither 1 g nor 1000 g`() {
        // JVM method names cannot contain '.', so the name spells out the mandatory input "1.000 g".
        assertEquals(ambiguousSeparator, normalizePackageMeasure("Arroz 1.000 g"))
        assertEquals(ambiguousSeparator, normalizePackageMeasure("Arroz 1.000g"))
    }

    @Test
    fun `a number that is also a thousands grouping is ambiguous, whatever the separator (N2)`() {
        listOf("Arroz 1,000 g", "Agua 2.500 ml", "Queijo 1,250 kg").forEach { input ->
            assertEquals(ambiguousSeparator, normalizePackageMeasure(input), input)
        }
    }

    @Test
    fun `a number with more than one separator is ambiguous (N1)`() {
        listOf("Arroz 1.000,5 kg", "Arroz 1,000.5 kg", "Arroz 1.000.000 g").forEach { input ->
            assertEquals(ambiguousSeparator, normalizePackageMeasure(input), input)
        }
    }

    @Test
    fun `a zero integer part is never a thousands group (N3)`() {
        assertEquals(NormalizedPackageMeasure(250, MeasureUnit.MASS, 1), measureOf("Queijo 0,250 kg"))
        assertEquals(NormalizedPackageMeasure(330, MeasureUnit.VOLUME, 1), measureOf("Suco 0.330 L"))
    }

    @Test
    fun `unambiguous decimals and plain integers keep converting (N3)`() {
        val cases = mapOf(
            "Racao 12.5 kg" to NormalizedPackageMeasure(12500, MeasureUnit.MASS, 1),
            "Arroz 1000 g" to NormalizedPackageMeasure(1000, MeasureUnit.MASS, 1),
            "Cafe 0.5 kg" to NormalizedPackageMeasure(500, MeasureUnit.MASS, 1),
            "Cafe 0,5 kg" to NormalizedPackageMeasure(500, MeasureUnit.MASS, 1),
            "Suco 1.5 L" to NormalizedPackageMeasure(1500, MeasureUnit.VOLUME, 1),
            "Suco 1,5 L" to NormalizedPackageMeasure(1500, MeasureUnit.VOLUME, 1),
            "Suco 0.33 L" to NormalizedPackageMeasure(330, MeasureUnit.VOLUME, 1),
            "Suco 0,33 L" to NormalizedPackageMeasure(330, MeasureUnit.VOLUME, 1)
        )
        cases.forEach { (input, expected) -> assertEquals(expected, measureOf(input), input) }
    }

    // Task 4 — absence and traps, fail closed (design §5.2, §6.2 rule 1, §8).

    private fun assertAbsent(vararg inputs: String) =
        inputs.forEach { input -> assertEquals(PackageMeasureResult.Absent, normalizePackageMeasure(input), "input '$input'") }

    @Test
    fun `a sale unit or measure word without a number invents no package size`() {
        // PAO FRANCES KG: contract fixture and R002. LEITE LV ELEGE LT: C001.
        assertAbsent("PAO FRANCES KG", "LEITE LV ELEGE LT")
    }

    @Test
    fun `ESPONJA BRITE 3M is absent because M is not a measure`() {
        // R005. No `m`, `mg` or `cl` in the closed alias table (D6).
        assertAbsent("ESPONJA BRITE 3M")
    }

    @Test
    fun `a number that is not followed by a measure is not a size`() {
        // ACHOC LIQ T.T.TP 200: C001.
        assertAbsent("Arroz Tipo 1", "Cafe 252 Graus", "ACHOC LIQ T.T.TP 200")
    }

    @Test
    fun `a measure word that is not a whole token is not a measure`() {
        // C001.
        assertAbsent("MUSCULO BOV RESF P.KG")
    }

    @Test
    fun `a name without measure language is absent`() {
        // R005.
        assertAbsent("HIDRAT JOHNSONS SOFT")
    }

    @Test
    fun `an internal NBSP is not a separator`() {
        // Hypothetical. Design §6.2 rule 1: decoding belongs to the source adapter (S9).
        assertAbsent("Arroz 500 g")
    }

    @Test
    fun `blank input is absent`() {
        assertAbsent("", "   ")
    }

    @Test
    fun `the traps do not hide the real size`() {
        val cases = mapOf(
            "Arroz Branco Camil Tipo 1 5kg" to NormalizedPackageMeasure(5000, MeasureUnit.MASS, 1),
            "Cafe Torrado e Moido Pilao 252 Graus Vacuo 500g" to NormalizedPackageMeasure(500, MeasureUnit.MASS, 1),
            // C001.
            "VAGEM EMB / 250G" to NormalizedPackageMeasure(250, MeasureUnit.MASS, 1)
        )
        cases.forEach { (input, expected) -> assertEquals(expected, measureOf(input), input) }
    }

    // Task 5 — pack and count-only content (design §6.2 rules 4, 5 and 8, D3, D4).

    @Test
    fun `a count of rolls with no size is the content of one package`() {
        // Rule 5: a count of rolls is the content of one package, never a pack count.
        assertEquals(NormalizedPackageMeasure(4, MeasureUnit.COUNT, 1), measureOf("Papel Higienico com 4 Rolos"))
        // carrefour-canonical.json.
        assertEquals(NormalizedPackageMeasure(4, MeasureUnit.COUNT, 1), measureOf("Papel Higienico Mimmo Folha Dupla com 4 Rolos"))
        // Hypothetical.
        assertEquals(NormalizedPackageMeasure(12, MeasureUnit.COUNT, 1), measureOf("Ovos Brancos 12 unidades"))
    }

    @Test
    fun `a count next to a size is the pack count, in either order`() {
        // Probe Pinheiros (paraphrase); the reversed order is hypothetical.
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 1), measureOf("Sabonete Dove 90g"))
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), measureOf("Sabonete Dove 90g 6un"))
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), measureOf("Sabonete Dove 6un 90g"))
    }

    @Test
    fun `N x SIZE is a pack of N, with or without spaces`() {
        // Hypothetical.
        assertEquals(NormalizedPackageMeasure(350, MeasureUnit.VOLUME, 6), measureOf("Cerveja 6 x 350 ml"))
        assertEquals(NormalizedPackageMeasure(350, MeasureUnit.VOLUME, 6), measureOf("Cerveja 6x350ml"))
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), measureOf("Sabonete 6 x 90g"))
    }

    @Test
    fun `Dove 90g 6un is not Dove 90g`() {
        // Compare the measures, not the results: the results would differ by their spans alone, and the
        // test would pass even if the multipack collapsed into the single unit.
        val single = measureOf("Sabonete Dove 90g")
        val multipack = measureOf("Sabonete Dove 90g 6un")
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 1), single)
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), multipack)
        assertNotEquals(single, multipack)
    }

    @Test
    fun `six times 90 g is not 540 g`() {
        val multipack = measureOf("Sabonete 6 x 90g")
        val single = measureOf("Sabonete 540g")
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), multipack)
        assertEquals(NormalizedPackageMeasure(540, MeasureUnit.MASS, 1), single)
        assertNotEquals(single, multipack)
    }

    @Test
    fun `every approved count alias counts, spaced and glued`() {
        // Design §6.4, COUNT row.
        val aliases = listOf("un", "und", "unid", "unidade", "unidades", "rolo", "rolos")
        aliases.forEach { alias ->
            assertEquals(NormalizedPackageMeasure(4, MeasureUnit.COUNT, 1), measureOf("Produto 4 $alias"), "spaced '$alias'")
            assertEquals(NormalizedPackageMeasure(4, MeasureUnit.COUNT, 1), measureOf("Produto 4$alias"), "glued '$alias'")
        }
    }

    @Test
    fun `only the unit aliases of rule 4 make a pack count next to a size`() {
        // Design §6.2 rule 4: `N un|und|unid|unidade|unidades` next to a size.
        listOf("un", "und", "unid", "unidade", "unidades").forEach { alias ->
            assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 4), measureOf("Produto 90g 4$alias"), "pack '$alias'")
        }
    }

    @Test
    fun `a count of rolls next to a size is unparsed pack language, never a pack count`() {
        // Design §6.2 rules 4 and 6, D4, invariant §7.4: rolls are the content of one package, so rolls
        // next to a mass or volume cannot be read safely. Hypothetical inputs.
        listOf("Produto 90g 4rolo", "Produto 90g 4 rolos", "Papel Toalha 55g 2 rolos").forEach { input ->
            assertEquals(
                PackageMeasureResult.Ambiguous(PackageMeasureAmbiguity.UNPARSED_PACK_SIGNAL),
                normalizePackageMeasure(input),
                "input '$input'"
            )
        }
        // Regression: rolls alone stay the content; a unit count next to a size stays the pack count.
        assertEquals(NormalizedPackageMeasure(4, MeasureUnit.COUNT, 1), measureOf("Papel Higienico com 4 Rolos"))
        assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), measureOf("Sabonete Dove 90g 6un"))
    }

    // Task 6 — every ambiguity reason (design §6.2 rules 4, 6 and 7, §6.5, §8). Each input triggers exactly
    // one reason: the reason is diagnostic, and no test depends on precedence.

    private fun assertAmbiguous(reason: PackageMeasureAmbiguity, vararg inputs: String) =
        inputs.forEach { input ->
            assertEquals(PackageMeasureResult.Ambiguous(reason), normalizePackageMeasure(input), "input '$input'")
        }

    @Test
    fun `two sizes or two counts are MULTIPLE_SIZES, never the first one`() {
        // GPV §G-03 combo; the others are hypothetical.
        assertAmbiguous(
            PackageMeasureAmbiguity.MULTIPLE_SIZES,
            "Refrigerante Coca-Cola 2 L e Fanta Laranja 2 L",
            "Arroz 500g 1kg",
            "Produto 300ml + 200ml",
            "Ovos 12 unidades 6un"
        )
    }

    @Test
    fun `unparsed pack language is never a single unit`() {
        // D3. Leve 3 Pague 2 and Pack 2L are hypothetical (promotion is S5); A UN is receipt R004.
        assertAmbiguous(
            PackageMeasureAmbiguity.UNPARSED_PACK_SIGNAL,
            "Sabonete Leve 3 Pague 2 90g",
            "Refrigerante Pack 2L",
            "V CHEIRO VERDE MATEUS A UN"
        )
    }

    @Test
    fun `a non-integer content or pack count is NON_EXACT_QUANTITY, never rounded`() {
        // Hypothetical. 1,5 g would need mg; a pack count must be an integer.
        assertAmbiguous(
            PackageMeasureAmbiguity.NON_EXACT_QUANTITY,
            "Tempero 1,5 g",
            "Essencia 0,5 ml",
            "Ovos 1,5 un",
            "Sabonete 1,5 x 90g"
        )
    }

    @Test
    fun `zero content or pack count is ZERO_QUANTITY`() {
        // Hypothetical.
        assertAmbiguous(PackageMeasureAmbiguity.ZERO_QUANTITY, "Arroz 0 g", "Arroz 0g", "Papel 0 rolos", "Arroz 0x 1kg")
    }

    @Test
    fun `content beyond Long or a pack count beyond Int is OUT_OF_RANGE`() {
        // Hypothetical.
        assertAmbiguous(PackageMeasureAmbiguity.OUT_OF_RANGE, "Arroz 99999999999999999999 kg", "Sabonete 99999999999 x 90g")
    }

    @Test
    fun `adverse numbers are refused, never thrown`() {
        // Design §8: normalizePackageMeasure never throws. Hypothetical inputs; only the state is asserted.
        listOf(
            "Arroz 0,0 g",
            "Arroz 0 x 0 g",
            "Papel 99999999999999999999 rolos",
            "Sabonete 90g 99999999999un",
            "Sabonete 99999999999999999999 x 99999999999999999999 kg"
        ).forEach { input -> assertIs<PackageMeasureResult.Ambiguous>(normalizePackageMeasure(input), "input '$input'") }
    }

    // Task 7 — spans for S2 (design §6.6, D2): index ranges of the matched expressions in the raw input.

    private fun spansOf(raw: String): List<IntRange> =
        assertIs<PackageMeasureResult.Normalized>(normalizePackageMeasure(raw), "input '$raw'").spans

    private fun spanTextsOf(raw: String): List<String> = spansOf(raw).map { raw.substring(it) }

    @Test
    fun `spans point to the matched expressions in the original input`() {
        assertEquals(listOf("90g", "6un"), spanTextsOf("Sabonete Dove 90g 6un"))
        assertEquals(listOf(14..16, 18..20), spansOf("Sabonete Dove 90g 6un"))
        assertEquals(listOf("6un", "90g"), spanTextsOf("Sabonete Dove 6un 90g"))
    }

    @Test
    fun `span covers the whole pack expression`() {
        assertEquals(listOf("6 x 350 ml"), spanTextsOf("Cerveja 6 x 350 ml"))
        assertEquals(listOf("6x350ml"), spanTextsOf("Cerveja 6x350ml"))
    }

    @Test
    fun `spans keep the original spelling and case`() {
        assertEquals(listOf("5KG"), spanTextsOf("ARROZ BRANCO 5KG"))
        assertEquals(listOf("4 Rolos"), spanTextsOf("Papel Higienico com 4 Rolos"))
    }

    @Test
    fun `spans index the raw input, whitespace included`() {
        // Hypothetical. A whitespace run inside the expression is kept; the one around it is not.
        val raw = "  Cafe\t0,5   kg  "
        assertEquals(listOf(7..14), spansOf(raw))
        assertEquals(listOf("0,5   kg"), spanTextsOf(raw))
    }

    @Test
    fun `spans are ascending and never overlap`() {
        listOf(
            "Sabonete Dove 90g 6un",
            "Sabonete Dove 6un 90g",
            "Cerveja 6 x 350 ml",
            "Papel Higienico com 4 Rolos",
            "Arroz Branco Camil Tipo 1 5kg",
            "Produto 90g 4 unidades"
        ).forEach { raw ->
            spansOf(raw).zipWithNext().forEach { (previous, next) ->
                assertTrue(previous.last < next.first, "input '$raw': $previous then $next")
            }
        }
    }

    // Invariant §7.1 — no dependence on the default locale (design §6.2 rule 1: Locale.ROOT).

    @Test
    fun `the default locale never changes the result`() {
        // Hypothetical inputs. Under tr-TR, a default-locale lowercase turns `I` into a dotless `ı`, so
        // `KILOS`, `LITRO` and `UNIDADES` would stop matching their aliases.
        val expected = mapOf(
            "ARROZ 5 KILOS" to NormalizedPackageMeasure(5000, MeasureUnit.MASS, 1),
            "Oleo 900 ML" to NormalizedPackageMeasure(900, MeasureUnit.VOLUME, 1),
            "LEITE 1 LITRO" to NormalizedPackageMeasure(1000, MeasureUnit.VOLUME, 1),
            "OVOS 12 UNIDADES" to NormalizedPackageMeasure(12, MeasureUnit.COUNT, 1)
        )
        val reference = expected.keys.associateWith(::normalizePackageMeasure)
        expected.forEach { (input, measure) -> assertEquals(measure, measureOf(input), "reference '$input'") }

        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            expected.keys.forEach { input ->
                assertEquals(reference.getValue(input), normalizePackageMeasure(input), "tr-TR '$input'")
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
