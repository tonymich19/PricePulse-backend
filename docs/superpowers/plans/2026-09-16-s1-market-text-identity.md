# S1a — Market Text Identity (`MarketTextKey`) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or
> `superpowers:executing-plans` when this slice is eventually executed. Steps use checkbox syntax.

**Status:** **PLANNED — NOT AUTHORIZED FOR EXECUTION.** Corrected on 2026-09-17 after the M5
assumption audit (see "Correction of 2026-09-17").

- **Milestone 4:** closed by the product owner on 2026-09-17 (`M4_GATE_PASS = YES`).
- **Milestone 5:** implementation **NOT_STARTED**. S1, S1a and S1b have **not** started.
- Execution still requires every item in "Execution preconditions" below and an explicit
  authorization from the product owner.

**Slice:** **S1a** of S1 in the approved sequence
`../../../PricePulse/docs/product-development/plans/price-intelligence-foundation-v1.md`
(GATE 1 APPROVED, GATE 2 APPROVED). S1 is decomposed as:

```text
S1  Canonical text key and unit normalization
├── S1a  MarketTextKey        <- this plan
└── S1b  UnitNormalization    <- no detailed plan yet
```

**S1 is COMPLETE only when S1a and S1b are both COMPLETE. S2 stays blocked until then.** This plan
covers S1a only and does not anticipate S1b or S2.

**Goal:** Give the backend a deterministic text-identity primitive whose behaviour is provably
identical to the app's existing `ProductNameMatchKey`, without importing app code and without
creating any build dependency between the two repositories.

**Why:** ADR-012 **D7** fixed that there is exactly one notion of text equality in PricePulse. The
backend cannot import `ProductNameMatchKey` — it lives in the app, has a private constructor, and the
repositories are built independently. So the backend reimplements the **rule** (never the type), and
a canonical fixture exercised by a contract test **in each repository** makes any divergence a red
test instead of a silent wrong merge.

**Architecture:** One pure Kotlin/JVM value class plus one JSON contract fixture. No Android, no
Compose, no Ktor, no Postgres, no `PriceObservation`. No fuzzy matching of any kind.

---

## Execution preconditions

S1a may not start until **all** of these hold. They are `S1a_EXECUTION_PRECONDITION`s.

1. **Canonical registration of M4 and M5 in the app repository.** The M4 closing documentation and
   the approved M5 design are integrated through the normal repository flow and present in the app's
   `origin/main`. The local branch `docs/marco4-encerramento` (commits `1a8c0d5`, `36bc754`) was
   sufficient as an audit base but is **not** the canonical record (product-owner decision,
   2026-09-17).
2. **This corrected plan has been re-audited and approved for execution** by the product owner.
3. **The backend guidance (`CLAUDE.md`, `AGENTS.md`) reflects the post-M4 state.**

Completion (not start) of S1a additionally requires the **app-side contract test** described in
"Parity proof" — see the Definition of done.

---

## Global constraints

- **Pure Kotlin/JVM only.** No dependency on Android, Compose, Ktor, Postgres, or any other S-slice.
- **No fuzzy matching.** S1a produces one deterministic key. Nothing else.
- **No new normalization is invented.** The rule is the app's rule as it **actually executes**, with
  every expected value generated from the compiled app class (see "Confirmed canonical behaviour").
  In particular: **accents are preserved**, **there is no Unicode normalization**, and **internal
  NBSP is not collapsed**.
- **No cross-repository source or build dependency, and no shared module.** Parity is proved by the
  same fixture, byte for byte, tested in each repository.
- **No scope creep:** no `UnitNormalization` (that is S1b), no `MarketProduct`, `ProductKey`,
  `PriceObservation`, `Merchant`, `Store`, `PriceRegion`, API, migration or adapter in this slice.
- Backend test convention: `kotlin.test`, backticked test names (evidence below).

---

## Confirmed canonical behaviour

**Source of truth:** `../../../PricePulse/app/src/main/java/com/tonycorreia/pricepulse/domain/matching/product/ProductNameMatchKey.kt`
(unchanged since `f18eaf2`, 2026-07-21) and its test `ProductNameMatchKeyTest.kt` (11 cases).

**The rule, as compiled.** The bytecode of `ProductNameMatchKey$Companion.from` (Kotlin 2.2.10,
inspected with `javap`) calls:

1. `kotlin.text.StringsKt.trim(CharSequence)` — **Kotlin's** `trim()`, which removes every
   character for which Kotlin's `Char.isWhitespace()` is true. It is **not** `java.lang.String.trim()`
   (which removes only characters `<= U+0020`);
2. `Regex("\\p{javaWhitespace}+").replace(…, " ")` — collapses runs matched by
   `Character.isWhitespace`;
3. rejects with `IllegalArgumentException` when the result is empty;
4. `toLowerCase(Locale.ROOT)`.

The two steps use **different** notions of whitespace, which is the whole story:

| Codepoint | Kotlin `Char.isWhitespace` (step 1: border trim) | `\p{javaWhitespace}` (step 2: internal collapse) | At a border | Inside the text |
| --- | --- | --- | --- | --- |
| U+0020 SPACE | true | true | removed | collapsed |
| U+0009 TAB | true | true | removed | collapsed |
| U+2002 EN SPACE | true | true | removed | collapsed |
| U+2003 EM SPACE | true | true | removed | collapsed |
| U+3000 IDEOGRAPHIC SPACE | true | true | removed | collapsed |
| U+00A0 NBSP | **true** | **false** | **removed** | **kept** |
| U+202F NARROW NBSP | **true** | **false** | **removed** | **kept** |
| U+2007 FIGURE SPACE | **true** | **false** | **removed** | **kept** |
| U+200B ZERO WIDTH SPACE | **false** | **false** | **kept** | **kept** |

Consequences, every one of them generated from the compiled app class and pinned in the fixture:

- EM SPACE, IDEOGRAPHIC SPACE, NBSP, NARROW NBSP and FIGURE SPACE **at a border are removed** —
  `"\u2003Trakinas"` (or any of the others in place of `\u2003`) keys to `"trakinas"`;
- text made **only** of EM SPACE, NBSP (or any mix of the removed characters) is **rejected**;
- **internal** NBSP, NARROW NBSP and FIGURE SPACE are **not** collapsed —
  `"Biscoito\u00a0Chocolate"` keys to `"biscoito\u00a0chocolate"`;
- ZWSP is **never** removed, at a border or inside, and a ZWSP-only text is **accepted**;
- case folds under `Locale.ROOT` — `KILO` keys to `kilo`;
- **accents preserved** — `Café 500 g` differs from `Cafe 500 g`;
- **no Unicode normalization** — precomposed `é` (U+00E9) differs from `e` + U+0301;
- hyphens, apostrophes, partial names and package-size spelling are all preserved as differences;
- `""`, `"   "` and `"\t"` are rejected.

The app's own unicode test uses **U+2003 EM SPACE** internally — verified by reading the file's
bytes (`E2 80 83` on line 43).

### Correction of 2026-09-17 — `LEGACY_CANONICAL_BEHAVIOR` withdrawn

The first version of this plan (2026-09-16) stated that border EM SPACE **survives** the trim and
becomes a border ASCII space (`"\u2003Trakinas" -> " trakinas"`), that an NBSP-only text is **accepted**,
and classified this as `LEGACY_CANONICAL_BEHAVIOR`. **That premise was false.** It described
`java.lang.String.trim()`; the app is compiled against Kotlin's `trim()`. The M5 assumption audit of
2026-09-17 established the real behaviour by:

- disassembling the compiled `ProductNameMatchKey$Companion` (it calls `kotlin/text/StringsKt.trim`);
- executing that call with the real `kotlin-stdlib` 2.2.10; and
- generating every expected value in the fixture by invoking the compiled app class itself.

**Product-owner decision, 2026-09-17:** the principle of the earlier option A stands — **the backend
reproduces the real, current behaviour of `ProductNameMatchKey` exactly** — but the
`LEGACY_CANONICAL_BEHAVIOR` classification for border whitespace is **removed**. There is no border
quirk to preserve. The fixture describes the app; the backend is never adapted to a wrong table.

### Registered future debt — `INTERNAL_NBSP_NORMALIZATION`

The former `MARKET_TEXT_KEY_WHITESPACE_V2` debt was mostly invalidated by the correction: border
whitespace, including NBSP, is already removed by the app. What remains is only this:

**`INTERNAL_NBSP_NORMALIZATION` = FUTURE / NOT_DECIDED.** Internal NBSP, NARROW NBSP and FIGURE
SPACE are not collapsed. Whether that should ever change is **not decided**, is **not** part of S1a,
and does not alter current behaviour. Any future change to the key would reshape every stored alias
(ADR-012) and would have to be versioned and applied to the app and the backend together.

### Source-side sanitisation belongs to the adapter boundary

**NBSP and HTML entities from storefronts must never change `MarketTextKey` semantics.** Confirmed
as an architectural decision on 2026-09-16, and still required after the correction — but only for
**internal** NBSP: a name arriving as `"Arroz\u00a0Camil 5kg"` keys differently from the app-side
`"Arroz Camil 5kg"`. Border NBSP is already removed by the rule.

```text
raw source -> source decoding / HTML normalization -> canonical textual input -> MarketTextKey
```

**Not implemented in S1a**; recorded as a requirement of the source-adapter slice (S9). The POC
evidence contains no NBSP sample, so the frequency of `&nbsp;` in storefront names is an assumption,
not a measurement; the requirement is harmless either way.

---

## Parity proof

Parity cannot rest on a fixture tested only in the backend. The approved strategy (product-owner
decision, 2026-09-17) is:

```text
CANONICAL CONTRACT FIXTURE  +  APP CONTRACT TEST  +  BACKEND CONTRACT TEST

app:      ProductNameMatchKey  -> contract test -> parity-cases.v1.json (copy)
backend:  MarketTextKey        -> contract test -> parity-cases.v1.json (canonical)
```

- **One fixture, two identical copies.** The canonical file is
  `contracts/fixtures/text-key/parity-cases.v1.json` in this repository, where `CLAUDE.md` already
  declares cross-repository contract authority. The app holds a byte-identical copy.
- **Identity is checked by hash.** SHA-256 of the fixture content with line endings normalised to
  LF (which is the git blob content):
  `aae747269ff423a7d2e38a93533a11b5d378ab3f7a902fe37d87b3c7fdbe136b` (5739 bytes, ASCII only). Both
  contract tests pin this value, so a copy that drifts fails in the repository that holds it. LF
  normalisation is needed because the app repository uses `core.autocrlf=true`.
- **Provenance.** Expected values were generated by invoking the compiled app class
  `ProductNameMatchKey` (PricePulse `c81a2d9`, source unchanged since `f18eaf2`) through reflection,
  with `kotlin-stdlib` 2.2.10. Only inputs were written by hand. The README records this.
- **The app copy follows the app's existing convention** (`app/src/test/resources/contract/README.md`):
  extracted from a backend **commit** — `git cat-file blob <backend commit>:contracts/fixtures/text-key/parity-cases.v1.json`,
  the byte-exact form of `git show` for a blob — never from a working copy, into
  `app/src/test/resources/contract/text-key/parity-cases.v1.json`, with the origin commit, blob id and
  hash recorded.
- **The app contract test is a separate change in the app repository**, with its own plan:
  `../../../PricePulse/docs/superpowers/plans/2026-09-17-s1a-market-text-contract-parity.md`
  (`ProductNameMatchKeyContractTest`, 5 tests). It is **not** part of the five backend files below,
  and it is **not** implemented yet. **It is a precondition for S1a to be COMPLETE:** the backend test
  proves `MarketTextKey` matches the fixture; only the app test proves the fixture still matches
  `ProductNameMatchKey`.
- **No code crosses repositories.** No import of app code, no shared module, no build dependency.

**Order of S1a, shared with the app plan.** The app contract test cannot run before a backend commit
containing the canonical fixture exists.

1. The backend creates the canonical fixture (Task 1).
2. The backend implements `MarketTextKey` and its contract test (Tasks 2–3).
3. The backend produces a verifiable commit with the fixture, the implementation and green tests.
4. The app copies the fixture from that commit with `git cat-file`.
5. The app implements the `ProductNameMatchKey` contract test.
6. Both repositories validate the same SHA-256.
7. Only then may S1a be declared COMPLETE.

---

## Assumption audit

| Assumption | Evidence inspected | Planning consequence |
| --- | --- | --- |
| The app trims with Kotlin's `trim()`, not Java's | `javap -c` of `ProductNameMatchKey$Companion.class`: `invokestatic kotlin/text/StringsKt.trim` | The backend must also call Kotlin's `String.trim()`. Using `java.lang.String.trim()` or `strip()` would diverge (neither removes NBSP) |
| Expected values match the real app | Fixture generated by reflection over the compiled app class with `kotlin-stdlib` 2.2.10 | Expected values are never edited by hand |
| App and backend use the same Kotlin | App `gradle/libs.versions.toml`: `kotlin = "2.2.10"`; backend `build.gradle.kts`: `kotlin("jvm") version "2.2.10"` | The same `trim()` semantics apply on both sides |
| Backend tests use `kotlin.test`, not JUnit | `PreparedReceiptImageTest.kt:3-6`; 145 `import kotlin.test` lines across `src/test`; `testImplementation(kotlin("test-junit5"))` | Tests use `kotlin.test`; `assertFailsWith` for rejection |
| Backticked test names are the convention | `PreparedReceiptImageTest`, `ResolveReconcilingOperationsUseCaseTest` | Same style here |
| `contracts/fixtures/` is the canonical cross-repo fixture home | `CLAUDE.md` names `contracts/` canonical; `ReceiptAnalysisResultSchemaTest` reads `contracts/fixtures/{valid,invalid}/` | The fixture lives at `contracts/fixtures/text-key/`, a **new sibling** directory |
| A sibling directory cannot break the existing schema test | `ReceiptAnalysisResultSchemaTest.kt:17-24` enumerates only `valid/` and `invalid/` via `listFiles { extension == "json" }` | `text-key/` is invisible to it. Verified by re-running that test in Step 3 |
| Tests resolve fixtures from the project directory | `ReceiptAnalysisResultSchemaTest.kt:17`: `File(System.getProperty("user.dir"))` | Same pattern in the parity test |
| Jackson is available to tests | `build.gradle.kts:36`: `implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")` | No new dependency |
| No `priceintelligence` code exists yet | `src/main/kotlin/.../application/` contains only `receiptanalysis` | Step 5 must fail with an unresolved reference |
| Line endings differ between repositories | Backend `core.autocrlf=false`; app `core.autocrlf=true`; neither has `.gitattributes` | The fixture hash is computed over LF-normalised content |
| Project toolchain is JDK 21 | `README.md` "JDK: 21"; `jvmToolchain(21)` | Behaviour verified on JDK 21 (`openjdk 21.0.10`) |

**No assumption remains open in S1a.** S1b has no detailed plan and is not audited here.

---

## File structure

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/MarketTextKey.kt` | the primitive: one value class, one factory, nothing else |
| `contracts/fixtures/text-key/parity-cases.v1.json` | the canonical contract fixture |
| `contracts/fixtures/text-key/README.md` | what the fixture is, its provenance and hash, and the rule that it is never edited to match an implementation |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/MarketTextKeyTest.kt` | behaviour of the primitive, written as tests first |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/MarketTextKeyParityTest.kt` | the backend contract test, driven by the fixture |

Five backend files. No other backend file is modified. The app-side copy and contract test belong to
a separate app change (see "Parity proof").

---

## Task 1: The contract fixture

**Files:**
- Create: `contracts/fixtures/text-key/parity-cases.v1.json`
- Create: `contracts/fixtures/text-key/README.md`

**Interfaces:**
- Produces: a JSON document with three arrays — `equivalence` (each `{id, left, right, equal}`),
  `rejection` (each `{id, input, rejected}`) and `canonicalKey` (each `{id, input, key}`, pinning the
  **exact output** of the app). Every non-ASCII or control character is a `\uXXXX` escape, so the file
  is pure ASCII and no editor can silently change it.

- [ ] **Step 1: Write the fixture**

`contracts/fixtures/text-key/parity-cases.v1.json` — copy **verbatim**, LF line endings, final
newline. Then confirm the SHA-256 is
`aae747269ff423a7d2e38a93533a11b5d378ab3f7a902fe37d87b3c7fdbe136b`. If it is not, the copy is wrong;
do not edit values to fix it.

```json
{
  "version": 1,
  "rule": "kotlin.text.trim() (Char.isWhitespace) -> replace \\p{javaWhitespace}+ with one space -> reject when empty -> lowercase(Locale.ROOT)",
  "provenance": "Expected values generated by invoking the compiled app class ProductNameMatchKey (PricePulse c81a2d9, source unchanged since f18eaf2) with kotlin-stdlib 2.2.10. Inputs written by hand; outputs never.",
  "equivalence": [
    { "id": "IDENTICAL",                "left": "Biscoito Chocolate 120 g Trakinas", "right": "Biscoito Chocolate 120 g Trakinas", "equal": true },
    { "id": "CASE",                     "left": "Biscoito Chocolate 120 g Trakinas", "right": "biscoito chocolate 120 g trakinas", "equal": true },
    { "id": "BORDER_ASCII_SPACE",       "left": "Trakinas", "right": "  Trakinas  ", "equal": true },
    { "id": "BORDER_TAB",               "left": "Trakinas", "right": "\u0009Trakinas\u0009", "equal": true },
    { "id": "INTERNAL_ASCII_RUN",       "left": "Biscoito Chocolate 120 g Trakinas", "right": "Biscoito  Chocolate 120  g Trakinas", "equal": true },
    { "id": "INTERNAL_TAB",             "left": "Biscoito Chocolate", "right": "Biscoito\u0009Chocolate", "equal": true },
    { "id": "INTERNAL_EN_SPACE",        "left": "Biscoito Chocolate", "right": "Biscoito\u2002Chocolate", "equal": true },
    { "id": "INTERNAL_EM_SPACE",        "left": "Biscoito Chocolate", "right": "Biscoito\u2003Chocolate", "equal": true },
    { "id": "INTERNAL_IDEOGRAPHIC",     "left": "Biscoito Chocolate", "right": "Biscoito\u3000Chocolate", "equal": true },
    { "id": "INTERNAL_NBSP",            "left": "Biscoito Chocolate", "right": "Biscoito\u00a0Chocolate", "equal": false },
    { "id": "INTERNAL_NARROW_NBSP",     "left": "Biscoito Chocolate", "right": "Biscoito\u202fChocolate", "equal": false },
    { "id": "INTERNAL_FIGURE_SPACE",    "left": "Biscoito Chocolate", "right": "Biscoito\u2007Chocolate", "equal": false },
    { "id": "INTERNAL_ZWSP",            "left": "Biscoito Chocolate", "right": "Biscoito\u200bChocolate", "equal": false },
    { "id": "BORDER_EM_SPACE",          "left": "Trakinas", "right": "\u2003Trakinas", "equal": true },
    { "id": "BORDER_EM_SPACE_TRAILING", "left": "Trakinas", "right": "Trakinas\u2003", "equal": true },
    { "id": "BORDER_EM_SPACE_BOTH",     "left": "Trakinas", "right": "\u2003Trakinas\u2003", "equal": true },
    { "id": "BORDER_IDEOGRAPHIC",       "left": "Trakinas", "right": "\u3000Trakinas", "equal": true },
    { "id": "BORDER_NBSP",              "left": "Trakinas", "right": "\u00a0Trakinas", "equal": true },
    { "id": "BORDER_NBSP_TRAILING",     "left": "Trakinas", "right": "Trakinas\u00a0", "equal": true },
    { "id": "BORDER_NARROW_NBSP",       "left": "Trakinas", "right": "\u202fTrakinas", "equal": true },
    { "id": "BORDER_FIGURE_SPACE",      "left": "Trakinas", "right": "\u2007Trakinas", "equal": true },
    { "id": "BORDER_ZWSP",              "left": "Trakinas", "right": "\u200bTrakinas", "equal": false },
    { "id": "ACCENT",                   "left": "Caf\u00e9 500 g", "right": "Cafe 500 g", "equal": false },
    { "id": "UNICODE_COMPOSITION",      "left": "Caf\u00e9", "right": "Cafe\u0301", "equal": false },
    { "id": "HYPHEN",                   "left": "COCA-COLA 2 l", "right": "COCA COLA 2 l", "equal": false },
    { "id": "APOSTROPHE",               "left": "M&M's 100 g", "right": "M&Ms 100 g", "equal": false },
    { "id": "PARTIAL_NAME",             "left": "Biscoito Chocolate 120 g", "right": "Biscoito Chocolate 120 g Trakinas", "equal": false },
    { "id": "PACKAGE_SIZE",             "left": "Arroz 5 kg", "right": "Arroz 5kg", "equal": false },
    { "id": "TURKISH_I",                "left": "KILO", "right": "kilo", "equal": true }
  ],
  "rejection": [
    { "id": "EMPTY",                 "input": "", "rejected": true },
    { "id": "ASCII_SPACES",          "input": "   ", "rejected": true },
    { "id": "TAB_ONLY",              "input": "\u0009", "rejected": true },
    { "id": "EM_SPACE_ONLY",         "input": "\u2003", "rejected": true },
    { "id": "IDEOGRAPHIC_ONLY",      "input": "\u3000", "rejected": true },
    { "id": "NBSP_ONLY",             "input": "\u00a0", "rejected": true },
    { "id": "NARROW_NBSP_ONLY",      "input": "\u202f", "rejected": true },
    { "id": "FIGURE_SPACE_ONLY",     "input": "\u2007", "rejected": true },
    { "id": "MIXED_WHITESPACE_ONLY", "input": " \u00a0\u2003\u0009", "rejected": true },
    { "id": "ZWSP_ONLY",             "input": "\u200b", "rejected": false }
  ],
  "canonicalKey": [
    { "id": "PLAIN",                    "input": "Trakinas", "key": "trakinas" },
    { "id": "BORDER_EM_SPACE",          "input": "\u2003Trakinas", "key": "trakinas" },
    { "id": "BORDER_EM_SPACE_TRAILING", "input": "Trakinas\u2003", "key": "trakinas" },
    { "id": "BORDER_EM_SPACE_BOTH",     "input": "\u2003Trakinas\u2003", "key": "trakinas" },
    { "id": "BORDER_IDEOGRAPHIC",       "input": "\u3000Trakinas", "key": "trakinas" },
    { "id": "ASCII_THEN_EM_SPACE",      "input": " \u2003Trakinas", "key": "trakinas" },
    { "id": "BORDER_NBSP",              "input": "\u00a0Trakinas", "key": "trakinas" },
    { "id": "BORDER_ZWSP",              "input": "\u200bTrakinas", "key": "\u200btrakinas" },
    { "id": "INTERNAL_EM_SPACE",        "input": "Biscoito\u2003Chocolate", "key": "biscoito chocolate" },
    { "id": "INTERNAL_NBSP",            "input": "Biscoito\u00a0Chocolate", "key": "biscoito\u00a0chocolate" },
    { "id": "ACCENT_KEPT",              "input": "Caf\u00e9 500 g", "key": "caf\u00e9 500 g" },
    { "id": "TURKISH_I",                "input": "KILO", "key": "kilo" },
    { "id": "ZWSP_ONLY",                "input": "\u200b", "key": "\u200b" }
  ]
}
```

Counts: **29** `equivalence`, **10** `rejection`, **13** `canonicalKey`.

- [ ] **Step 2: Write the README**

`contracts/fixtures/text-key/README.md` must state:

- this fixture is the cross-repository contract for text identity (ADR-012 D7), consumed by a
  contract test in **each** repository: `MarketTextKeyParityTest` here and an app-side test against
  `ProductNameMatchKey`;
- its **provenance**: every expected value was generated by invoking the compiled app class
  `ProductNameMatchKey` (PricePulse `c81a2d9`, source unchanged since `f18eaf2`) with
  `kotlin-stdlib` 2.2.10; only inputs were written by hand;
- its **SHA-256 over LF-normalised content**,
  `aae747269ff423a7d2e38a93533a11b5d378ab3f7a902fe37d87b3c7fdbe136b`, and that the app copy must
  have the same hash;
- **when a case fails, the implementation is wrong until proven otherwise — the fixture is never
  edited to make a test pass.** A change to expected values requires regenerating them from the app
  and changing both copies and both pinned hashes together;
- non-ASCII and control characters are written as `\uXXXX` escapes;
- border whitespace (including NBSP) is removed by the app's Kotlin `trim()`, internal NBSP is not
  collapsed, and ZWSP is never removed — this is the real behaviour, not a legacy quirk;
- `INTERNAL_NBSP_NORMALIZATION` is FUTURE / NOT_DECIDED and must never be applied locally.

- [ ] **Step 3: Verify the fixture does not disturb the existing contract test**

```bash
./gradlew.bat test --tests '*ReceiptAnalysisResultSchemaTest*'
```

Expected: PASS, same count as before. The schema test enumerates only `valid/` and `invalid/`.

---

## Task 2: `MarketTextKey`, test-first

**Files:**
- Test: `src/test/kotlin/.../application/priceintelligence/identity/MarketTextKeyTest.kt`
- Create: `src/main/kotlin/.../application/priceintelligence/identity/MarketTextKey.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `MarketTextKey` — `@JvmInline value class MarketTextKey private constructor(val value: String)`
  with `companion object { fun from(text: String): MarketTextKey }`. Throws
  `IllegalArgumentException` when the collapsed text is empty.

- [ ] **Step 4: Write the failing behaviour test**

All non-ASCII characters are written as Kotlin `\uXXXX` escapes.

```kotlin
package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MarketTextKeyTest {

    @Test
    fun `rejects empty text`() {
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from("") }
    }

    @Test
    fun `rejects ascii-whitespace-only text`() {
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from("   ") }
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from("\t") }
    }

    @Test
    fun `rejects text made only of non-ascii space separators`() {
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from("\u2003") }
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from("\u00A0") }
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from(" \u00A0\u2003\t") }
    }

    @Test
    fun `trims ascii borders and collapses internal runs`() {
        assertEquals(
            MarketTextKey.from("Biscoito Chocolate 120 g Trakinas"),
            MarketTextKey.from("  Biscoito  Chocolate 120  g Trakinas  ")
        )
    }

    @Test
    fun `collapses internal unicode whitespace that java considers whitespace`() {
        assertEquals(
            MarketTextKey.from("Biscoito Chocolate"),
            MarketTextKey.from("Biscoito\u2003Chocolate")
        )
    }

    @Test
    fun `does not collapse internal nbsp`() {
        assertNotEquals(
            MarketTextKey.from("Biscoito Chocolate"),
            MarketTextKey.from("Biscoito\u00A0Chocolate")
        )
        assertEquals("biscoito\u00A0chocolate", MarketTextKey.from("Biscoito\u00A0Chocolate").value)
    }

    /**
     * The app's rule uses Kotlin's `trim()`, which removes every character for which Kotlin's
     * `Char.isWhitespace()` is true — including NBSP, narrow NBSP and figure space. Do NOT replace it
     * with `java.lang.String.trim()` or `strip()`: neither removes NBSP, and the backend would split
     * text identity from the app.
     */
    @Test
    fun `trims non-ascii space separators at the borders`() {
        assertEquals("trakinas", MarketTextKey.from("\u2003Trakinas").value)
        assertEquals("trakinas", MarketTextKey.from("Trakinas\u2003").value)
        assertEquals("trakinas", MarketTextKey.from("\u3000Trakinas").value)
        assertEquals("trakinas", MarketTextKey.from("\u00A0Trakinas").value)
        assertEquals("trakinas", MarketTextKey.from("\u202FTrakinas").value)
        assertEquals("trakinas", MarketTextKey.from("\u2007Trakinas").value)
        assertEquals("trakinas", MarketTextKey.from(" \u2003Trakinas").value)
    }

    @Test
    fun `never removes zero width space`() {
        assertEquals("\u200Btrakinas", MarketTextKey.from("\u200BTrakinas").value)
        assertEquals("\u200B", MarketTextKey.from("\u200B").value)
    }

    @Test
    fun `folds case using a fixed locale`() {
        assertEquals(MarketTextKey.from("KILO"), MarketTextKey.from("kilo"))
        assertEquals("kilo", MarketTextKey.from("KILO").value)
    }

    @Test
    fun `preserves accents`() {
        assertNotEquals(MarketTextKey.from("Caf\u00E9 500 g"), MarketTextKey.from("Cafe 500 g"))
        assertEquals("caf\u00E9 500 g", MarketTextKey.from("Caf\u00E9 500 g").value)
    }

    @Test
    fun `does not apply unicode normalization`() {
        assertNotEquals(MarketTextKey.from("Caf\u00E9"), MarketTextKey.from("Cafe\u0301"))
    }

    @Test
    fun `preserves hyphens and apostrophes`() {
        assertNotEquals(MarketTextKey.from("COCA-COLA 2 l"), MarketTextKey.from("COCA COLA 2 l"))
        assertNotEquals(MarketTextKey.from("M&M's 100 g"), MarketTextKey.from("M&Ms 100 g"))
    }

    @Test
    fun `does not treat a partial name as equivalent`() {
        assertNotEquals(
            MarketTextKey.from("Biscoito Chocolate 120 g"),
            MarketTextKey.from("Biscoito Chocolate 120 g Trakinas")
        )
    }
}
```

That is **13** tests.

- [ ] **Step 5: Run it and confirm it fails for the right reason**

```bash
./gradlew.bat test --tests '*MarketTextKeyTest*'
```

Expected: **compilation failure**, `Unresolved reference: MarketTextKey`. Not an assertion failure —
if it compiles, something already defines the name and that must be investigated before continuing.

- [ ] **Step 6: Write the minimal implementation**

```kotlin
package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import java.util.Locale

/**
 * The single notion of text equality for market product identity, mirroring the app's
 * `ProductNameMatchKey` (ADR-012 D7). Trims with Kotlin's `trim()`, collapses runs of
 * `\p{javaWhitespace}` to one space, rejects an empty result and lowercases with a fixed [Locale.ROOT].
 *
 * The two whitespace notions differ, exactly as in the app: `trim()` removes every Kotlin
 * `Char.isWhitespace` character at the borders (NBSP, narrow NBSP and figure space included), while
 * the collapse leaves internal NBSP, narrow NBSP and figure space untouched. Zero width space is never
 * removed. Deliberately preserves accents, punctuation, hyphens, apostrophes, numbers, units and token
 * order, and applies no Unicode normalization.
 *
 * Do not replace `trim()` with `java.lang.String.trim()` or `strip()`: neither removes NBSP.
 *
 * The app's implementation cannot be imported: separate repositories, private constructor. Parity is
 * proved by [MarketTextKeyParityTest] and by the app's contract test, both against
 * `contracts/fixtures/text-key/parity-cases.v1.json`. That fixture, not this code, is the contract.
 */
@JvmInline
value class MarketTextKey private constructor(val value: String) {

    companion object {
        private val WHITESPACE = Regex("\\p{javaWhitespace}+")

        fun from(text: String): MarketTextKey {
            val collapsed = text.trim().replace(WHITESPACE, " ")
            require(collapsed.isNotEmpty()) { "text must not be blank" }
            return MarketTextKey(collapsed.lowercase(Locale.ROOT))
        }
    }
}
```

- [ ] **Step 7: Run and confirm green**

```bash
./gradlew.bat test --tests '*MarketTextKeyTest*'
```

Expected: PASS, **13** tests.

---

## Task 3: Backend contract test driven by the fixture

**Files:**
- Test: `src/test/kotlin/.../application/priceintelligence/identity/MarketTextKeyParityTest.kt`

**Interfaces:**
- Consumes: `MarketTextKey.from` (Task 2), `contracts/fixtures/text-key/parity-cases.v1.json` (Task 1).
- Produces: nothing consumed by later slices. It is a guard.

- [ ] **Step 8: Write the contract test**

```kotlin
package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives [MarketTextKey] from the cross-repository contract fixture. The app runs the same fixture
 * against `ProductNameMatchKey`. A failure here means the backend rule drifted from the app. The
 * fixture is the contract: fix the code, never the fixture.
 */
class MarketTextKeyParityTest {

    private val fixture =
        File(File(System.getProperty("user.dir")), "contracts/fixtures/text-key/parity-cases.v1.json")

    private val table = ObjectMapper().readTree(fixture)

    /** Same value as the app's copy. Computed over LF-normalised content (the git blob content). */
    @Test
    fun `fixture content matches the pinned hash shared with the app`() {
        val lf = fixture.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(lf.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("aae747269ff423a7d2e38a93533a11b5d378ab3f7a902fe37d87b3c7fdbe136b", sha)
    }

    @Test
    fun `table is present and complete`() {
        assertEquals(1, table.get("version").asInt())
        assertEquals(29, table.get("equivalence").size(), "equivalence cases")
        assertEquals(10, table.get("rejection").size(), "rejection cases")
        assertEquals(13, table.get("canonicalKey").size(), "canonicalKey cases")
    }

    /**
     * The whitespace cases that the first version of this slice got wrong are asserted by id, so
     * removing them fails loudly.
     */
    @Test
    fun `whitespace contract cases are present by id`() {
        val keys = table.get("canonicalKey").map { it.get("id").asText() }.toSet()
        val rejections = table.get("rejection").map { it.get("id").asText() }.toSet()
        for (id in listOf("BORDER_EM_SPACE", "BORDER_NBSP", "BORDER_ZWSP", "INTERNAL_NBSP")) {
            assertTrue(id in keys, "canonicalKey case $id was removed from the fixture")
        }
        for (id in listOf("EM_SPACE_ONLY", "NBSP_ONLY", "ZWSP_ONLY")) {
            assertTrue(id in rejections, "rejection case $id was removed from the fixture")
        }
    }

    @Test
    fun `every canonical key case produces the pinned value`() {
        for (case in table.get("canonicalKey")) {
            val id = case.get("id").asText()
            assertEquals(
                case.get("key").asText(),
                MarketTextKey.from(case.get("input").asText()).value,
                "case $id: pinned key does not match"
            )
        }
    }

    @Test
    fun `every equivalence case matches the rule`() {
        for (case in table.get("equivalence")) {
            val id = case.get("id").asText()
            val left = MarketTextKey.from(case.get("left").asText())
            val right = MarketTextKey.from(case.get("right").asText())
            assertEquals(
                case.get("equal").asBoolean(),
                left == right,
                "case $id: expected equal=${case.get("equal").asBoolean()} " +
                    "but got left=[${left.value}] right=[${right.value}]"
            )
        }
    }

    @Test
    fun `every rejection case matches the rule`() {
        for (case in table.get("rejection")) {
            val id = case.get("id").asText()
            val input = case.get("input").asText()
            if (case.get("rejected").asBoolean()) {
                assertFailsWith<IllegalArgumentException>("case $id: expected rejection") {
                    MarketTextKey.from(input)
                }
            } else {
                assertTrue(
                    MarketTextKey.from(input).value.isNotEmpty(),
                    "case $id: expected acceptance"
                )
            }
        }
    }
}
```

That is **6** tests.

- [ ] **Step 9: Run the contract test and the full module suite**

```bash
./gradlew.bat test --tests '*MarketTextKeyParityTest*'
./gradlew.bat test
```

Expected: contract test PASS (6 tests); full suite PASS with **no pre-existing test newly failing**.
Record the total test count before and after — it must rise by exactly **19** (13 behaviour + 6
contract).

- [ ] **Step 10: Confirm the dependency boundary held**

```bash
grep -rnE "android|androidx|io\.ktor|org\.postgresql|compose" \
  src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/
```

Expected: **empty**. S1a is pure Kotlin/JVM.

```bash
grep -rn "priceintelligence" src/main/kotlin --include=*.kt | grep -v "/priceintelligence/"
```

Expected: **empty**. Nothing outside the new package references it yet — S1a wires into nothing.

- [ ] **Step 11: Commit — only if the user has explicitly asked**

Repository rule: no commit, branch, worktree or push without an explicit request. If asked:

```bash
git add contracts/fixtures/text-key src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence
git commit -m "feat(priceintelligence): market text key with cross-repo contract fixture"
```

---

## Definition of done

Backend side:

1. `MarketTextKey` exists, is `@JvmInline`, has a private constructor and a `from(String)` factory,
   and trims with Kotlin's `String.trim()`.
2. All **13** behaviour tests pass.
3. All **29** equivalence, **10** rejection and **13** `canonicalKey` cases in the fixture pass
   (`MarketTextKeyParityTest` PASS).
4. The fixture's LF-normalised SHA-256 is
   `aae747269ff423a7d2e38a93533a11b5d378ab3f7a902fe37d87b3c7fdbe136b`, and the contract test pins it.
5. `ReceiptAnalysisResultSchemaTest` still passes, unchanged.
6. The full backend suite passes, with exactly **19** new tests and none newly failing.
7. Both greps in Step 10 return empty: no database dependency and no network dependency.
8. No backend file outside the five listed was created or modified.
9. The canonical fixture is **committed** in the backend; that commit is the origin of the app copy.

`S1a_COMPLETE` — the same definition as the app plan — additionally requires:

10. `ProductNameMatchKeyContractTest` (app) PASS;
11. the **same fixture** in both repositories, copied from the commit in item 9;
12. the **same SHA-256** pinned by both contract tests;
13. no database and no network dependency on the app side either, and no app production code changed.

**A green backend alone never completes S1a.** S1 as a whole is COMPLETE only when S1a and S1b are
both COMPLETE, and S2 stays blocked until then.

## Risks

| # | Risk | Mitigation |
| --- | --- | --- |
| R-1.1 | The two implementations drift after S1a | The same fixture, pinned by the same hash, is tested in both repositories |
| R-1.2 | Someone "fixes" a failing case by editing the fixture | The README states it explicitly: fix the code, never the fixture; the hash test fails on any edit |
| R-1.3 | An editor silently normalises the Unicode in the fixture | The fixture is pure ASCII; every non-ASCII or control character is a `\uXXXX` escape |
| R-1.4 | Internal NBSP from scraped HTML never matches app-side names | **Out of S1a by design.** Sanitise at the adapter boundary (S9), never in the identity rule |
| R-1.5 | A contributor replaces Kotlin's `trim()` with `java.lang.String.trim()` or `strip()` | `BORDER_NBSP`, `NBSP_ONLY` and the border behaviour test fail; the KDoc forbids it |
| R-1.6 | The app's rule changes and only one copy of the fixture is updated | Both copies pin the same hash; changing expected values requires regenerating from the app and updating both copies and both hashes together |
| R-1.7 | Expected values were derived by reading instead of executing, as in the first version of this plan | Values are generated from the compiled app class; the app contract test re-proves them on every app build |

## Dependencies

**Upstream:** none inside M5. S1a is a root of the DAG, alongside S1b.
**Downstream:** S2 (`ProductKey`) consumes `MarketTextKey.from`, and only after **S1 = S1a + S1b** is
COMPLETE. Nothing else in S1a's scope.
**External:** Jackson `ObjectMapper` — already a backend dependency, used by
`ReceiptAnalysisResultSchemaTest`. `java.security.MessageDigest` from the JDK. No new dependency.
**Cross-repository:** the app contract test (separate app change) is required for S1a completion.

## Open decisions carried into execution

**None for S1a.** The border-whitespace question was re-decided on 2026-09-17: parity with the real
app behaviour, `LEGACY_CANONICAL_BEHAVIOR` withdrawn.

Deferred, and explicitly **not** to be resolved inside S1a:

- **`INTERNAL_NBSP_NORMALIZATION`** — FUTURE / NOT_DECIDED. No behaviour change.
- **Source-side sanitisation of HTML entities and internal NBSP** — requirement of the source-adapter
  slice (S9), not of the identity rule.
- **S1b (`UnitNormalization`)** — part of S1, without a detailed plan yet. Not planned here.
- **OPD-1, OPD-4, OPD-6** — untouched by S1a. If any step appears to require one, **stop** and raise
  it rather than choosing a value.
