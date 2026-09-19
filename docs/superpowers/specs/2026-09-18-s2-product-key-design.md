# S2 — `ProductKey` — Design

**Status: `APPROVED_BY_PO`** (2026-09-18). Built on the product owner's decisions PO-1 to PO-11 of
2026-09-18 (§3.2), which are normative, and on the spec-level choices of §19: SR-1, SR-2, SR-3, SR-4,
SR-7 and SR-8 approved; SR-5 and SR-6 approved as modified. The final PO reconciliation of 2026-09-18
also fixed the identity-level shape (§11), the independence of the GTIN path (§9.3), the result
boundaries (§9.0), the GTIN vectors (§5.4) and the S1 immutability gate (§15 AC12). **S2
implementation: `NOT_STARTED`.** **Approval of this document authorizes no
execution**: implementation still needs the approved plan
(`docs/superpowers/plans/2026-09-18-s2-product-key.md`), its preconditions and an explicit
authorization from the product owner.

Milestone 5, slice S2. **Backend only.** Parent documents, all approved and in the app repository
`../PricePulse`:

- delivery plan `docs/product-development/plans/price-intelligence-foundation-v1.md` §6 S2;
- specification `docs/product-development/specs/price-intelligence-foundation-v1.md` §6.1, §6.2, §6.6,
  §8, §13, §15;
- ADR-015 **D4** (`ProductKey` = GTIN when the source exposes it; else normalized attribute signature
  of brand + canonical name + variant + size + unit + pack count);
- ADR-016 (app-local identity ≠ market canonical identity; only the `MarketTextKey` primitive is a
  cross-repository contract).

S1 is `COMPLETE` (S1a `MarketTextKey`, S1b `UnitNormalization`). F1 is resolved by ADR-016; F3 is
resolved. Evidence for this design comes from the S2 discovery of 2026-09-18, whose probes executed the
compiled S1a/S1b code of backend `origin/main` `4fb9801`.

---

## 1. Context

`PriceObservation` (S4) carries a `productKey`, a `productKeyBasis` and a `productIdentityLevel` (parent
spec §6.1, §6.6). S2 defines that key: the market canonical identity of a product, owned by the backend
(ADR-015 D4), independent of any person's local catalog (ADR-016 D1).

Two sources were investigated in the POCs and they need different paths:

| Source | Identity available | Evidence |
| --- | --- | --- |
| Atacadão | EAN-13 in `items[].ean` | POC A `evidence/atacadao.md`; POC B `data/atacadao-observations.json` (15 EANs, all 13 digits, all with a valid GS1 check digit) |
| Carrefour | title only; no GTIN anywhere | POC A probe Pinheiros: `GTIN Coverage = 0/15`; POC B `data/carrefour-canonical.json` (`name` only) |

No investigated source exposes **brand**, **variant** or **category** as structured fields. The titles
embed the brand (`Arroz Branco Camil Tipo 1 5kg`, `Macarrao Espaguete Carrefour Classic 500g`).

The failure S2 exists to prevent is the **false merge** (delivery plan R-2): two different products
sharing one key make a wrong price look right. S2 therefore refuses whenever it cannot build a key
safely, and accepts **false splits** — two keys for one physical product — as the price of never
merging.

## 2. Scope

**IN_SCOPE:**

- `Gtin` value object — parsing, GS1 validation, canonical 14-digit form;
- `AttributeSignature` value object;
- `ProductKey` (two variants), `ProductKeyBasis`, `ProductIdentityLevel`;
- the deterministic canonicalization pipeline from a market title to a signature (PO-1, PO-2);
- GTIN precedence and the best-effort auxiliary signature (PO-6, PO-7);
- the sealed resolution result and its fail-closed semantics (PO-5, PO-11);
- pure unit tests.

**OUT_OF_SCOPE:**

| Not S2 | Where it belongs / why |
| --- | --- |
| Postgres, migrations | S10 |
| HTTP, OpenAPI, JSON Schema | S12 |
| Key serialization (string, column, hash) | S10/S12 (PO-10) |
| Key-rule versioning | `DEFERRED_OPEN_DECISION` (§14) |
| Source adapters; HTML decoding; internal NBSP; source sanitization | S9 (C5, §18) |
| `PriceObservation`, provenance, raw-title storage | S4 |
| `ReferencePrice`, `PriceEvent` | S7, S8 |
| `UserTrackedProduct` | S11 |
| Local `Product` → `ProductKey` resolution | `OPEN_DECISION` (ADR-016 open items 1–2) |
| `MarketProduct` catalog; GTIN ↔ signature linkage | `OPEN_DECISION`, no owning slice (C4, §18) |
| Stable source product ids as identity | slice that owns provenance (C2, §18) |
| Brand, variant or category extraction from the title | never heuristic (PO-4, PO-9) |
| Display name | S4 / catalog (PO-3) |
| Fuzzy matching, learned aliases, LLM matching | not in M5 Foundation |
| Commercial source integration | ADR-015 D8; every source is `COMMERCIAL_USE_NOT_VALIDATED` |
| Any change to `MarketTextKey`, `UnitNormalization`, the parity fixture or the app | PO-1; ADR-016 |

## 3. Normative dependencies

### 3.1 Documents and code

| Dependency | What S2 takes from it |
| --- | --- |
| ADR-015 D4 | the two bases and the six signature components |
| ADR-016 D1–D4 | market identity is backend-only; only `MarketTextKey ≡ ProductNameMatchKey` is a cross-repo contract; `Product` → `ProductKey` stays open |
| Parent spec §6.2 | three identity levels; «brand different is not identity»; `AMBIGUOUS_IDENTITY` is never compared (§8.14) |
| S1a — `MarketTextKey.from(String): MarketTextKey` | the only text primitive. Private constructor; throws `IllegalArgumentException` on blank (Kotlin `trim()` then empty) |
| S1b — `normalizePackageMeasure(raw: String): PackageMeasureResult` | the only package-measure parser. `Normalized(measure, spans)` · `Absent` · `Ambiguous(reason)`; never throws; spans index its input, ascending, non-overlapping; every `Ambiguous` must be treated alike (S1b design §8) |
| S1b — `NormalizedPackageMeasure(quantity: Long, measureUnit: MeasureUnit, packCount: Int)` | the measure component, used as is |

### 3.2 Product owner decisions (2026-09-18) — normative

| # | Decision |
| --- | --- |
| PO-1 | Pipeline: `rawTitle` → `rawTitle.trim()` = `analysisInput` → `normalizePackageMeasure(analysisInput)` → remove recognized spans from `analysisInput` → `MarketTextKey` of the rest. S1a and S1b unchanged. The raw title never enters the key |
| PO-2 | Spans refer to `analysisInput`. Each span is replaced by one ASCII space, then `MarketTextKey` is applied. No punctuation cleanup, no stopword removal, no measure re-interpretation, no removal of words such as `com`, no NLP or fuzzy matching. Empty remainder ⇒ no signature key |
| PO-3 | Canonical product name is identity, not display: the `MarketTextKey` after measure-span removal. Display name is out of scope |
| PO-4 | Brand and variant are consumed only when supplied structured; never extracted from the title. Both optional; absence is an explicit state, different from any present value. A brand present in the title is not removed from the canonical name |
| PO-5 | Signature path: `Normalized` is required; `Absent` ⇒ no key; `Ambiguous` ⇒ ambiguous, no key. No fallback |
| PO-6 | `Gtin` value object; GTIN-8/12/13/14; digits only; GS1 check digit validated; canonical 14 digits, left-padded with zeros; reject unsupported length, non-digits, bad check digit, all zeros. A present but invalid GTIN yields no key and **no fallback** to the signature. A valid GTIN yields basis `GTIN`, level `EXACT_IDENTITY` |
| PO-7 | With a valid GTIN, S2 **tries** to compute the signature too. It is not part of the GTIN key's equality and changes neither basis nor level. **Best effort:** its failure, for any reason, never invalidates the GTIN key |
| PO-8 | `GTIN` ⇒ `EXACT_IDENTITY`; `ATTRIBUTE_SIGNATURE` ⇒ `ATTRIBUTE_IDENTITY`. No `SOURCE_ID` basis and no third form of key. Stable source ids are out of scope |
| PO-9 | Category is not a key component. The delivery plan's RED «categoria diferente → nunca casa» is obsolete and incompatible with the current contract; it is removed, not satisfied by a category field |
| PO-10 | `ProductKey` is a structured value object with structural `equals`/`hashCode`; no canonical string in S2. It must be unambiguous and serializable later |
| PO-11 | Resolution from external data returns a sealed result — resolved, or an expected-data non-resolution. No exception for expected external data (invalid GTIN, `Absent`, `Ambiguous`, empty name). `require` stays for internal invariants of value objects. Final names follow the backend's conventions (§4.6) |

## 4. Domain model

Package `com.tonycorreia.pricepulsebackend.application.priceintelligence.identity`. Conceptual Kotlin,
**not authorized code**; names are proposed under PO-11 and frozen at the plan's freeze point.

### 4.1 Files

| File | Holds |
| --- | --- |
| `Gtin.kt` | `Gtin`, `GtinParseResult`, `GtinRejection` |
| `AttributeSignature.kt` | `AttributeSignature`; the internal signature derivation (§7, §8) |
| `ProductIdentityLevel.kt` | `ProductIdentityLevel` |
| `ProductKey.kt` | `ProductKey`, `ProductKeyBasis`, `ProductKeyResult`, `UnresolvedReason`, `InputRejection`, `resolveProductKey` |

The delivery plan names `ProductKey.kt`, `ProductIdentityLevel.kt` and `AttributeSignature.kt`. `Gtin.kt`
is added by PO-6 (the value object needs its own file: one file, one responsibility, plan §4).

### 4.2 Types

```kotlin
/** GTIN in canonical form: exactly 14 ASCII digits, left-padded with zeros, valid GS1 check digit. */
@JvmInline
value class Gtin private constructor(val value: String) {
    init { require(value.length == 14 && value.all { it in '0'..'9' }) }
    companion object { fun parse(raw: String): GtinParseResult }
}

sealed interface GtinParseResult {
    data class Valid(val gtin: Gtin) : GtinParseResult
    data class Invalid(val rejection: GtinRejection) : GtinParseResult
}

/** Declaration order is the reporting order (as PackageMeasureAmbiguity). Diagnostic only. */
enum class GtinRejection { NON_DIGIT, UNSUPPORTED_LENGTH, ALL_ZEROS, INVALID_CHECK_DIGIT }

/** Market attribute signature (ADR-015 D4). Absent brand/variant are null: explicit, never equal to a value. */
data class AttributeSignature(
    val brand: MarketTextKey?,
    val canonicalName: MarketTextKey,
    val variant: MarketTextKey?,
    val packageMeasure: NormalizedPackageMeasure
)

enum class ProductKeyBasis(val identityLevel: ProductIdentityLevel) {
    GTIN(ProductIdentityLevel.EXACT_IDENTITY),
    ATTRIBUTE_SIGNATURE(ProductIdentityLevel.ATTRIBUTE_IDENTITY)
}

enum class ProductIdentityLevel { EXACT_IDENTITY, ATTRIBUTE_IDENTITY, AMBIGUOUS_IDENTITY }

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

/** No identity level on the interface: only the variants that carry one declare it (§11). */
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
        val identityLevel: ProductIdentityLevel get() = key.basis.identityLevel   // EXACT or ATTRIBUTE

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

    /** Structurally invalid input (PO-6, SR-1, blank title). Carries no identity level (§11). */
    data class Rejected(val reason: InputRejection) : ProductKeyResult
}

enum class UnresolvedReason { PACKAGE_MEASURE_ABSENT, PACKAGE_MEASURE_AMBIGUOUS, EMPTY_CANONICAL_NAME }

sealed interface InputRejection {
    data class InvalidGtin(val rejection: GtinRejection) : InputRejection
    data object BlankTitle : InputRejection
    data object BlankBrand : InputRejection
    data object BlankVariant : InputRejection
}

/** The only entry point for external data. Pure, deterministic, never throws. */
fun resolveProductKey(title: String, gtin: String?, brand: String?, variant: String?): ProductKeyResult
```

### 4.3 Inputs

| Parameter | Meaning | Absent | Present but blank |
| --- | --- | --- | --- |
| `title` | the market source's product title, as delivered | — (required parameter) | `Rejected(BlankTitle)` on the signature path (§9) |
| `gtin` | the source's GTIN field, raw | `null` ⇒ signature path | `""` is **present**: `Rejected(InvalidGtin(UNSUPPORTED_LENGTH))` |
| `brand` | structured brand from the source | `null` ⇒ absent | `Rejected(BlankBrand)` on the signature path (§19 SR-1) |
| `variant` | structured variant from the source | `null` ⇒ absent | `Rejected(BlankVariant)` on the signature path (§19 SR-1) |

Mapping «the source has no such field» to `null` is the adapter's job (S9). S2 never trims, decodes or
repairs `gtin`, `brand` or `variant`.

### 4.4 Construction paths

- `Gtin` — private constructor; created only by `Gtin.parse`. Its `init` re-checks the canonical shape.
- `AttributeSignature` — public constructor over already-validated types (`MarketTextKey`,
  `NormalizedPackageMeasure`), like `NormalizedPackageMeasure`. Tests build expected values with it.
  **The only path from external data is `resolveProductKey`.**
- `ProductKey` — **`ProductKey.fromGtin(gtin)` and `ProductKey.fromAttributes(signature)` are the public
  construction API**, exactly as the delivery plan (§6 S2 «Produz») names them, and they return the
  abstract `ProductKey`, so consumers never have to name a variant to build a key. `GtinKey` and
  `AttributeKey` exist as structural variants for `is`/`when` matching; their constructors are
  `internal`, with `@ConsistentCopyVisibility` so that `copy()` is not more visible than the
  constructor, and the two factories are their **only** call sites (plan guard grep). The factories
  are where construction is centralized; the component invariants live in the component types (`Gtin`,
  `MarketTextKey`, `NormalizedPackageMeasure`) (§19 SR-5).
- `ProductKeyResult.Resolved` — two shapes, `ByGtin(gtin, auxiliaryAttributeSignature)` and
  `ByAttributes(signature)`, with `internal` constructors used in production code only by
  `resolveProductKey` (tests in the same package may build expected values with them). Each stores its
  identity material once; `key` is derived through the factories, and `attributeSignature` is derived
  (`ByAttributes` → its `signature`; `ByGtin` → `auxiliaryAttributeSignature`). **No `init` is needed**:
  an `AttributeKey` result with a second, different signature has no field to exist in (§19 SR-6).
- `@ConsistentCopyVisibility` is present in `kotlin-stdlib` 2.2.10, the backend's version
  (`build.gradle.kts`: `kotlin("jvm") version "2.2.10"`; `kotlin/ConsistentCopyVisibility.class` in
  the cached `kotlin-stdlib-2.2.10.jar`).

### 4.5 What the model deliberately does not contain

No `category`, no `displayName`, no raw title, no spans, no `sourceProductId`, no string form, no
version field, no hash. None is a component of ADR-015 D4 or a PO-approved S2 concern.

### 4.6 Naming against backend conventions (PO-11)

| Convention observed on `origin/main` | S2 follows |
| --- | --- |
| Result types end in `Result`: `PackageMeasureResult`, `StartReceiptAnalysisResult`, `ReceiptUploadResult` | `ProductKeyResult`, `GtinParseResult` |
| Success variant `Resolved` (`StartReceiptAnalysisResult.Resolved`) | `ProductKeyResult.Resolved` |
| Invalid-input variant `Rejected(rejection)` (`ReceiptUploadResult.Rejected`) | `ProductKeyResult.Rejected(reason)` |
| Diagnostic enum whose declaration order is the reporting order (`PackageMeasureAmbiguity`) | `GtinRejection`, `UnresolvedReason` |
| Id-like value class, private constructor + factory (`MarketTextKey`), `require` for shape (`ContentHash`) | `Gtin` |
| Construction invariants in `init` (`NormalizedPackageMeasure`) | `Gtin` (canonical shape) |
| Explicit factories named by the approved plan (`MarketTextKey.from`, delivery plan `ProductKey.fromGtin`/`fromAttributes`) | `ProductKey.fromGtin`, `ProductKey.fromAttributes` |
| Impossible states excluded by the type rather than checked (`PackageMeasureResult` variants carry only their own data) | `Resolved.ByGtin` / `Resolved.ByAttributes` |

## 5. GTIN contract (PO-6)

### 5.1 Parsing — `Gtin.parse(raw)`, checks in this order

| # | Check | Rejection |
| --- | --- | --- |
| G1 | every character is an ASCII digit `0`–`9` (not `Char.isDigit`, which accepts other scripts' digits) | `NON_DIGIT` |
| G2 | length ∈ {8, 12, 13, 14} | `UNSUPPORTED_LENGTH` (includes `""`) |
| G3 | not all zeros | `ALL_ZEROS` |
| G4 | GS1 mod-10 check digit | `INVALID_CHECK_DIGIT` |

The first failing check is reported; the rejection is diagnostic only. **No trimming**: `" 7896006711155"`
is `NON_DIGIT`.

### 5.2 Canonical form

`value = raw.padStart(14, '0')`. The check digit is computed over the padded string: positions 1–13
from the left weighted 3, 1, 3, …, 3; check = (10 − sum mod 10) mod 10. Left padding never changes the
sum, so the check is the same for every length.

Consequences:

- `036000291452` (GTIN-12) ≡ `0036000291452` (GTIN-13) ≡ `00036000291452` (GTIN-14): one `Gtin`;
- `96385074` (GTIN-8) is `00000096385074`;
- the indicator digit of a GTIN-14 is significant: `17896006711152` ≠ `07896006711155`.

### 5.3 Why all zeros is an explicit rule

`00000000` and `00000000000000` **pass** the GS1 check (sum 0, check 0) — verified during planning.
Without G3 a placeholder value would become a product identity shared by everything that uses it.

### 5.4 Test vectors (frozen)

Every check digit below was computed with the GS1 mod-10 algorithm of §5.2 **and** re-verified by
executing that algorithm on 2026-09-18; none is taken from prose. «Expected check digit» is the digit the
algorithm computes from the first *n − 1* digits.

**Valid**

| Raw | Length | Last digit | Expected check digit | Canonical (14) | Result | Origin |
| --- | --- | --- | --- | --- | --- | --- |
| `96385074` | 8 | 4 | 4 | `00000096385074` | `Valid` | hypothetical |
| `12345670` | 8 | 0 | 0 | `00000012345670` | `Valid` | hypothetical |
| `036000291452` | 12 | 2 | 2 | `00036000291452` | `Valid` | hypothetical |
| `7896006711155` | 13 | 5 | 5 | `07896006711155` | `Valid` | **real**, POC B `atacadao-observations.json` |
| `7896006744115` | 13 | 5 | 5 | `07896006744115` | `Valid` | **real**, POC B |
| `7898215151708` | 13 | 8 | 8 | `07898215151708` | `Valid` | **real**, POC B |
| `7891910030347` | 13 | 7 | 7 | `07891910030347` | `Valid` | **real**, POC B |
| `7893500020158` | 13 | 8 | 8 | `07893500020158` | `Valid` | **real**, POC A `evidence/atacadao.md` |
| `7893500020110` | 13 | 0 | 0 | `07893500020110` | `Valid` | **real**, POC A |
| `0036000291452` | 13 | 2 | 2 | `00036000291452` | `Valid` | hypothetical |
| `00036000291452` | 14 | 2 | 2 | `00036000291452` | `Valid` | hypothetical |
| `07896006711155` | 14 | 5 | 5 | `07896006711155` | `Valid` | real EAN, zero-prefixed |
| `17896006711152` | 14 | 2 | 2 | `17896006711152` | `Valid` | hypothetical (indicator digit 1) |

**Equivalences:** `036000291452` ≡ `0036000291452` ≡ `00036000291452` (one `Gtin`);
`7896006711155` ≡ `07896006711155`. **Non-equivalence:** `17896006711152` ≢ `07896006711155`
(indicator digit).

**Invalid**

| Raw | Length | Why | Expected check digit | Result |
| --- | --- | --- | --- | --- |
| `96385075` | 8 | last digit 5 | 4 | `Invalid(INVALID_CHECK_DIGIT)` |
| `036000291453` | 12 | last digit 3 | 2 | `Invalid(INVALID_CHECK_DIGIT)` |
| `7896006711156` | 13 | last digit 6 | 5 | `Invalid(INVALID_CHECK_DIGIT)` |
| `17896006711153` | 14 | last digit 3 | 2 | `Invalid(INVALID_CHECK_DIGIT)` |
| `00000000` | 8 | all zeros — **passes** mod 10 | 0 | `Invalid(ALL_ZEROS)` |
| `000000000000` | 12 | all zeros — passes mod 10 | 0 | `Invalid(ALL_ZEROS)` |
| `0000000000000` | 13 | all zeros — passes mod 10 | 0 | `Invalid(ALL_ZEROS)` |
| `00000000000000` | 14 | all zeros — passes mod 10 | 0 | `Invalid(ALL_ZEROS)` |
| `789600671115a` | 13 | letter | — | `Invalid(NON_DIGIT)` |
| `7896-006711155` | 14 | hyphen | — | `Invalid(NON_DIGIT)` |
| ` 7896006711155`, `7896006711155 ` | 14 | space (never trimmed, SR-3) | — | `Invalid(NON_DIGIT)` |
| `789600671115\u0665` (last char U+0665) | 13 | non-ASCII digit (SR-4) | — | `Invalid(NON_DIGIT)` |
| `""` | 0 | empty but present (SR-3) | — | `Invalid(UNSUPPORTED_LENGTH)` |
| `1234567`, `123456789`, `12345678901`, `123456789012345` | 7, 9, 11, 15 | length | — | `Invalid(UNSUPPORTED_LENGTH)` |
| `12a` | 3 | non-digit **and** length | — | `Invalid(NON_DIGIT)` — G1 first (SR-8) |
| `000` | 3 | length **and** all zeros | — | `Invalid(UNSUPPORTED_LENGTH)` — G2 first (SR-8) |

## 6. AttributeSignature contract

| Component | Required | Source | Canonical representation | Absence |
| --- | --- | --- | --- | --- |
| `brand` | no | structured `brand` input only (PO-4) | `MarketTextKey.from(brand)` (§19 SR-7) | `null` |
| `canonicalName` | **yes** | the title, through §7–§8 | `MarketTextKey` of the remainder (PO-3) | no signature (`EMPTY_CANONICAL_NAME`) |
| `variant` | no | structured `variant` input only (PO-4) | `MarketTextKey.from(variant)` (§19 SR-7) | `null` |
| `packageMeasure` | **yes** | `normalizePackageMeasure(analysisInput)` | `NormalizedPackageMeasure` as returned: `quantity`, `measureUnit`, `packCount` | no signature (PO-5) |

- `packCount = 1` is part of equality explicitly: `(90, MASS, 1)` ≠ `(90, MASS, 6)`.
- `COUNT` is an ordinary `MeasureUnit`: `Ovos 12 unidades` → `(12, COUNT, 1)`. No special case.
- A brand written in the title stays in `canonicalName`; a structured brand is **added**, never
  subtracted from the name (PO-4). No component is derived from another.
- Brand and variant are keyed with the same primitive as the name so that «one notion of text
  equality» (ADR-012 D7, ADR-016 D3) also holds for them.

## 7. Canonicalization pipeline (PO-1)

```text
title
  └─ analysisInput = title.trim()                 Kotlin String.trim(): the MarketTextKey border rule
       └─ normalizePackageMeasure(analysisInput)   S1b, unchanged
            ├─ Absent      → Unresolved(PACKAGE_MEASURE_ABSENT)
            ├─ Ambiguous   → Unresolved(PACKAGE_MEASURE_AMBIGUOUS)     (reason never inspected)
            └─ Normalized(measure, spans)
                 └─ remainder = analysisInput with each span replaced by " "     (§8)
                      ├─ remainder.isBlank() → Unresolved(EMPTY_CANONICAL_NAME)
                      └─ canonicalName = MarketTextKey.from(remainder)
```

- `trim()` is **Kotlin's** `String.trim()` — the function `MarketTextKey.from` itself calls. Never
  `java.lang.String.trim()` or `strip()`: neither removes NBSP (S1a plan R-1.5).
- `isBlank()` tests the same character set as Kotlin `trim()` (`Char.isWhitespace`), so a
  non-blank remainder can never make `MarketTextKey.from` throw.
- The raw title is used only as the pipeline's input. It is not stored, not part of the key and not
  in the result. Keeping it for provenance is S4's job (PO-1).

**Why `trim()` first (discovery evidence).** `U+00A0 Cafe 500G U+00A0` gives `Absent` when S1b reads
the raw title, because S1b's tokens are split by `\p{javaWhitespace}` only and `500G` + NBSP is one
token. U+202F and U+2007 behave the same; EM SPACE does not (it is `javaWhitespace`). After `trim()`
it gives `(500, MASS, 1)`, name `cafe`. Over the 133 evidenced and constructed titles of the
discovery, this pipeline and «`MarketTextKey` first, then S1b on the key» produced identical results
in 133 of 133. This pipeline was chosen because its spans keep indexing the source text with its
original spelling, as S1b D2 and §6.6 intended, and because `MarketTextKey` is applied only once.

## 8. Span removal contract (PO-2)

1. Spans come from `PackageMeasureResult.Normalized.spans` and index `analysisInput`. S2 uses them as
   given: it never searches for, widens, narrows or re-parses a measure.
2. Each span is replaced by exactly one ASCII space (U+0020). Replacement is applied from the last
   span to the first so that earlier indexes stay valid.
3. Everything outside the spans is kept byte for byte: punctuation, stopwords (`com`), numbers that
   are not measures (`Tipo 1`, `252 Graus`), accents, ZWSP, internal NBSP.
4. `MarketTextKey.from` then trims, collapses `\p{javaWhitespace}` runs and lowercases.

| Title | Spans (S1b) | Canonical name | Measure |
| --- | --- | --- | --- |
| `Dove 90g 6un` | `90g`, `6un` | `dove` | `(90, MASS, 6)` |
| `Dove 6 x 90 g` | `6 x 90 g` (one span) | `dove` | `(90, MASS, 6)` |
| `5kg Arroz Camil` | `5kg` (start) | `arroz camil` | `(5000, MASS, 1)` |
| `Arroz 5 kg Camil` | `5 kg` (middle) | `arroz camil` | `(5000, MASS, 1)` |
| `Arroz Branco 5KG` | `5KG` (end) | `arroz branco` | `(5000, MASS, 1)` |
| `Leite Integral 1 L` | `1 L` | `leite integral` | `(1000, VOLUME, 1)` |
| `Café Pilão 500G` | `500G` | `café pilão` | `(500, MASS, 1)` |
| `VAGEM EMB / 250G` | `250G` | `vagem emb /` | `(250, MASS, 1)` |
| `Arroz - 5kg` | `5kg` | `arroz -` | `(5000, MASS, 1)` |
| `Papel Higienico Mimmo Folha Dupla com 4 Rolos` | `4 Rolos` | `papel higienico mimmo folha dupla com` | `(4, COUNT, 1)` |
| `500g` | `500g` | — | `Unresolved(EMPTY_CANONICAL_NAME)` |
| `6un Dove 90g` | — | — | `Unresolved(PACKAGE_MEASURE_AMBIGUOUS)` (S1b: count not next to the size) |

All values above were observed by executing the real S1b and `MarketTextKey` during discovery.

## 9. Result and failure semantics (PO-5, PO-6, PO-7, PO-11)

### 9.0 Result boundaries

| Variant | Meaning | Carries |
| --- | --- | --- |
| `Resolved` | an identity **is established** | a key, its level, the (auxiliary) signature |
| `Unresolved` | the input is **acceptable** — every value supplied is well-formed — but it does not contain enough to establish an identity safely | a diagnostic `UnresolvedReason`; level `AMBIGUOUS_IDENTITY` |
| `Rejected` | the input is **structurally invalid**: a value that was supplied has a form S2 cannot accept | a diagnostic `InputRejection`; **no** identity level |

Deciding cases:

| Case | Variant | Why |
| --- | --- | --- |
| GTIN present and invalid | `Rejected(InvalidGtin)` | the supplied GTIN is malformed (PO-6) |
| no GTIN, measure `Absent` | `Unresolved(PACKAGE_MEASURE_ABSENT)` | a title without a recognizable size is well-formed text; it just lacks a required component (PO-5) |
| no GTIN, measure `Ambiguous` | `Unresolved(PACKAGE_MEASURE_AMBIGUOUS)` | S1b refused to interpret well-formed text (PO-5) |
| no GTIN, canonical name empty (`500g`) | **`Unresolved(EMPTY_CANONICAL_NAME)`** | the title is non-blank and well-formed; after the measure is taken out, no name is left to identify *which* product. That is insufficient identity, not malformed input. PO-2 words it as «não há ProductKey por assinatura» |
| no GTIN, blank title | `Rejected(BlankTitle)` | a title was supplied with no content at all |
| no GTIN, structured brand/variant blank | `Rejected(BlankBrand / BlankVariant)` | SR-1 (approved): a supplied blank value is invalid; it is never turned into `null` |
| valid GTIN, structured brand/variant blank | **`Resolved.ByGtin(auxiliaryAttributeSignature = null)`** | §9.3: invalid metadata only disables the auxiliary signature |

### 9.1 Algorithm of `resolveProductKey(title, gtin, brand, variant)`

```text
if gtin != null:                                     GTIN path — evaluated FIRST, alone
    Gtin.parse(gtin)
      Invalid(r) → Rejected(InvalidGtin(r))          no fallback (PO-6); nothing else is evaluated
      Valid(g)   → identity established here, final
                   aux = signaturePath(title, brand, variant)
                   → Resolved.ByGtin(g, auxiliaryAttributeSignature = (aux as? Resolved)?.attributeSignature)
else:                                                signature path
    signaturePath(title, brand, variant)

signaturePath, first failing step wins:
    title.isBlank()                        → Rejected(BlankTitle)
    brand != null && brand.isBlank()       → Rejected(BlankBrand)
    variant != null && variant.isBlank()   → Rejected(BlankVariant)
    pipeline of §7                         → Unresolved(...) or AttributeSignature s
    s                                      → Resolved.ByAttributes(s)
```

There is one signature derivation, `signaturePath`, used by both paths. On the GTIN path its outcome
is **only ever read** to fill `auxiliaryAttributeSignature`; it is never returned. The keys of both
shapes come from `ProductKey.fromGtin` / `ProductKey.fromAttributes` (§4.4).

### 9.2 Fail-closed matrix (frozen)

| Input | Result | Key | Level |
| --- | --- | --- | --- |
| valid GTIN | `Resolved.ByGtin` | `GtinKey` | `EXACT_IDENTITY` |
| valid GTIN, signature derivable | `Resolved.ByGtin`, `auxiliaryAttributeSignature` set | `GtinKey` | `EXACT_IDENTITY` |
| valid GTIN, signature fails for **any** reason (§9.3) | `Resolved.ByGtin`, `auxiliaryAttributeSignature = null` | `GtinKey` | `EXACT_IDENTITY` |
| GTIN present and invalid (any `GtinRejection`, including `""`), whatever the other inputs | `Rejected(InvalidGtin)` | none — **no signature fallback** | — (none) |
| no GTIN, complete signature | `Resolved.ByAttributes` | `AttributeKey` | `ATTRIBUTE_IDENTITY` |
| no GTIN, brand absent (`null`) | `Resolved.ByAttributes` | `AttributeKey`, `brand = null` | `ATTRIBUTE_IDENTITY` |
| no GTIN, variant absent (`null`) | `Resolved.ByAttributes` | `AttributeKey`, `variant = null` | `ATTRIBUTE_IDENTITY` |
| no GTIN, measure `Absent` | `Unresolved(PACKAGE_MEASURE_ABSENT)` | none | `AMBIGUOUS_IDENTITY` |
| no GTIN, measure `Ambiguous` (every reason alike) | `Unresolved(PACKAGE_MEASURE_AMBIGUOUS)` | none | `AMBIGUOUS_IDENTITY` |
| no GTIN, canonical name empty | `Unresolved(EMPTY_CANONICAL_NAME)` | none | `AMBIGUOUS_IDENTITY` |
| no GTIN, blank title | `Rejected(BlankTitle)` | none | — (none) |
| no GTIN, blank brand or variant | `Rejected(BlankBrand / BlankVariant)` | none | — (none) |
| no GTIN, zero or overflowing measure | `Unresolved(PACKAGE_MEASURE_AMBIGUOUS)` — inherited from S1b `ZERO_QUANTITY` / `OUT_OF_RANGE` | none | `AMBIGUOUS_IDENTITY` |

### 9.3 Independence of the GTIN path (frozen)

**A valid GTIN establishes the identity by itself.** Once `Gtin.parse` returns `Valid`, the result is
`Resolved.ByGtin` (key `GtinKey`, `EXACT_IDENTITY`), and **nothing** about the title, the canonical
name, the package measure, the structured brand, the structured variant or the auxiliary signature can
turn it into `Unresolved` or `Rejected`.

| Valid GTIN plus | Result |
| --- | --- |
| blank title | `Resolved.ByGtin(auxiliaryAttributeSignature = null)` (SR-2) |
| measure `Absent` | `Resolved.ByGtin(auxiliaryAttributeSignature = null)` |
| measure `Ambiguous` (any reason) | `Resolved.ByGtin(auxiliaryAttributeSignature = null)` |
| canonical name empty | `Resolved.ByGtin(auxiliaryAttributeSignature = null)` |
| blank structured brand | `Resolved.ByGtin(auxiliaryAttributeSignature = null)` — the blank brand is **not** converted to `null` to rescue the signature (SR-1) |
| blank structured variant | `Resolved.ByGtin(auxiliaryAttributeSignature = null)` — same |
| everything well-formed | `Resolved.ByGtin(auxiliaryAttributeSignature = <signature>)` |

The auxiliary signature never takes part in `GtinKey` equality or hash (it is not a field of the key),
never changes the basis and never changes the level: both are derived from the key alone.

The distinction the PO asked for:

- **Invalid overall external input** is decided only by the fields that would carry the identity: the
  GTIN when one is supplied; the title, brand and variant when there is no GTIN. It yields `Rejected`.
- **Invalid auxiliary metadata** is any defect in title, brand or variant on the GTIN path. Those fields
  only feed the auxiliary signature, so their defect only removes that signature
  (`auxiliaryAttributeSignature = null`); it is never reported as a rejection and never destroys the GTIN
  identity.

Order guarantee: the GTIN is parsed **before** any signature step runs, and a signature step can never
short-circuit the GTIN path. An implementation that derives the signature first and returns its
failure is non-conforming (plan Task 8 tests it).

**Diagnostic not carried.** On the GTIN path, why the auxiliary signature is absent is not recorded:
no normative consumer needs it, and adding a field would put auxiliary data into the result's contract
without a decision. If a future slice needs it, it is an additive change.

### 9.4 Reasons are diagnostic

`UnresolvedReason`, `InputRejection` and `GtinRejection` exist for logs and tests. Downstream code
must treat every non-`Resolved` result alike: no key, never compared (parent spec §8.14). S2 never
forwards S1b's `PackageMeasureAmbiguity`, so nobody can branch on it (S1b design §8).

### 9.5 Exceptions

`resolveProductKey` and `Gtin.parse` never throw for any input. `require` only guards the one
value-object invariant external data cannot reach through these functions: the canonical shape of
`Gtin`. The consistency between a key and its signature is not checked at run time: the shapes of
§4.2 make the inconsistent state unrepresentable (SR-6).

## 10. Equality and hash semantics (PO-10)

| Rule | Consequence |
| --- | --- |
| same canonical GTIN ⇒ same `GtinKey` | titles, brands, measures and the auxiliary signature are ignored |
| different canonical GTINs ⇒ different keys | even with identical titles |
| `GtinKey` ≠ `AttributeKey` | always, even for the same physical product (C4) |
| same structural signature ⇒ same `AttributeKey` | `brand` (null or key) · `canonicalName` · `variant` (null or key) · `quantity` · `measureUnit` · `packCount` |
| `hashCode` consistent with `equals` | generated by `data class` / `value class`; no hand-written `equals` |

- The auxiliary signature lives in `ProductKeyResult.Resolved.ByGtin`, **not** in `ProductKey`, so it
  can never influence key equality or hash.
- `GtinKey` and `AttributeKey` are data classes over a single field; `ProductKey.fromGtin(g) ==
  ProductKey.fromGtin(g)` and `ProductKey.fromAttributes(s) == ProductKey.fromAttributes(s)` hold
  structurally, whichever call built them.
- **Unambiguous and serializable later:** every component is a closed enum, an integer, a canonical digit
  string or a `MarketTextKey` value; absence is `null`, which no `MarketTextKey` can equal. A future
  encoding only has to keep the two forms apart and absence distinct from a value. Choosing that
  encoding is S10/S12's job.
- `toString()` is the generated one. Market product names are not personal data.

## 11. ProductIdentityLevel semantics (PO-8)

`ProductIdentityLevel` describes **an identity attempt that produced a classification**. It is not
decoration for every result variant.

| Result | Level | Normative source |
| --- | --- | --- |
| `Resolved.ByGtin` (`GtinKey`) | `EXACT_IDENTITY` | PO-8; parent spec §6.2 («GTIN igual»); delivery plan S2 RED «GTIN igual → `EXACT_IDENTITY`» |
| `Resolved.ByAttributes` (`AttributeKey`) | `ATTRIBUTE_IDENTITY` | PO-8; parent spec §6.2 («assinatura canônica idêntica»); delivery plan S2 RED |
| `Unresolved` | `AMBIGUOUS_IDENTITY` | delivery plan S2 RED (lines 229–230): «entrada insuficiente → `AMBIGUOUS_IDENTITY`»; parent spec §6.2 «`AMBIGUOUS_IDENTITY` — não resolvido»; S1b design §8: `Ambiguous` measure → «expected: `AMBIGUOUS_IDENTITY`» |
| `Rejected` | **none** | no normative text assigns a level to invalid input. «Entrada insuficiente» is input that is well-formed but not enough (= `Unresolved`), not malformed input |

- The level is **computed** — from the basis for `Resolved`, constant for `Unresolved` — never stored
  beside the key, so the two cannot disagree.
- The level is declared **per variant**, not on `ProductKeyResult`, so `Rejected` is not forced to carry
  a value it has no meaning for.
- The enum keeps all three values, as the delivery plan (§6 S2 «Produz») and parent spec §6.1 require:
  `AMBIGUOUS_IDENTITY` is also a value S4 may persist on an observation (C1, deferred).
- «`productKeyBasis` sobrevive em todos os casos» (delivery plan RED) is read as «in every case that
  has a key»: the basis is the form of the key, so it cannot be lost. `Unresolved` and `Rejected` have no
  key and hence no basis.
- A consumer that needs a level for any result (for instance S4, if C1 is resolved towards persisting
  unresolved observations) maps `Rejected` explicitly. S2 does not pre-empt that decision.

## 12. Collision guarantees

These are **structural** guarantees: they hold because a component differs.

| Must not collide | Separating component | Guarantee |
| --- | --- | --- |
| `Cafe Pilao 500g` vs `Cafe Pilao 1kg` | `quantity` | structural |
| `Dove 90g` vs `Dove 90g 6un` | `packCount` | structural |
| `Sabonete 6 x 90g` vs `Sabonete 540g` | `quantity`, `packCount` | structural (S1b never multiplies) |
| `Ovos 12 unidades` vs `Ovos 30 unidades` | `quantity` (COUNT) | structural |
| `Leite 1L` vs `Leite 1kg` | `measureUnit` | structural |
| GTIN A vs GTIN B | `gtin` | structural |
| same text, structured brand `A` vs `B` | `brand` | structural |
| same text, structured brand `A` vs absent | `brand` (null ≠ key) | structural |
| same text, variant present vs absent | `variant` | structural |

And these hold **only because the titles differ as written**:

| Must not collide | Why distinct here | Not guaranteed when |
| --- | --- | --- |
| `Coca-Cola 2L` vs `Coca-Cola Zero 2L` | `zero` is in one title | a source omits the variant word from the title and supplies no structured variant |
| `Arroz Tio Joao 5kg` vs `Arroz Camil 5kg` | brand written in the title | two titles omit the brand and no structured brand is supplied |
| `Refrigerante Fanta Guarana 2L` vs `Refrigerante Coca-Cola 2L` | different words | **category** is never compared: two products of different categories with identical titles and measures would collide (PO-9) |

S2 **does not** claim that different categories always yield different keys (PO-9).

## 13. Known false-split risks (accepted)

False splits are the fail-safe direction: one product with two keys never produces a wrong price, only
a missed match.

| Case | Keys | Cause |
| --- | --- | --- |
| `Cafe Pilao 500g` vs `Café Pilão 500g` | distinct | accents preserved (S1a, ADR-016) |
| `Cafe Pilao 500g` vs `Pilao Cafe 500g` | distinct | word order preserved |
| `Refrigerante Coca-Cola Garrafa 2 L` vs `Coca-Cola 2L` | distinct | descriptive words kept |
| `Arroz - 5kg` vs `Arroz 5kg` | distinct | punctuation kept (PO-2) |
| `… com 4 Rolos` vs `… 4 Rolos` | distinct | `com` kept (PO-2) |
| `Arroz Camil 5kg` (NBSP inside) vs `Arroz Camil 5kg` | distinct | internal NBSP is not collapsed; S9 sanitizes |
| `Cafe 500 g` (NBSP between number and unit) | `Unresolved(PACKAGE_MEASURE_ABSENT)` | S1b does not split on NBSP; S9 sanitizes |
| `Cafe 500G` + ZWSP | `Unresolved(PACKAGE_MEASURE_ABSENT)` | ZWSP is neither whitespace nor a letter |
| ZWSP + `Cafe 500G` | name keeps the ZWSP (not `cafe`) | ZWSP never removed (S1a) |
| `6un Dove 90g` | `Unresolved` | S1b requires the count next to the size |
| structured brand `Camil` vs same title without structured brand | distinct | absence ≠ value (PO-4) |
| Atacadão product (GTIN) vs the same product at Carrefour (title) | `GtinKey` ≠ `AttributeKey` | C4 — no catalog link in S2 |

**Consequence for expectations:** exact signature equality will rarely join titles written by different
sources. Cross-source identity comes from GTIN, or from the future catalog link (§14). Within one source
the signature is stable, and it carries the measure/pack safety that F-04 and G-03 required.

**Residual false-merge risks (not fixable in S2):** two different products whose titles are identical
and whose brand is neither in the title nor structured; two different categories with identical titles
and measures (PO-9).

## 14. Deferred decisions

| Decision | State | Owner / trigger |
| --- | --- | --- |
| Local `Product` → `ProductKey` resolution: mechanism and owner | `OPEN_DECISION` (ADR-016 items 1–2) | before S11/S13 |
| `MarketProduct` catalog: definition and owning slice | `OPEN_DECISION` — no slice S1–S13 owns it | before any cross-source identity use |
| GTIN ↔ signature linkage | `OPEN_DECISION` (C4) | same as above; S2 keeps the auxiliary signature to make it possible |
| Conflicting attributes under one GTIN | `DEFERRED` | the catalog decision |
| Key serialization (string, columns, hash) and HTTP shape | `DEFERRED` (PO-10) | S10, S12 |
| **Key-rule versioning** | `DEFERRED_OPEN_DECISION` | before S10 persists any key — see §17 G-7 |
| Whether observations with `AMBIGUOUS_IDENTITY` are persisted | `DEFERRED` (C1) | S4 |
| Stable source product ids as exact identity | `DEFERRED` (C2) | slice that owns provenance |
| HTML decoding, internal NBSP, source sanitization | S9 (C5) | S9 |
| `INTERNAL_NBSP_NORMALIZATION` | `FUTURE / NOT_DECIDED` (S1a) | coordinated cross-repo change only |

## 15. Acceptance criteria

1. **AC1** — `resolveProductKey` and `Gtin.parse` are pure and deterministic, and never throw for any
   string input.
2. **AC2** — GTIN-8/12/13/14 with a valid check digit parse to one canonical 14-digit `Gtin`;
   GTIN-12 ≡ the zero-prefixed GTIN-13 ≡ the zero-prefixed GTIN-14.
3. **AC3** — non-digits, unsupported length, all zeros and a bad check digit are rejected; a present,
   invalid GTIN never yields a key and never falls back to the signature.
4. **AC4** — a valid GTIN yields `GtinKey`, basis `GTIN`, `EXACT_IDENTITY`, whatever the title; the
   failure of the auxiliary signature never removes it.
5. **AC5** — without a GTIN, the fail-closed matrix of §9.2 holds row by row.
6. **AC6** — the canonical name is `MarketTextKey` of `trim()`-ed input with S1b spans replaced by
   one space; punctuation, `com`, accents, ZWSP and internal NBSP are kept.
7. **AC7** — border NBSP, U+202F and U+2007 do not prevent a measure from being recognized.
8. **AC8** — every structural collision of §12 is distinct; `GtinKey` never equals `AttributeKey`;
   `equals`/`hashCode` are consistent.
9. **AC9** — every `Resolved` exposes its basis and level (`EXACT_IDENTITY` / `ATTRIBUTE_IDENTITY`);
   every `Unresolved` exposes `AMBIGUOUS_IDENTITY`; `Rejected` has no level property; a valid GTIN is
   never turned into `Unresolved` or `Rejected` by any other input (§9.3).
10. **AC10** — the 15 real Carrefour titles resolve to the signatures of §16.1.
11. **AC11** — S2 contains no size parsing, no unit alias, no category field and no string form of a
    key (guard greps in the plan).
12. **AC12 — S1 immutability gate.** The S1 artifacts S2 may not alter are byte-identical before and
    after S2. The gate covers exactly these `S1_IMMUTABILITY_PATHS` and nothing else:

    | Path | Why protected | Git blob on `origin/main` `4fb9801` |
    | --- | --- | --- |
    | `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/MarketTextKey.kt` | S1a production rule | `3338193a18de468fb305edf9a21be5749af881ef` |
    | `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/UnitNormalization.kt` | S1b production rule | `9323148d00dff697521a346815e5719b7a01b9d2` |
    | `contracts/fixtures/text-key/parity-cases.v1.json` | cross-repository contract (SHA-256 `aae74726…fdbe136b`, pinned by both repositories) | `0e3f4f4f32a8382dae615e258cd10f40cb7ea1cc` |
    | `contracts/fixtures/text-key/README.md` | normative description of that contract and its hash | `a17a83395cd089a7ac06d925b1862a4d5d857b81` |
    | `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/MarketTextKeyParityTest.kt` | the backend's enforcement of the contract; it pins the hash | `2493878791682634d55493746a2c282de8b2d84c` |

    **Not in the gate:** `MarketTextKeyTest.kt` and `UnitNormalizationTest.kt`. They are not a contract,
    and S2 has no reason to touch them; the plan's scope check (only S2's own files may change) already
    catches any edit to them. Docs, build output and every other file are outside the gate.

    **Verification:** `git hash-object <path>` equals the recorded blob for each path, and
    `git diff --exit-code <base> -- <paths>` is empty, where `<base>` is the `origin/main` commit the S2
    branch is created from. The blobs are stable because the backend uses `core.autocrlf=false`.

## 16. Definition of done

1. This design and its plan approved by the product owner, including §19.
2. `Gtin.kt`, `AttributeSignature.kt`, `ProductIdentityLevel.kt`, `ProductKey.kt` and their tests
   exist in `identity/`, written test-first.
3. Every row of §9.2, every case of §5.4, §8 and §12–§13, and the corpus of §16.1 has a passing test.
4. Targeted tests green; full suite equal to the pre-edit baseline plus the new tests, with no new
   failure.
5. Guard greps of the plan return empty; the S1 immutability gate (AC12) passes.
6. Integrated into backend `main` through the normal PR flow, with the PO's authorization for branch,
   commit, push and PR.
7. **Then** S2 = `COMPLETE`. Updating the roadmap, the delivery plan and `CLAUDE.md` is a separate
   documentation step.

### 16.1 Real corpus — `SUPPORTED_BY_REPO`

Source: POC B `data/carrefour-canonical.json` (15 titles). No GTIN, no structured brand or variant.
Expected results as observed with the real S1a/S1b during discovery; every one is
`Resolved.ByAttributes`, `brand = null`, `variant = null`.

| Title | Canonical name | Measure |
| --- | --- | --- |
| `Arroz Branco Camil Tipo 1 5kg` | `arroz branco camil tipo 1` | `(5000, MASS, 1)` |
| `Feijao Carioca Tipo 1 Kicaldo 1Kg` | `feijao carioca tipo 1 kicaldo` | `(1000, MASS, 1)` |
| `Leite Integral Piracanjuba 1 Litro` | `leite integral piracanjuba` | `(1000, VOLUME, 1)` |
| `Cafe Torrado e Moido Pilao 252 Graus Vacuo 500g` | `cafe torrado e moido pilao 252 graus vacuo` | `(500, MASS, 1)` |
| `Acucar Refinado Uniao 1kg` | `acucar refinado uniao` | `(1000, MASS, 1)` |
| `Oleo de Soja Soya 900ml` | `oleo de soja soya` | `(900, VOLUME, 1)` |
| `Macarrao Espaguete Carrefour Classic 500g` | `macarrao espaguete carrefour classic` | `(500, MASS, 1)` |
| `Farinha de Trigo Integral Dona Benta Integral Premium 1 Kg` | `farinha de trigo integral dona benta integral premium` | `(1000, MASS, 1)` |
| `Molho de Tomate Tradicional Tarantella Sache 300 g` | `molho de tomate tradicional tarantella sache` | `(300, MASS, 1)` |
| `Leite Condensado Integral Carrefour Classic 395g` | `leite condensado integral carrefour classic` | `(395, MASS, 1)` |
| `Detergente Liquido Carrefour Neutro 500ml` | `detergente liquido carrefour neutro` | `(500, VOLUME, 1)` |
| `Sabonete em Barra Dove Karite e Baunilha 90g` | `sabonete em barra dove karite e baunilha` | `(90, MASS, 1)` |
| `Papel Higienico Mimmo Folha Dupla com 4 Rolos` | `papel higienico mimmo folha dupla com` | `(4, COUNT, 1)` |
| `Refrigerante Coca-Cola Garrafa 2 L` | `refrigerante coca-cola garrafa` | `(2000, VOLUME, 1)` |
| `Agua Sanitaria Ype 1L` | `agua sanitaria ype` | `(1000, VOLUME, 1)` |

## 17. Risks and guardrails

| # | Risk | Guardrail |
| --- | --- | --- |
| G-1 | S2 re-implements size parsing (a second notion of «the size token») | only S1b's spans are used; plan guard grep for digit/unit regexes and unit aliases in S2 files |
| G-2 | `trim()` replaced by `java.lang.String.trim()`/`strip()` | NBSP, U+202F and U+2007 border tests; KDoc |
| G-3 | Silent GTIN → signature fallback reintroduced | test: invalid GTIN + perfectly valid title ⇒ `Rejected` |
| G-4 | Auxiliary signature failure blocks a GTIN key | one test per signature failure with a valid GTIN |
| G-5 | Branching on S1b's ambiguity reason | S2 never forwards it; a test over every `PackageMeasureAmbiguity` value expects the same result |
| G-6 | A category or display field added to satisfy the obsolete RED | AC11 guard grep; PO-9 recorded in §18 |
| G-7 | **Key drift:** a change to `MarketTextKey`, to S1b's aliases or grammar, or to this pipeline silently changes keys already produced | versioning is `DEFERRED_OPEN_DECISION`; S2 persists nothing, so there is nothing to migrate yet; the real-corpus test (§16.1) turns any drift into a red test; **must be decided before S10 persists a key** |
| G-8 | `Char.isDigit()` accepting non-ASCII digits in GTIN | explicit `'0'..'9'`; test with an Arabic-Indic digit |
| G-9 | Brand/variant extracted from the title by a later «improvement» | PO-4 in KDoc; tests where a title-embedded brand stays in the name and `brand = null` |
| G-10 | Vendor identifiers in domain types (parent spec invariant 10) | the delivery plan's S4 grep, run over `identity/` too |

## 18. Reconciliation of discovery contradictions

The discovery recorded five contradictions (C1–C5). None is hidden here.

**C1 — `AMBIGUOUS_IDENTITY` observations exist (spec §6.1, §8.14, §12) vs «no observation is emitted»
when identity cannot be established (spec §13).** *Deferred to S4.* S2 only produces
`Unresolved` (level `AMBIGUOUS_IDENTITY`, no key) or `Rejected` (no level, no key); whether S4 stores
observations for either, and how it labels a `Rejected` one, is S4's decision (§11).

**C2 — «`EXACT_IDENTITY` = GTIN igual, ou id estável da mesma fonte» (spec §6.2) vs `ProductKeyBasis` =
{`GTIN`, `ATTRIBUTE_SIGNATURE`}.** *Resolved by PO-8.* In S2, `EXACT_IDENTITY` ⇔ basis `GTIN`. There is
no `SOURCE_ID` basis and no third key form. The «stable source id» clause describes a comparison
between observations of the **same source**, which needs provenance (`sourceProductId`, spec §6.6) that
S2 does not have; it is left to the slice that owns provenance. The parent spec text is not edited by
this slice; the reconciliation is recorded here and in the plan, and the parent documents are updated
in the documentation step after S2 (§16 item 7).

**C3 — delivery plan S2 RED «categoria diferente → nunca casa» vs ADR-015 D4 (no category).** *Resolved
by PO-9: the RED is obsolete and incompatible with the normative contract.* ADR-015 D4 has no
category component, no investigated source structures category, and S2 extracts nothing from the
title. The RED is removed from S2 and not replaced by a category field. S2 does **not** claim that
different categories are always separated by the canonical name (§12). The POC B «Fanta Guaraná as
cola» failure was a **search** defect (spec §13, «busca devolve produto errado»), whose mitigation
(post-filter by category + unit) belongs to the adapter/search boundary, not to key construction.

**C4 — ADR-015 D4's rationale (dedupe across chains: Atacadão has GTIN, Carrefour does not) vs the
literal rule (the GTIN key and the signature key of one product never meet).** *Not resolved in S2 —
`OPEN_DECISION`.* `GtinKey ≠ AttributeKey` always. Joining them requires a catalog/resolution step whose
owner is undecided (§14). S2 only keeps the door open: on the GTIN path it computes the signature best
effort (PO-7) and returns it beside the key, outside the key's equality. No catalog is implemented.

**C5 — S1b §6.1 expects input already decoded by the adapter (S9), but S9 comes after S2 in the DAG.**
*Resolved by the PO clarification of 2026-09-18.* The DAG order is the order of building foundations,
not a transfer of runtime responsibilities: HTML decoding, internal NBSP and source sanitization stay
with S9. S2's only extra preparation is `analysisInput = rawTitle.trim()`. Undecoded input degrades
into the accepted false splits or `Unresolved` of §13, never into a false merge.

## 19. Spec-level choices (SR-1 to SR-8)

These follow from PO-1 to PO-11 but are not literally stated in them. Each one is marked in the plan.

| # | Choice | Rationale | Derives from | Status |
| --- | --- | --- | --- | --- |
| SR-1 | A structured `brand`/`variant` that is `null` is absent and allowed; one that is present but blank or whitespace-only is invalid structured input ⇒ `Rejected(BlankBrand/BlankVariant)` on the signature path, and it is never silently converted to `null`. On the GTIN path it only removes the auxiliary signature (§9.3). The adapter maps «no field» to `null` | consistent with «blank title ⇒ invalid»; no silent choice between «absent» and «empty value» | PO-4 (absence is explicit), PO-11 | **APPROVED by PO** (2026-09-18) |
| SR-2 | Valid GTIN + blank title ⇒ `Resolved.ByGtin` with `auxiliaryAttributeSignature = null`. Without GTIN, a blank title stays `Rejected(BlankTitle)` and never yields an `AttributeKey` | PO-7 («never block a valid GTIN key») takes precedence over the matrix row «blank title ⇒ no key», which applies to the signature path | PO-7 | **APPROVED by PO** (2026-09-18) |
| SR-3 | `gtin = null` is absent; `gtin = ""` is a **present** GTIN ⇒ `Rejected(InvalidGtin(UNSUPPORTED_LENGTH))`; the GTIN is never trimmed or repaired (`" 789…"` ⇒ `NON_DIGIT`) | PO-6 «digits only»; same principle as SR-1: a supplied value is judged as supplied | PO-6 + SR-1 | **APPROVED by PO** (2026-09-18) |
| SR-4 | GTIN digits are ASCII `0`–`9` only; Unicode decimal digits are not accepted (`Char.isDigit` is not used; U+0665 ⇒ `NON_DIGIT`) | GS1 GTINs are ASCII digit strings; `Char.isDigit` would accept other scripts' digits | PO-6 «digits only» | **APPROVED by PO** (2026-09-18) |
| SR-5 | **`ProductKey.fromGtin(gtin)` and `ProductKey.fromAttributes(signature)` are kept as the primary public construction API**, as the delivery plan (§6 S2 «Produz») names them; they return `ProductKey`. `GtinKey` and `AttributeKey` exist as structural variants for matching, with `internal` constructors (`@ConsistentCopyVisibility`) whose only call sites are the two factories. `resolveProductKey` is the entry point for external data and builds keys only through the factories. File and test layout: `Gtin.kt` (PO-6) and the test files `GtinTest`, `AttributeSignatureTest`, `ProductKeyTest` are **added** to the files the delivery plan lists; none of them replaces a listed one | preserves the approved public contract; consumers are not coupled to concrete variants; construction is centralized in the factories | PO-6, PO-11, delivery plan §6 S2 | **APPROVED_AS_MODIFIED by PO** (2026-09-18). There is no divergence from the delivery plan's «Produz» line |
| SR-6 | `ProductKeyResult.Resolved` is a sealed interface with two shapes. `Resolved.ByGtin(gtin, auxiliaryAttributeSignature: AttributeSignature?)`: the auxiliary signature is best effort and optional. `Resolved.ByAttributes(signature)`: the signature is the key's own and is stored **once**. On both, `key` is derived through the factories and `attributeSignature` is a derived property (`ByAttributes` → `signature`; `ByGtin` → `auxiliaryAttributeSignature`). The state «`AttributeKey(signature = A)` + `attributeSignature = B`» is unrepresentable; no `init` reconciles two copies. The auxiliary signature never takes part in `GtinKey` equality/hash, never changes basis or level, never invalidates a valid GTIN | consumers read `attributeSignature` on both paths; no redundant state; impossible state excluded by construction | PO-7 | **APPROVED_AS_MODIFIED by PO** (2026-09-18) |
| SR-7 | Structured brand and variant use `MarketTextKey.from` as their canonical text representation; no second text rule | the only text primitive (ADR-016 D3) | PO-4, ADR-016 | **APPROVED by PO** (2026-09-18) |
| SR-8 | Determinism of diagnostics: the GTIN is evaluated first (§9.3); within `Gtin.parse`, G1–G4 run in declaration order of `GtinRejection` and the first failure is reported; on the signature path the first failing step of §9.1 is reported | determinism, as S1b design §8; order is diagnostic only and never changes whether a key exists | PO-6, PO-7, PO-11 | **APPROVED by PO** (2026-09-18) |
