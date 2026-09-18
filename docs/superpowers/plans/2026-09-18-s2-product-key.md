# S2 — `ProductKey` — Implementation Plan

**Status: `APPROVED_BY_PO`** (2026-09-18). Built on PO-1 to PO-11 (2026-09-18) and on the final PO
reconciliation of the same date: SR-1, SR-2, SR-3, SR-4, SR-7 and SR-8 approved; SR-5 and SR-6 approved
as modified (design §19). **S2 implementation: `NOT_STARTED`.** Approved is not authorized: each task
still needs explicit PO authorization before it runs.

Design: `docs/superpowers/specs/2026-09-18-s2-product-key-design.md` (`APPROVED_BY_PO`, 2026-09-18).
Parent: `../PricePulse/docs/product-development/plans/price-intelligence-foundation-v1.md` §6 S2.

> **For agentic workers:** execute with superpowers:executing-plans or
> superpowers:subagent-driven-development, test-first (superpowers:test-driven-development). Steps use
> checkbox (`- [ ]`) syntax. Every RED must be **seen failing for the expected reason** before GREEN.

**Goal:** pure backend value objects and one entry point, `resolveProductKey`, that turn a market
title (plus an optional GTIN, brand and variant) into a `ProductKey` — or refuse explicitly.

**Architecture:** four production files in `application/priceintelligence/identity/`. They consume S1
(`MarketTextKey.from`, `normalizePackageMeasure`) unchanged. No I/O, no clock, no persistence, no HTTP.
**Backend only.**

**Tech stack:** Kotlin/JVM 2.2.10, `kotlin.test` on JUnit 5.

---

## Execution preconditions

S2 may not start until **all** of these hold:

1. The design and this plan are approved by the PO, including design §19 (SR-1 to SR-8) — **satisfied
   on 2026-09-18**.
2. A backend branch is created from `origin/main` (`4fb9801` or later) **with explicit PO
   authorization** (backend `CLAUDE.md`). Suggested: `feat/m5-s2-product-key`. The current local
   checkout (`feat/m5-s1a-market-text-key`) is behind `origin/main` and must not be used as the base.
3. The design and this plan are carried onto that branch unchanged.
4. **Freeze point** (below) passed.
5. The Task 0 baseline is captured **before the first edit**.

## Freeze point — before the first implementation edit

After approval and before Task 1 Step 1, the following are **frozen**. Changing any of them afterwards
is a design change that needs PO approval, not an implementation detail:

- type and function names of design §4.2, and the four files of design §4.1;
- the result boundaries (design §9.0), the fail-closed matrix (§9.2) and the step order (§9.1);
- the **independence of the GTIN path** (design §9.3): the GTIN is evaluated first, and a valid GTIN is
  never turned into `Unresolved` or `Rejected`;
- the GTIN rules G1–G4, the canonical 14-digit form and the **GTIN vectors** (design §5, §5.4);
- the span contract (design §8) and the pipeline (design §7);
- equality semantics (design §10) and the **identity-level shape** (design §11): a level on `Resolved`
  and `Unresolved` only, none on `Rejected`, none on the `ProductKeyResult` interface;
- the `S1_IMMUTABILITY_PATHS` and their recorded blobs (design AC12);
- SR-1 to SR-8 as approved (or as amended by the PO).

If implementation disproves a frozen item, follow `pricepulse-assumption-audit` «Mismatches»: record
the evidence, stop, and ask.

## Global constraints

- **Pure Kotlin/JVM.** No Ktor, Postgres, Android or `java.time`. Depends only on S1 and the stdlib.
- **S1 is read-only.** The five `S1_IMMUTABILITY_PATHS` of design AC12 are gated by blob; the S1 unit
  tests (`MarketTextKeyTest.kt`, `UnitNormalizationTest.kt`) are protected by the scope check of
  Task 10 Step 6. S2 edits no S1 file.
- **GTIN first, GTIN final** (design §9.3). The builder parses the GTIN before any signature step and
  never lets the signature path decide the result when the GTIN is valid.
- **Factories build keys** (SR-5): `ProductKey.fromGtin` / `ProductKey.fromAttributes` are the only call
  sites of the variant constructors. **One copy of a signature** (SR-6): `Resolved.ByAttributes` stores its
  signature once; `ByGtin` stores only the auxiliary one; no `init` reconciles copies.
- **No second size parser** (design G-1): S2 uses S1b's spans and measure as given. No digit regex, no
  unit alias, no `kg`/`ml`/`un` literal in S2 production code.
- **No extraction** of brand, variant or category from the title (PO-4, PO-9). No `category` or
  `displayName` field.
- **No canonical string**, serialization, hash or version field (PO-10).
- **No exception for expected external data** (PO-11). `require` only for value-object invariants.
- **Never fall back** from an invalid GTIN to the signature (PO-6). **Never** let the auxiliary
  signature block a valid GTIN key (PO-7).
- **Kotlin `trim()` only** — never `java.lang.String.trim()` or `strip()` (design G-2).
- **No scope creep:** no `PriceObservation`, provenance, catalog, `MarketProduct`, adapter,
  sanitization, fuzzy matching, `Product` → `ProductKey` resolution.
- Backend test convention: `kotlin.test`, backticked names; source of each evidenced case in a comment.
  Snippets after Task 1 omit imports: add the `kotlin.test` assertions they use (`assertIs`,
  `assertNull`, `assertNotNull`, `assertNotEquals`, `assertFailsWith`, `assertTrue`).
- Non-ASCII test inputs are written as Kotlin `\uXXXX` escapes, never as literal invisible
  characters (same rule as the S1a fixture).
- Test snippets are the minimum RED set, not a ceiling. They are written into the test files only
  during execution, never during this authoring step.

## Assumption audit

Performed on 2026-09-18 against backend `origin/main` `4fb9801`, after incorporating PO-1 to PO-11.

| Assumption | Evidence inspected | Planning consequence |
| --- | --- | --- |
| No `ProductKey`, `Gtin`, `AttributeSignature`, `ProductIdentityLevel`, brand or variant type exists | full-tree search of `src`, `contracts`, migrations, build files on `origin/main`: zero hits (only unrelated `receiptanalysis` comments) | New types; no implementer, fake, DI binding, route or caller to update |
| Nothing consumes `identity/` in production | search for `MarketTextKey`, `normalizePackageMeasure`, `NormalizedPackageMeasure` in `src/main`: only their own files | Adding files affects no call site; no interface impact |
| `MarketTextKey` has a private constructor; `from` throws `IllegalArgumentException` on blank after Kotlin `trim()` | `MarketTextKey.kt:22-33` | S2 must test `isBlank()` before calling `from` on external data (PO-11); build keys in tests with `MarketTextKey.from` |
| `normalizePackageMeasure` never throws; spans index its input, ascending, non-overlapping | `UnitNormalization.kt:87-121` KDoc and code; S1b design §6.6, §8 | S2 replaces spans from last to first; no bounds re-validation beyond S1b's guarantee |
| S1b splits tokens on `\p{javaWhitespace}` only, so a border NBSP glued to a size hides it | `UnitNormalization.kt:231` (`TOKEN`); discovery probe: `\u00a0Cafe 500G\u00a0` ⇒ `Absent` on raw, `(500,MASS,1)` after `trim()` | PO-1's `trim()` is required; border NBSP/U+202F/U+2007 tests |
| `NormalizedPackageMeasure` is a public `data class` with `init` invariants | `UnitNormalization.kt:23-28` | Used as is as the measure component; tests build expected values directly |
| Every `PackageMeasureAmbiguity` value is reachable from a title | `UnitNormalizationTest.kt` single-reason tests; discovery probe | Task 6 iterates over all six values and asserts the S1b reason first, so an S1b change surfaces as a precise failure |
| Backend result convention: sealed `…Result`, `Resolved`, `Rejected(reason)` | `StartReceiptAnalysisResult`, `ReceiptUploadResult`, `PackageMeasureResult` | Names of design §4.6 |
| Value-class convention: `@JvmInline`, private ctor + factory, `require` for shape | `MarketTextKey.kt`, `ContentHash.kt` | `Gtin` |
| All-zero GTINs pass the GS1 check digit | computed during planning: `00000000`, `00000000000000` valid under mod 10 | G3 is an explicit rule with its own test |
| Test GTIN vectors are correct | every vector of design §5.4 computed with the GS1 algorithm during planning; six are real EANs from the POCs | Tests use those literal values |
| No time, randomness, id generation or configuration is involved | design §4 | No clock or fixture needed |
| Full-suite baseline on `origin/main` `4fb9801` | `./gradlew.bat test --continue` on an export of `origin/main`, 2026-09-18: **482 tests, 2 failures** — `PostgresReceiptAnalysisOperationStoreTest`, `PostgresSchemaContractTest` (no Docker) | Task 0 expectation; re-captured on the branch before any edit |
| The backend has no `verify.ps1` harness | `origin/main` tree: absent | Gate = targeted tests + full suite against the baseline |
| S1 artifacts have stable git blobs | `git rev-parse origin/main:<path>` for the five paths of design AC12 (blobs recorded there); last S1 commit touching them `f7da770`; `core.autocrlf=false`; fixture SHA-256 `aae74726…fdbe136b` equals the hash pinned by `MarketTextKeyParityTest.kt:30` | Gate by `git hash-object` + `git diff --exit-code <base>` on exactly those paths |
| Where `AMBIGUOUS_IDENTITY` is required | app delivery plan lines 219–220 (enum value) and 229–230 (RED «entrada insuficiente → `AMBIGUOUS_IDENTITY`»); parent spec §6.1, §6.2; S1b design §8. No text assigns a level to invalid input | Level on `Unresolved`, none on `Rejected` (design §11) |
| «`Rejected` has no level» is testable without `kotlin-reflect` | Java reflection (`Class.getMethods`) needs no extra dependency | Task 4 asserts no `getIdentityLevel` method on `Rejected` |
| `internal` constructors can be kept from leaking through `copy()` | `build.gradle.kts:2` `kotlin("jvm") version "2.2.10"`; `kotlin/ConsistentCopyVisibility.class` present in the cached `kotlin-stdlib-2.2.10.jar` | `GtinKey`, `AttributeKey`, `Resolved.ByGtin`, `Resolved.ByAttributes` use `internal constructor` + `@ConsistentCopyVisibility` (design §4.2, SR-5, SR-6) |
| The two `Resolved` shapes store only their own fields | design §4.2: `ByGtin(gtin, auxiliaryAttributeSignature)`, `ByAttributes(signature)`; Java reflection over `declaredFields` sees backing fields only | Task 4 asserts the field sets, which proves «no second signature copy» structurally |

**Unresolved questions:** none. SR-1 to SR-8 are decided (design §19).

## File Structure

All paths are under `src/{main,test}/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/`.

| File | Action | Tasks |
| --- | --- | --- |
| `main/.../ProductIdentityLevel.kt` | Create. `ProductIdentityLevel` | 1 |
| `main/.../Gtin.kt` | Create. `Gtin`, `GtinParseResult`, `GtinRejection` | 2 |
| `main/.../AttributeSignature.kt` | Create. `AttributeSignature`; internal signature derivation | 3, 5–7 |
| `main/.../ProductKey.kt` | Create. `ProductKey` (with `fromGtin`, `fromAttributes`), `ProductKeyBasis`, `ProductKeyResult` (with `Resolved.ByGtin`, `Resolved.ByAttributes`), `UnresolvedReason`, `InputRejection`, `resolveProductKey` | 1, 3, 4–8 |
| `test/.../GtinTest.kt` | Create | 2 |
| `test/.../AttributeSignatureTest.kt` | Create. Signature value object and canonicalization | 3, 5, 6 |
| `test/.../ProductKeyTest.kt` | Create. Keys, results, fail-closed, precedence, collisions, corpus | 1, 3, 4, 7–9 |
| `docs/superpowers/specs/2026-09-18-s2-product-key-design.md` | Create (this authoring step) | — |
| `docs/superpowers/plans/2026-09-18-s2-product-key.md` | Create (this plan) | — |

No other file changes: no test double, fake, DI binding, route, schema, contract or app file.
`Gtin.kt` and the test files `GtinTest.kt` and `AttributeSignatureTest.kt` are **added** to the files the
delivery plan lists; no listed file is replaced (design §19 SR-5). The delivery plan's public API,
`ProductKey.fromGtin(...)` / `ProductKey.fromAttributes(...)`, is kept as is.

---

## Task 0: Baseline — before any edit

- [ ] **Step 1: Confirm the base.** `git rev-parse HEAD origin/main` and `git status --porcelain`.
Expected: HEAD is `origin/main` (or its descendant carrying only the design and plan); the working tree
is clean except those two files.
- [ ] **Step 2: Capture the full-suite baseline.** `./gradlew.bat test --continue`. Record total, passed,
failed and the **names** of the failing tests, outside the working tree or under an ignored path; never
commit it. Expected: 482 tests, 2 failures — `PostgresReceiptAnalysisOperationStoreTest` and
`PostgresSchemaContractTest`.
- [ ] **Step 3: Record the S1 immutability base (design AC12).** Record `BASE = git merge-base HEAD origin/main`
(the `origin/main` commit the branch was created from, even if the branch already carries a commit with
the design and this plan). With
`ID=src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity` and
`IT=src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity`, the
`S1_IMMUTABILITY_PATHS` are exactly:

```text
$ID/MarketTextKey.kt                          3338193a18de468fb305edf9a21be5749af881ef
$ID/UnitNormalization.kt                      9323148d00dff697521a346815e5719b7a01b9d2
contracts/fixtures/text-key/parity-cases.v1.json  0e3f4f4f32a8382dae615e258cd10f40cb7ea1cc
contracts/fixtures/text-key/README.md         a17a83395cd089a7ac06d925b1862a4d5d857b81
$IT/MarketTextKeyParityTest.kt                2493878791682634d55493746a2c282de8b2d84c
```

Run `git hash-object <path>` for each. Expected: the blob listed. If a blob differs, S1 changed on
`origin/main` after this plan: **STOP** and ask; do not update the table silently.
- [ ] **Step 4: STOP if the baseline differs** from the expected in any way other than environment, and
report it before writing code.

## Task 1: Levels and bases

**Files:** create `ProductIdentityLevel.kt`, `ProductKey.kt` (only `ProductKeyBasis` for now),
`ProductKeyTest.kt`.

- [ ] **Step 1: RED**

```kotlin
package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
```

Run `./gradlew.bat test --tests '*ProductKeyTest*'`. Expected: compilation failure (types absent).

- [ ] **Step 2: GREEN.** `enum class ProductIdentityLevel` and `enum class ProductKeyBasis(val identityLevel)`
exactly as in design §4.2. Expected: PASS.

## Task 2: `Gtin` value object

**Files:** create `Gtin.kt`, `GtinTest.kt`.

- [ ] **Step 1: RED — valid lengths and canonical form**

```kotlin
class GtinTest {

    private fun valid(raw: String): Gtin = assertIs<GtinParseResult.Valid>(Gtin.parse(raw)).gtin
    private fun rejection(raw: String): GtinRejection = assertIs<GtinParseResult.Invalid>(Gtin.parse(raw)).rejection

    @Test
    fun `accepts gtin-13 and keeps it as 14 digits`() {
        // Real Atacadão EAN, POC B data/atacadao-observations.json.
        assertEquals("07896006711155", valid("7896006711155").value)
    }

    @Test
    fun `accepts gtin-8, gtin-12 and gtin-14`() {
        // Design §5.4 valid table; check digits computed and executed, not taken from prose.
        assertEquals("00000096385074", valid("96385074").value)       // GTIN-8, check 4
        assertEquals("00000012345670", valid("12345670").value)       // GTIN-8, check 0
        assertEquals("00036000291452", valid("036000291452").value)   // GTIN-12, check 2
        assertEquals("00036000291452", valid("00036000291452").value) // GTIN-14, check 2
        assertEquals("17896006711152", valid("17896006711152").value) // GTIN-14, indicator 1, check 2
    }

    @Test
    fun `leading zeros denote the same gtin at every length`() {
        val gtin12 = valid("036000291452")
        assertEquals(gtin12, valid("0036000291452"))
        assertEquals(gtin12, valid("00036000291452"))
        assertEquals(gtin12.hashCode(), valid("00036000291452").hashCode())
    }

    @Test
    fun `the gtin-14 indicator digit is significant`() {
        assertNotEquals(valid("17896006711152"), valid("07896006711155"))
    }

    @Test
    fun `accepts every real ean of the pocs`() {
        // POC B atacadao-observations.json; POC A evidence/atacadao.md.
        listOf("7896006711155", "7896006744115", "7898215151708", "7891910030347", "7893500020158", "7893500020110")
            .forEach { assertEquals("0$it", valid(it).value) }
    }
}
```

Expected: compilation failure.

- [ ] **Step 2: RED — rejections (G1–G4, SR-3, SR-4, SR-8)**

```kotlin
@Test
fun `rejects an invalid check digit at every length`() {
    // Design §5.4 invalid table: last digit differs from the expected check digit.
    listOf("96385075", "036000291453", "7896006711156", "17896006711153")
        .forEach { assertEquals(GtinRejection.INVALID_CHECK_DIGIT, rejection(it)) }
}

@Test
fun `rejects all zeros although the check digit is valid`() {
    // Design §5.3: 0…0 passes mod 10.
    listOf("00000000", "000000000000", "0000000000000", "00000000000000")
        .forEach { assertEquals(GtinRejection.ALL_ZEROS, rejection(it)) }
}

@Test
fun `rejects unsupported lengths including empty`() {
    listOf("", "1234567", "123456789", "12345678901", "123456789012345")
        .forEach { assertEquals(GtinRejection.UNSUPPORTED_LENGTH, rejection(it)) }
}

@Test
fun `rejects non digits and never trims`() {
    listOf(" 7896006711155", "7896006711155 ", "789600671115a", "7896-006711155", "789600671115\u0665")
        .forEach { assertEquals(GtinRejection.NON_DIGIT, rejection(it)) }
}

@Test
fun `reports the first failing check in declaration order`() {
    assertEquals(GtinRejection.NON_DIGIT, rejection("12a"))          // also a bad length
    assertEquals(GtinRejection.UNSUPPORTED_LENGTH, rejection("000")) // also all zeros
}

@Test
fun `parse never throws`() {
    listOf("", " ", "\u00a0", "x".repeat(10_000), "9".repeat(10_000)).forEach { Gtin.parse(it) }
}
```

Expected: FAIL.

- [ ] **Step 3: GREEN.** `@JvmInline value class Gtin private constructor(val value: String)` with an
`init { require(...) }` for the 14-ASCII-digit shape; `companion object { fun parse(raw: String): GtinParseResult }`
applying G1 (`all { it in '0'..'9' }`), G2, G3, G4 in that order; canonical `raw.padStart(14, '0')`;
check digit over positions 1–13 of the padded value with weights 3, 1, 3, …; `GtinParseResult` and
`GtinRejection` as in design §4.2. Expected: PASS.

## Task 3: `AttributeSignature` and `ProductKey` value objects

**Files:** create `AttributeSignature.kt`, `AttributeSignatureTest.kt`; extend `ProductKey.kt`,
`ProductKeyTest.kt`.

- [ ] **Step 1: RED — structural equality**

```kotlin
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
}
```

- [ ] **Step 2: RED — keys (append to `ProductKeyTest`)**

```kotlin
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
```

Expected: compilation failure.

- [ ] **Step 3: GREEN.** `data class AttributeSignature` (design §4.2); `sealed interface ProductKey`
with `GtinKey` and `AttributeKey` (`internal constructor`, `@ConsistentCopyVisibility`) and the companion
factories `fromGtin` / `fromAttributes` returning `ProductKey`. No hand-written `equals`/`hashCode`.
Expected: PASS.

## Task 4: The result type

**Files:** extend `ProductKey.kt`, `ProductKeyTest.kt`.

- [ ] **Step 1: RED**

```kotlin
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
```

Expected: compilation failure.

- [ ] **Step 2: GREEN.** `ProductKeyResult` (with the sealed `Resolved` and its shapes `ByGtin` /
`ByAttributes`, `internal constructor` + `@ConsistentCopyVisibility`, `key` derived through the
factories), `UnresolvedReason`, `InputRejection` as in design §4.2. **No `init`** in the `Resolved`
shapes. Expected: PASS.

## Task 5: Canonicalization — pipeline and spans (no GTIN)

**Files:** extend `AttributeSignature.kt` (internal derivation), `ProductKey.kt` (`resolveProductKey`,
signature path only), `AttributeSignatureTest.kt`.

Test helper (in `AttributeSignatureTest`):

```kotlin
private fun signatureOf(title: String, brand: String? = null, variant: String? = null): AttributeSignature {
    val result = assertIs<ProductKeyResult.Resolved>(resolveProductKey(title, gtin = null, brand = brand, variant = variant))
    return assertIs<ProductKey.AttributeKey>(result.key).signature
}
private fun nameOf(title: String) = signatureOf(title).canonicalName.value
private fun measureOf(title: String) = signatureOf(title).packageMeasure
```

- [ ] **Step 1: RED — span positions and multiple spans (design §8)**

```kotlin
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
```

Expected: compilation failure (`resolveProductKey` absent).

- [ ] **Step 2: GREEN.** Internal derivation in `AttributeSignature.kt`: `analysisInput = title.trim()`
(Kotlin); `normalizePackageMeasure(analysisInput)`; on `Normalized`, replace spans with `" "` from the
last to the first; `remainder.isBlank()` ⇒ `Unresolved(EMPTY_CANONICAL_NAME)`; else
`MarketTextKey.from(remainder)`; success ⇒ `Resolved.ByAttributes(signature)`. `resolveProductKey`
with `gtin == null` only for now (a present GTIN may `TODO()` until Task 8; no test reaches it).
Expected: PASS.

## Task 6: Whitespace, text and measure refusals

**Files:** extend `AttributeSignatureTest.kt`, `ProductKeyTest.kt`.

- [ ] **Step 1: RED — whitespace and text (append to `AttributeSignatureTest`)**

```kotlin
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
```

- [ ] **Step 2: RED — measure refusals and empty name (append to `ProductKeyTest`)**

```kotlin
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
```

Expected: FAIL only where Task 5's GREEN is incomplete; otherwise these are regression locks and must be
seen to fail by temporarily breaking the `trim()` or the blank check (record which).

- [ ] **Step 3: GREEN / confirm.** Expected: PASS.

## Task 7: Structured brand/variant and invalid input (no GTIN)

**Files:** extend `ProductKeyTest.kt`, `ProductKey.kt`.

- [ ] **Step 1: RED**

```kotlin
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
```

Expected: FAIL (brand/variant not consumed yet).

- [ ] **Step 2: GREEN.** Steps of design §9.1 in order; `MarketTextKey.from` for brand and variant
after the blank check. Expected: PASS.

## Task 8: GTIN precedence and the auxiliary signature

**Files:** extend `ProductKey.kt`, `ProductKeyTest.kt`.

- [ ] **Step 1: RED**

```kotlin
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
```

Expected: FAIL.

- [ ] **Step 2: GREEN.** The GTIN branch of design §9.1: `Gtin.parse` **first**; `Invalid` ⇒ `Rejected`
without running the signature path; `Valid` ⇒ run the signature path and return
`Resolved.ByGtin(gtin, auxiliaryAttributeSignature = (outcome as? Resolved)?.attributeSignature)`. The
signature path's `Rejected` or `Unresolved` is never returned on this branch. Remove any `TODO()`.
Expected: PASS.

## Task 9: Collision matrix, real corpus and totality

**Files:** extend `ProductKeyTest.kt`.

- [ ] **Step 1: RED / lock — collisions (design §12)**

```kotlin
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
        "\u00a0Cafe 500G\u00a0" to "Cafe 500g"
    ).forEach { (left, right) -> assertEquals(keyOf(left), keyOf(right), "$left | $right") }
}
```

- [ ] **Step 2: RED / lock — the 15 real titles (design §16.1)**

A table-driven test over the 15 rows of design §16.1: with `s = AttributeSignature(null,
MarketTextKey.from(name), null, measure)`, each title resolves to exactly
`ProductKeyResult.Resolved.ByAttributes(s)` (hence key `ProductKey.fromAttributes(s)`) with the listed
name and measure. The
source comment (POC B `data/carrefour-canonical.json`) lives in the **test** file only; production
KDoc never names a vendor (G-10).

- [ ] **Step 3: RED / lock — totality**

```kotlin
@Test
fun `resolution never throws`() {
    val titles = listOf("", " ", "\u00a0", "\u200b", "500g", "x".repeat(10_000), "9".repeat(5_000) + " g", "Dove 90g 3000000000un")
    val gtins = listOf(null, "", "0", "7896006711155", "\u0661".repeat(13))
    for (title in titles) for (gtin in gtins) for (brand in listOf(null, "", "Dove")) resolve(title, gtin, brand)
}
```

- [ ] **Step 4: GREEN / confirm.** These should pass without production change. Any failure is a
design mismatch: **stop and report** (freeze point). Expected: PASS.

## Task 10: Verification, guards and stop

- [ ] **Step 1: Targeted.** `./gradlew.bat test --tests '*Gtin*' --tests '*AttributeSignature*' --tests '*ProductKey*'`.
Expected: all green.
- [ ] **Step 2: S1 still green.** `./gradlew.bat test --tests '*MarketTextKey*' --tests '*UnitNormalization*'`.
Expected: all green.
- [ ] **Step 3: Full regression.** `./gradlew.bat test --continue`. Expected: the Task 0 baseline plus
the new tests; the **same** failing set (the two Docker tests) and nothing else.
- [ ] **Step 4: Guard greps** over the four new production files
(`Gtin.kt AttributeSignature.kt ProductIdentityLevel.kt ProductKey.kt` under `src/main/.../identity/`).
Each must return **nothing**:
  - no second size parser (G-1): `grep -n 'Regex' <files>` and
    `grep -nE '"(g|gr|kg|ml|l|lt|litro|un|und|unid|unidades?|rolos?)"' <files>` — S2 needs no regex
    at all (`Gtin` uses the character range `'0'..'9'`);
  - no category/display/serialization (AC11): `grep -niE 'category|categoria|displayName|serializ' <files>`
    (KDoc included: production KDoc refers to these only by design section number);
  - no Java trim (G-2): `grep -nE '\.strip\(|java\.lang\.String' <files>`;
  - factories are the only key constructors (SR-5): `grep -rnE '(GtinKey|AttributeKey)\(' src/main/kotlin`
    returns exactly the two lines inside `ProductKey.fromGtin` / `ProductKey.fromAttributes` (this check
    expects those two hits, not zero); every other use of the variants is `is`/`when` matching;
  - no vendor identifiers (G-10): `grep -riE 'vtex|carrefour|atacadao|gpa|regionid|accountname' src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/`.
- [ ] **Step 5: S1 immutability gate (AC12).** `git hash-object` of each of the five
`S1_IMMUTABILITY_PATHS` equals its recorded blob (Task 0 Step 3), **and**
`git diff --exit-code $BASE -- <the five paths>` exits 0 (committed and uncommitted changes both
covered, since the diff is against the working tree). Nothing else is compared: not the repository,
not the S2 docs, not `build/`. `S1_IMMUTABILITY_GATE_VALID` only if both hold.
- [ ] **Step 6: Scope.** `git status --porcelain` shows only the seven source/test files of the File
Structure, plus the design and this plan.
- [ ] **Step 7: STOP.** Commit, push and PR are `HUMAN_AUTHORITY`. Report the outputs of Steps 1–6 and
wait.

## Test matrix — coverage map

| Area | Case | Task |
| --- | --- | --- |
| GTIN | 8 · 12 · 13 · 14 · canonical 14 · leading-zero equivalence · indicator digit · real EANs | 2 |
| GTIN | invalid check digit · all zero · non-digit (incl. non-ASCII digit, spaces) · invalid length (incl. empty) · order · never throws | 2 |
| GTIN precedence | same GTIN/different titles · different GTIN/same title · GTIN vs attribute key · valid GTIN + each auxiliary failure · invalid GTIN never falls back | 3, 8 |
| GTIN independence | GTIN evaluated first (all other inputs defective) · valid GTIN never `Unresolved`/`Rejected` over a title × brand × variant grid · blank brand not rescued as absent | 8 |
| Result shape | `Resolved.ByGtin` / `ByAttributes` derive key, level and `attributeSignature` · auxiliary signature outside `GtinKey` equality/hash/basis/level · `ByAttributes` has one signature field only (no second copy possible) · `Unresolved` ⇒ `AMBIGUOUS_IDENTITY` · `Rejected` and the interface have no level | 4 |
| Factories | `fromGtin` / `fromAttributes` build the two variants and return `ProductKey` · only constructor call sites (guard grep) | 3, 10 |
| S1 immutability | five `S1_IMMUTABILITY_PATHS` by blob and by `git diff --exit-code $BASE` | 0, 10 |
| Signature | brand present/absent · variant present/absent · different brand · MASS · VOLUME · COUNT · packCount 1 vs 6 · `6x90g` ≡ `90g 6un` · decimal · measure Absent · every Ambiguous reason alike · empty name · blank inputs | 3, 5, 6, 7 |
| Spans | start · middle · end · multiple spans · single multipack span · title only a measure · punctuation kept · `com` kept · non-measure numbers kept | 5, 6 |
| Whitespace | NBSP / U+202F / U+2007 / EM SPACE at borders · internal NBSP distinct · ZWSP kept · NBSP inside the measure ⇒ Absent | 6 |
| Text | case · whitespace collapse · accents · `Pilao` ≠ `Pilão` · title-embedded brand stays | 6 |
| Collisions | Pilão 500g/1kg · Dove 90g/6un · Coca/Coca Zero · Tio João/Camil · Ovos 12/30 · 6×90g/540g · MASS/VOLUME · GTIN A/B | 9 |
| Equality | `equals` · `hashCode` · cross-basis inequality · result invariants | 3, 4 |
| Levels | basis → level · two bases only · three enum values | 1, 4 |
| Corpus | 15 real Carrefour titles | 9 |

### GTIN vectors (frozen — mirror of design §5.4)

Check digits computed with GS1 mod 10 **and** executed on 2026-09-18. A test that disagrees with this
table is a defect in the code, never in the table.

| Raw | Len | Check digit (last / expected) | Canonical 14 | Expected | Origin |
| --- | --- | --- | --- | --- | --- |
| `96385074` | 8 | 4 / 4 | `00000096385074` | `Valid` | hypothetical |
| `12345670` | 8 | 0 / 0 | `00000012345670` | `Valid` | hypothetical |
| `036000291452` | 12 | 2 / 2 | `00036000291452` | `Valid` | hypothetical |
| `7896006711155` | 13 | 5 / 5 | `07896006711155` | `Valid` | **real**, POC B |
| `7896006744115` | 13 | 5 / 5 | `07896006744115` | `Valid` | **real**, POC B |
| `7898215151708` | 13 | 8 / 8 | `07898215151708` | `Valid` | **real**, POC B |
| `7891910030347` | 13 | 7 / 7 | `07891910030347` | `Valid` | **real**, POC B |
| `7893500020158` | 13 | 8 / 8 | `07893500020158` | `Valid` | **real**, POC A |
| `7893500020110` | 13 | 0 / 0 | `07893500020110` | `Valid` | **real**, POC A |
| `0036000291452` | 13 | 2 / 2 | `00036000291452` | `Valid` — ≡ `036000291452` | hypothetical |
| `00036000291452` | 14 | 2 / 2 | `00036000291452` | `Valid` — ≡ `036000291452` | hypothetical |
| `07896006711155` | 14 | 5 / 5 | `07896006711155` | `Valid` — ≡ `7896006711155` | real, zero-prefixed |
| `17896006711152` | 14 | 2 / 2 | `17896006711152` | `Valid` — ≢ `07896006711155` | hypothetical |
| `96385075` | 8 | 5 / 4 | — | `INVALID_CHECK_DIGIT` | — |
| `036000291453` | 12 | 3 / 2 | — | `INVALID_CHECK_DIGIT` | — |
| `7896006711156` | 13 | 6 / 5 | — | `INVALID_CHECK_DIGIT` | — |
| `17896006711153` | 14 | 3 / 2 | — | `INVALID_CHECK_DIGIT` | — |
| `00000000`, `000000000000`, `0000000000000`, `00000000000000` | 8, 12, 13, 14 | 0 / 0 — **passes** mod 10 | — | `ALL_ZEROS` | — |
| `789600671115a`, `7896-006711155`, leading/trailing space, last char U+0665 | — | — | — | `NON_DIGIT` | — |
| `""`, `1234567`, `123456789`, `12345678901`, `123456789012345` | 0, 7, 9, 11, 15 | — | — | `UNSUPPORTED_LENGTH` | — |
| `12a` / `000` | 3 | — | — | `NON_DIGIT` / `UNSUPPORTED_LENGTH` (first check wins) | — |

## Definition of done

Design §16. In short: design and plan approved; tests written first and green; full suite equal to the
baseline plus the new tests; guards empty; S1 unchanged; integrated through the normal PR flow with PO
authorization. **Only then** S2 = `COMPLETE`. The roadmap, the delivery plan (C2 and C3 reconciliation,
file list) and `CLAUDE.md` are updated in a separate documentation step.

## Risks

| Risk | Mitigation |
| --- | --- |
| Key drift from a future S1a/S1b/pipeline change (G-7) | Versioning deferred; nothing persisted in S2; the real-corpus test turns drift into a red test; decide before S10 |
| Re-parsing sizes in S2 (G-1) | Only S1b spans; guard grep |
| Silent GTIN fallback (G-3) / GTIN key blocked by the auxiliary signature (G-4) | Dedicated tests in Task 8 |
| Branching on S1b ambiguity reasons (G-5) | Reason not forwarded; all-reasons test |
| An S1b behaviour change breaks S2 tests confusingly | Task 6 asserts the S1b result before the S2 result |
| Expectations of cross-source joins by signature | Design §13: false splits accepted; cross-source identity needs GTIN or the future catalog (C4) |

## Out of scope

Persistence, serialization and versioning of keys; HTTP; adapters and sanitization (S9); provenance
and `PriceObservation` (S4); `MarketProduct` catalog and GTIN ↔ signature linkage (C4); local
`Product` → `ProductKey` resolution; stable source ids (C2); category (C3); brand/variant extraction;
fuzzy, learned or LLM matching; any change to S1, the parity fixture or the app.
