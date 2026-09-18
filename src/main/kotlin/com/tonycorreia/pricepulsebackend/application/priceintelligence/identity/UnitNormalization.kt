package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import java.math.BigDecimal
import java.util.Locale

/**
 * Dimension of a market package measure. Each value fixes its canonical base unit: [MASS] in grams,
 * [VOLUME] in millilitres, [COUNT] in units.
 *
 * Not the receipt's sale unit (`UN`, `KG` on a receipt line, which labels a purchased quantity): this
 * is the net content of a package. Design `docs/superpowers/specs/2026-09-18-s1b-unit-normalization-design.md` §4.3.
 */
enum class MeasureUnit { MASS, VOLUME, COUNT }

/**
 * Canonical measure of a market package: [quantity] is the content of ONE sellable unit in the base
 * unit of [measureUnit], and [packCount] is how many such units are sold together.
 *
 * The two are never multiplied: six 90 g bars are `(90, MASS, 6)`, never `(540, MASS, 1)`, so a
 * multipack can equal neither the single bar nor a 540 g bar. The invariants live in `init`, so
 * `copy()` cannot bypass them either.
 */
data class NormalizedPackageMeasure(val quantity: Long, val measureUnit: MeasureUnit, val packCount: Int) {
    init {
        require(quantity > 0) { "quantity must be positive" }
        require(packCount >= 1) { "packCount must be at least 1" }
    }
}

/**
 * Outcome of normalizing the package measure of a market product name. Fail-closed: anything that
 * cannot be normalized safely is [Ambiguous], never guessed.
 */
sealed interface PackageMeasureResult {

    /**
     * A measure was recognized. [spans] are the index ranges of the matched expressions in the
     * original input, which S2 removes from the textual identity instead of re-parsing sizes.
     */
    data class Normalized(val measure: NormalizedPackageMeasure, val spans: List<IntRange>) : PackageMeasureResult

    /** No size and no pack language in the name. */
    data object Absent : PackageMeasureResult

    /** Recognizable measure language that cannot be normalized safely. [reason] is diagnostic only. */
    data class Ambiguous(val reason: PackageMeasureAmbiguity) : PackageMeasureResult
}

/**
 * Why a measure was refused. Declaration order is the reporting order: when several reasons apply, the
 * first one declared is reported. Consumers must treat every [PackageMeasureResult.Ambiguous] alike.
 */
enum class PackageMeasureAmbiguity {
    AMBIGUOUS_DECIMAL_SEPARATOR,
    OUT_OF_RANGE,
    ZERO_QUANTITY,
    NON_EXACT_QUANTITY,
    MULTIPLE_SIZES,
    UNPARSED_PACK_SIGNAL
}

/**
 * Normalizes the package measure written in a market product name ([raw], already decoded by the
 * source adapter). A size is `NUMBER MEASURE`, glued (`500g`) or separated by one whitespace run
 * (`500 g`, `1 Litro`), occupying whole tokens; `MEASURE` must be an alias of the closed table
 * (design §6.4). Matching is case-insensitive with [Locale.ROOT]; tokens are separated by
 * `\p{javaWhitespace}` only, so an internal NBSP never separates them.
 *
 * The number first goes through the decimal grammar of design §6.3: a number with two readings is
 * refused as [PackageMeasureAmbiguity.AMBIGUOUS_DECIMAL_SEPARATOR] before any value is built.
 * Otherwise it is parsed as a [BigDecimal] and scaled exactly to grams or millilitres. A canonical value
 * that is not an integer is refused as [PackageMeasureAmbiguity.NON_EXACT_QUANTITY]; nothing is ever
 * rounded. Spans index [raw] itself.
 *
 * Packs (design §6.2 rules 4, 5 and 8): `N x SIZE` (spaces around `x` optional) and a count expression
 * `N COUNT` next to a size (`90g 6un`, `6un 90g`) set `packCount = N`. A count expression with no size
 * is the content of one package: `com 4 Rolos` is `(4, COUNT, 1)`. `quantity` is never multiplied by
 * `packCount`.
 *
 * Refusals (design §6.2 rules 6–7, §6.5): two sizes or two counts are
 * [PackageMeasureAmbiguity.MULTIPLE_SIZES]; a pack word, a bare count word, or a count not next to the
 * size is [PackageMeasureAmbiguity.UNPARSED_PACK_SIGNAL]; a zero is
 * [PackageMeasureAmbiguity.ZERO_QUANTITY]; a content beyond [Long] or a pack count beyond [Int] is
 * [PackageMeasureAmbiguity.OUT_OF_RANGE]. When several apply, the first declared is reported (design §8).
 * Never throws.
 */
fun normalizePackageMeasure(raw: String): PackageMeasureResult {
    val tokens = TOKEN.findAll(raw).toList()
    val expressions = findExpressions(tokens)
    val sizes = expressions.filter { it.kind != ExpressionKind.COUNT }
    val counts = expressions.filter { it.kind == ExpressionKind.COUNT }
    val size = sizes.singleOrNull()
    val count = counts.singleOrNull()
    val content = if (sizes.isEmpty()) count else size
    // Rule 4: a unit count next to a plain size is its pack count. Rule 5: a count with no size is the content.
    val packCountExpression = count?.takeIf {
        it.alias in PACK_COUNT_ALIASES && size?.kind == ExpressionKind.SIZE && size.isNextTo(it)
    }

    val reasons = mutableSetOf<PackageMeasureAmbiguity>()
    if (sizes.size > 1 || counts.size > 1) reasons += PackageMeasureAmbiguity.MULTIPLE_SIZES
    if (hasBarePackWord(tokens, expressions) || (size != null && count != null && packCountExpression == null)) {
        reasons += PackageMeasureAmbiguity.UNPARSED_PACK_SIGNAL
    }
    // Every number is checked, so that the reason reported is the first applicable one.
    val quantities = expressions.associateWith {
        check(it.number, it.scale.powerOfTen, if (it === packCountExpression) MAX_PACK_COUNT else Long.MAX_VALUE)
    }
    val packNumbers = sizes.mapNotNull { expression -> expression.packNumber?.let { expression to check(it, 0, MAX_PACK_COUNT) } }.toMap()
    (quantities.values + packNumbers.values).forEach { reasons += it.reasons }
    // Enum order is declaration order, which is the reporting order.
    reasons.minOrNull()?.let { return PackageMeasureResult.Ambiguous(it) }

    if (content == null) return PackageMeasureResult.Absent
    val packCount = packNumbers[content]?.value ?: packCountExpression?.let { quantities.getValue(it).value } ?: 1L
    val spans = listOfNotNull(content.span, packCountExpression?.span).sortedBy { it.first }
    return PackageMeasureResult.Normalized(
        NormalizedPackageMeasure(quantities.getValue(content).value!!, content.scale.measureUnit, packCount.toInt()),
        spans
    )
}

private const val MAX_PACK_COUNT = Int.MAX_VALUE.toLong()

/** A checked number: its canonical [value], or the [reasons] it has none. */
private class Checked(val value: Long?, val reasons: Set<PackageMeasureAmbiguity>)

/**
 * Checks one number against the decimal grammar (design §6.3) and the conversion rules (§6.5): scaled by
 * 10^[powerOfTen], it must be an exact integer in `1..`[max]. Never throws, never rounds.
 */
private fun check(number: String, powerOfTen: Int, max: Long): Checked {
    if (hasAmbiguousSeparator(number)) return Checked(null, setOf(PackageMeasureAmbiguity.AMBIGUOUS_DECIMAL_SEPARATOR))
    val canonical = BigDecimal(number.replace(',', '.')).movePointRight(powerOfTen).stripTrailingZeros()
    val reasons = buildSet {
        if (canonical > BigDecimal.valueOf(max)) add(PackageMeasureAmbiguity.OUT_OF_RANGE)
        if (canonical.signum() == 0) add(PackageMeasureAmbiguity.ZERO_QUANTITY)
        if (canonical.scale() > 0) add(PackageMeasureAmbiguity.NON_EXACT_QUANTITY)
    }
    return Checked(if (reasons.isEmpty()) canonical.longValueExact() else null, reasons)
}

/** Rule 6: a pack word, or a count word outside every recognized expression. */
private fun hasBarePackWord(tokens: List<MatchResult>, expressions: List<Expression>): Boolean {
    val consumed = expressions.flatMap { it.firstToken..it.lastToken }.toSet()
    return tokens.withIndex().any { (index, token) ->
        val word = token.value.lowercase(Locale.ROOT)
        index !in consumed && (word in PACK_SIGNAL_WORDS || word in COUNT_ALIASES)
    }
}

/**
 * The closed expression grammar, tried in this order at each token. [pattern] matches whole tokens
 * joined by one space, so ` ?` accepts the glued and the spaced spelling alike. Every number captures
 * any number of separators so that N1 can refuse them (see hasAmbiguousSeparator), and a decimal count
 * reaches [PackageMeasureAmbiguity.NON_EXACT_QUANTITY] instead of going unrecognized.
 */
private enum class ExpressionKind(val maxTokens: Int, val pattern: Regex, val aliases: Map<String, BaseScale>) {
    PACK(4, Regex("(\\d+(?:[.,]\\d+)*) ?x ?(\\d+(?:[.,]\\d+)*) ?(\\p{L}+)"), MEASURE_ALIASES),
    SIZE(2, Regex("(\\d+(?:[.,]\\d+)*) ?(\\p{L}+)"), MEASURE_ALIASES),
    COUNT(2, Regex("(\\d+(?:[.,]\\d+)*) ?(\\p{L}+)"), COUNT_ALIASES)
}

/**
 * An expression over tokens [firstToken]..[lastToken]; [alias] is its measure or count word, lowercased;
 * [packNumber] is set for [ExpressionKind.PACK] only.
 */
private class Expression(
    val kind: ExpressionKind,
    val number: String,
    val alias: String,
    val scale: BaseScale,
    val packNumber: String?,
    val firstToken: Int,
    val lastToken: Int,
    val span: IntRange
) {
    fun isNextTo(other: Expression): Boolean = other.lastToken + 1 == firstToken || other.firstToken == lastToken + 1
}

private fun findExpressions(tokens: List<MatchResult>): List<Expression> {
    val found = mutableListOf<Expression>()
    var index = 0
    while (index < tokens.size) {
        val expression = expressionAt(tokens, index)
        if (expression == null) index++ else {
            found += expression
            index = expression.lastToken + 1
        }
    }
    return found
}

private fun expressionAt(tokens: List<MatchResult>, start: Int): Expression? {
    for (kind in ExpressionKind.entries) {
        for (last in start until minOf(start + kind.maxTokens, tokens.size)) {
            val text = tokens.subList(start, last + 1).joinToString(" ") { it.value.lowercase(Locale.ROOT) }
            val match = kind.pattern.matchEntire(text) ?: continue
            val groups = match.groupValues
            val scale = kind.aliases[groups.last()] ?: continue
            val packNumber = if (kind == ExpressionKind.PACK) groups[1] else null
            return Expression(
                kind, groups[groups.size - 2], groups.last(), scale, packNumber, start, last,
                tokens[start].range.first..tokens[last].range.last
            )
        }
    }
    return null
}

/**
 * Decimal grammar of design §6.3, decided from the text alone — never from a locale.
 *
 * - N1: more than one separator (`1.000,5`, `1,000.5`, `1.000.000`) is ambiguous.
 * - N2: one separator followed by exactly three digits, after an integer part of one to three digits
 *   not starting with 0 (`1.000`, `1,000`, `2.500`, `1,250`), is also a thousands-grouped integer, so it
 *   is ambiguous. The separator character never decides.
 * - N3: otherwise the separator, `,` or `.`, is the decimal point (`0,5`, `1.5`, `0.330`, `12.5`).
 */
private fun hasAmbiguousSeparator(number: String): Boolean {
    val separators = number.count { it == ',' || it == '.' }
    if (separators > 1) return true
    if (separators == 0) return false
    val (integerPart, fraction) = number.split(',', '.')
    return fraction.length == 3 && integerPart.length in 1..3 && integerPart[0] != '0'
}

/** How to reach the canonical base unit: multiply by 10^[powerOfTen]. */
private data class BaseScale(val measureUnit: MeasureUnit, val powerOfTen: Int)

private val TOKEN = Regex("[^\\p{javaWhitespace}]+")

/** Count aliases of the closed table (design §6.4). */
private val COUNT_SCALE = BaseScale(MeasureUnit.COUNT, 0)
private val COUNT_ALIASES: Map<String, BaseScale> =
    listOf("un", "und", "unid", "unidade", "unidades", "rolo", "rolos").associateWith { COUNT_SCALE }

/**
 * Count aliases that may set a pack count next to a size (design §6.2 rule 4). Rolls are not among them:
 * a count of rolls is the content of one package (D4), so rolls next to a size are unparsed pack language.
 */
private val PACK_COUNT_ALIASES = setOf("un", "und", "unid", "unidade", "unidades")

/** Pack signal words of design §6.2 rule 6, matched as whole tokens. */
private val PACK_SIGNAL_WORDS = setOf("pack", "kit", "fardo", "caixa", "cx", "leve", "combo", "c/")

/** Mass and volume aliases of the closed table (design §6.4). */
private val MEASURE_ALIASES: Map<String, BaseScale> = buildMap {
    listOf("g", "gr", "grs", "grama", "gramas").forEach { put(it, BaseScale(MeasureUnit.MASS, 0)) }
    listOf("kg", "kgs", "kilo", "kilos", "quilo", "quilos", "quilograma", "quilogramas")
        .forEach { put(it, BaseScale(MeasureUnit.MASS, 3)) }
    put("ml", BaseScale(MeasureUnit.VOLUME, 0))
    listOf("l", "lt", "lts", "litro", "litros").forEach { put(it, BaseScale(MeasureUnit.VOLUME, 3)) }
}
