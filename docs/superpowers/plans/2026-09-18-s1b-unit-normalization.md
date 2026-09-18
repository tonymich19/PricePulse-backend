# S1b — `UnitNormalization` — Implementation Plan

**Status: `APPROVED_BY_PO`** (2026-09-18). Decisions D1–D9 taken by the product owner on 2026-09-18.
Approved is not authorized: each task still needs explicit PO authorization before it runs.

Design: `docs/superpowers/specs/2026-09-18-s1b-unit-normalization-design.md` (`APPROVED_BY_PO`).
Parent: `../PricePulse/docs/product-development/plans/price-intelligence-foundation-v1.md` §6 S1b.

> **For agentic workers:** execute with superpowers:executing-plans or
> superpowers:subagent-driven-development, test-first (superpowers:test-driven-development). Steps use
> checkbox (`- [ ]`) syntax.

**Goal:** a pure backend function that turns the package-measure expression of a market product name
into a canonical `NormalizedPackageMeasure`, or refuses explicitly with `Absent` / `Ambiguous`.

**Architecture:** one production file in `application/priceintelligence/identity/`, no dependency on
any other slice, no I/O. **Backend only** (D5).

**Tech stack:** Kotlin/JVM 2.2.10, `kotlin.test` on JUnit 5, `java.math.BigDecimal`.

---

## Execution preconditions

S1b may not start until **all** hold:

1. The product owner approves the design (including the alias table, design §6.4) and this plan.
2. A backend branch is created from `origin/main` **with explicit PO authorization** (backend
   `CLAUDE.md`: no branch, commit or push unless asked). Suggested: `feat/m5-s1b-unit-normalization`.
3. The test baseline of Task 0 is captured **before the first edit**.

F1 (`FULL_TEXT_IDENTITY_PARITY = NOT_ESTABLISHED`) is **not** a precondition of S1b. It is a gate
before the implementation of S2 (design §14). Resolved on 2026-09-18 by ADR-016
(`F1 = RESOLVED_BY_ADR_016`; see design §14).

## Global constraints

- **Pure Kotlin/JVM.** No dependency on Ktor, Postgres, Android, `MarketTextKey`, or any other slice.
- **No fuzzy matching**, no typo tolerance, no stemming. Closed alias table (D6). No `m`, `mg`, `cl`.
- **Never round.** `BigDecimal` + `longValueExact()`; refuse what is not exact (D7).
- **Never guess a number format.** Design §6.3, rules N1–N3 (D8).
- **Never default to «single» over unparsed pack language** (D3). **Never multiply `quantity` by
  `packCount`** (D4).
- **Backend only** (D5): no file under `contracts/`, no app change, no shared module, no cross-repo
  import, no fixture.
- **No bare `unit` concept** in type, field or function names (D9).
- **No scope creep:** no `ProductKey`, `AttributeSignature`, `ProductIdentityLevel`, price per unit,
  adapter, schema, migration or HTTP change. No work on F1.
- Backend test convention: `kotlin.test`, backticked names.

## Assumption audit

Re-run on 2026-09-18 **after** incorporating D1–D9.

| Assumption | Evidence inspected | Class | Planning consequence |
| --- | --- | --- | --- |
| No measure model exists in the backend | `git grep NormalizedUnit\|UnitKind\|normalizeUnit` on `origin/main` `1c9f791`: none; `identity/` holds only `MarketTextKey` | SUPPORTED | New types; no implementer, fake, DI binding or caller to update |
| Nothing depends on `identity/` in production | `git grep MarketTextKey -- src/main`: only its own file | SUPPORTED | Adding a file affects no call site |
| **ADR-015 D4 freezes the type name** | ADR-015 lines 41–43: components «tamanho + unidade + contagem de pacote», no type name | NOT_SUPPORTED | D9 needs **no ADR change** |
| **The delivery plan freezes `NormalizedUnit`** | delivery plan line 188 is the only occurrence in the app and backend docs (`grep` on both) | SUPPORTED_WITH_LIMITS | Divergence recorded in design §10; follow-up F3 in the app after approval. Not an ADR, not an authority-gated path |
| **D9 names avoid the receipt «unit»** | `PurchaseItem.unit`, receipt `unit`, `ReceiptQuantityConversion` (label of the purchased quantity) | SUPPORTED | `NormalizedPackageMeasure`, `measureUnit`, `MeasureUnit`, `normalizePackageMeasure` |
| **D7: decimal input, integer canonical result** | app precedent `toScaledQuantityOrNull` (refuse, never round; ADR-011 d170) | SUPPORTED | `movePointRight(3)` for kg/l, `stripTrailingZeros`, `longValueExact()` |
| D7 covers every evidenced size | all evidenced sizes are integers in g/ml after conversion | SUPPORTED | `0,5 kg`, `1.5 L`, `0,33 L` accepted; `1,5 g` refused (needs `mg`) |
| **D8: `.` and `,` both decimal when unambiguous** | PO decision; real pt-BR weights also use 3 decimals (`2,345` KG on receipt R004) — on *purchased* quantity, out of scope, but the shape exists | SUPPORTED_WITH_LIMITS | Rule N2 refuses `1,250 kg` and `2.500 ml` too: coverage lost, never a wrong value |
| **D8: rule N2 treats both separators alike** | PO: «1.000 g» must be neither 1 g nor 1000 g; the symmetric `1,000 g` has the same two readings | SUPPORTED | One rule for both separators; the character alone never decides |
| `INT = 0` can never be a thousands group | arithmetic of grouped numerals | SUPPORTED | `0,250 kg` → 250 g, `0.330 L` → 330 ml |
| **Spans: S2 must not re-parse sizes** | D2; ADR-012 D7 principle (one notion of equality) | SUPPORTED | Spans index the **original** input, ascending, non-overlapping; tested |
| Spans alone make `500g` ≡ `0,5 kg` at identity level | S2 not designed yet; S2 must strip spans **and** key the rest | SUPPORTED_WITH_LIMITS | S1b guarantees the spans; how S2 composes them is S2's design |
| **`quantity` vs `packCount`** | D4; F-04 (multipack priced ~4× as unit) | SUPPORTED | `(90, MASS, 6)` ≠ `(90, MASS, 1)` ≠ `(540, MASS, 1)`; count-only → `(N, COUNT, 1)` |
| Pack grammar is backed by real listing titles | probe Pinheiros paraphrases «Dove 90g 6un»; GPV quotes the combo | SUPPORTED_WITH_LIMITS | Unparsed pack language → `Ambiguous` (D3) |
| The app has a package-measure rule to be in parity with | `RuleBasedProductNameNormalizer`: textual `500G`→`500 g`, integer only, no conversion, no pack | NOT_SUPPORTED | Backend only (D5) |
| Source decoding (NBSP, HTML) is S1b's job | S1a plan, «Source-side sanitisation belongs to the adapter boundary» | NOT_SUPPORTED | S9's job; internal NBSP yields `Absent` |
| No persistence, receipt or HTTP impact | parent plan §5: S1–S9 touch no database or network; receipt contract unchanged | SUPPORTED | Pure unit tests only |
| **F1 blocks S1b** | S1b does not use text identity at all | NOT_SUPPORTED | F1 is a gate before S2 implementation, recorded in design §14 |
| Backend has a completion harness | `origin/main` has no `scripts/verify.ps1`; agentic A1.8 not started | SUPPORTED | Gate = targeted tests + full suite against a pre-edit baseline |
| Construction style | `MarketTextKey` (private ctor + `from`); app `Quantity` (`init { require }`) | SUPPORTED | `NormalizedPackageMeasure` validates in `init`, so `copy()` cannot bypass it |
| Test style | `MarketTextKeyTest`: `kotlin.test`, backticked names | SUPPORTED | Same |

**OPEN_DECISION:** none structural. The pt-BR aliases outside the evidenced set (design §6.4) are
submitted for approval together with the design.

## File Structure

| File | Action | Task |
| --- | --- | --- |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/UnitNormalization.kt` | Criar. `MeasureUnit`, `NormalizedPackageMeasure`, `PackageMeasureResult`, `PackageMeasureAmbiguity`, `normalizePackageMeasure` | 1–7 |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/UnitNormalizationTest.kt` | Criar. All S1b tests | 1–7 |
| `docs/superpowers/specs/2026-09-18-s1b-unit-normalization-design.md` | Criar. Design da S1b | — |
| `docs/superpowers/plans/2026-09-18-s1b-unit-normalization.md` | Criar. Este plano | — |

No other file changes. Nothing in the app repository.

---

## Task 0: Baseline — before any edit

- [ ] **Step 1: Confirm the base.** `git rev-parse HEAD origin/main` and `git status --porcelain`.
Expected: HEAD equals `origin/main`; working tree clean except the design and this plan.
- [ ] **Step 2: Capture the full-suite baseline.** `./gradlew.bat test --continue`. Record total,
passed, failed and the **names** of the failing tests, outside the working tree or under an ignored
path; never commit it. Expected at plan time: 427 tests, 2 failures —
`PostgresReceiptAnalysisOperationStoreTest` and `PostgresSchemaContractTest` (no Docker environment).
- [ ] **Step 3: STOP if the baseline differs** from the expected in any way other than environment, and
report it before writing code.

## Task 1: Types and construction invariants

**Files:** Create `UnitNormalization.kt`, `UnitNormalizationTest.kt`.

- [ ] **Step 1: RED**

```kotlin
package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class UnitNormalizationTest {

    @Test
    fun `rejects non-positive package content`() {
        assertFailsWith<IllegalArgumentException> { NormalizedPackageMeasure(0, MeasureUnit.MASS, 1) }
        assertFailsWith<IllegalArgumentException> { NormalizedPackageMeasure(-1, MeasureUnit.MASS, 1) }
    }

    @Test
    fun `rejects a pack count below one`() {
        assertFailsWith<IllegalArgumentException> { NormalizedPackageMeasure(90, MeasureUnit.MASS, 0) }
    }

    @Test
    fun `copy cannot bypass the invariants`() {
        val valid = NormalizedPackageMeasure(90, MeasureUnit.MASS, 1)
        assertFailsWith<IllegalArgumentException> { valid.copy(packCount = 0) }
    }

    @Test
    fun `multipack never equals the single unit`() {
        assertNotEquals(
            NormalizedPackageMeasure(90, MeasureUnit.MASS, 1),
            NormalizedPackageMeasure(90, MeasureUnit.MASS, 6)
        )
    }

    @Test
    fun `90 g times 6 is never 540 g`() {
        assertNotEquals(
            NormalizedPackageMeasure(540, MeasureUnit.MASS, 1),
            NormalizedPackageMeasure(90, MeasureUnit.MASS, 6)
        )
    }
}
```

Run: `./gradlew.bat test --tests '*UnitNormalization*'` — Expected: FAIL (types missing).

- [ ] **Step 2: GREEN** — `MeasureUnit`, `NormalizedPackageMeasure` with `init { require(...) }`, the
sealed `PackageMeasureResult`, `PackageMeasureAmbiguity` in the declared order of design §6, and a
`normalizePackageMeasure` returning `Absent`. Run again — Expected: PASS.

## Task 2: Sizes and exact conversion

- [ ] **Step 1: RED** — `normalizePackageMeasure(input)` returns `Normalized` with this measure:

| Input | Expected | Origin |
| --- | --- | --- |
| `Cafe 500 g` | `(500, MASS, 1)` | mandatory |
| `Cafe 500g` | `(500, MASS, 1)` | mandatory (glued) |
| `Cafe 0,5 kg` | `(500, MASS, 1)` | mandatory |
| `Cafe 0.5 kg` | `(500, MASS, 1)` | mandatory |
| `Suco 1,5 L` | `(1500, VOLUME, 1)` | mandatory |
| `Suco 1.5 L` | `(1500, VOLUME, 1)` | mandatory |
| `Refrigerante 330 ml` | `(330, VOLUME, 1)` | mandatory |
| `Refrigerante 0,33 L` | `(330, VOLUME, 1)` | D7 example |
| `ARROZ BRANCO 5KG` | `(5000, MASS, 1)` | mandatory; contract fixture `complete-with-items.json` |
| the eleven Carrefour names of design §5.2 | as listed there | `carrefour-canonical.json` |

Plus: `Cafe 500 g`, `Cafe 500g`, `Cafe 0,5 kg`, `Cafe 0.5 kg` produce **equal** measures; one test per
alias of design §6.4 (e.g. `Arroz 5 quilos` → `(5000, MASS, 1)`, `Leite 1 lt` → `(1000, VOLUME, 1)`).

Expected: FAIL.

- [ ] **Step 2: GREEN** — tokenizer on `\p{javaWhitespace}` keeping original indices; `NUMBER MEASURE`
glued or separated by one whitespace run, at token boundaries; closed alias table;
`BigDecimal` → `movePointRight(3)` for kg/l → `stripTrailingZeros()` → `longValueExact()`.
Expected: PASS.

## Task 3: Decimal grammar (D8, design §6.3)

- [ ] **Step 1: RED**

| Input | Expected | Rule |
| --- | --- | --- |
| `Arroz 1.000 g` | `Ambiguous(AMBIGUOUS_DECIMAL_SEPARATOR)` — neither 1 g nor 1000 g | N2, mandatory |
| `Arroz 1,000 g` | `Ambiguous(AMBIGUOUS_DECIMAL_SEPARATOR)` | N2 |
| `Agua 2.500 ml` | `Ambiguous(AMBIGUOUS_DECIMAL_SEPARATOR)` | N2 |
| `Queijo 1,250 kg` | `Ambiguous(AMBIGUOUS_DECIMAL_SEPARATOR)` | N2 |
| `Arroz 1.000,5 kg` | `Ambiguous(AMBIGUOUS_DECIMAL_SEPARATOR)` | N1 |
| `Arroz 1.000.000 g` | `Ambiguous(AMBIGUOUS_DECIMAL_SEPARATOR)` | N1 |
| `Queijo 0,250 kg` | `(250, MASS, 1)` | N3, `INT = 0` |
| `Suco 0.330 L` | `(330, VOLUME, 1)` | N3, `INT = 0` |
| `Racao 12.5 kg` | `(12500, MASS, 1)` | N3 |
| `Arroz 1000 g` | `(1000, MASS, 1)` | digits only |

All hypothetical inputs, labelled as such. Expected: FAIL.

- [ ] **Step 2: GREEN** — classify the number (N1 → N2 → N3) **before** any `BigDecimal` is built.
Expected: PASS.

## Task 4: Absence and traps — fail closed

- [ ] **Step 1: RED** — each returns `PackageMeasureResult.Absent`:

| Input | Why | Origin |
| --- | --- | --- |
| `PAO FRANCES KG` | sale unit, no number — no package size invented | mandatory; contract fixture; R002 |
| `ESPONJA BRITE 3M` | `M` is not a measure | mandatory; R005 |
| `Arroz Tipo 1` | `1` is not a size | mandatory |
| `Cafe 252 Graus` | not a measure | mandatory |
| `LEITE LV ELEGE LT` | measure word, no number | C001 |
| `MUSCULO BOV RESF P.KG` | not at a token boundary | C001 |
| `ACHOC LIQ T.T.TP 200` | number, no measure | C001 |
| `HIDRAT JOHNSONS SOFT` | nothing | R005 |
| `Arroz 500 g` | internal NBSP is not a separator (S9 decodes) | hypothetical |
| `""`, `"   "` | blank | — |

And the positives that contain the same traps: `Arroz Branco Camil Tipo 1 5kg` → `(5000, MASS, 1)`;
`Cafe Torrado e Moido Pilao 252 Graus Vacuo 500g` → `(500, MASS, 1)`; `VAGEM EMB / 250G` →
`(250, MASS, 1)` (C001).

- [ ] **Step 2: GREEN** — boundaries enforced; measure words without a number ignored; NBSP not a
separator. Expected: PASS.

## Task 5: Pack and count-only content (D3, D4)

- [ ] **Step 1: RED**

| Input | Expected | Origin |
| --- | --- | --- |
| `Papel Higienico com 4 Rolos` | `(4, COUNT, 1)` | mandatory |
| `Papel Higienico Mimmo Folha Dupla com 4 Rolos` | `(4, COUNT, 1)` | `carrefour-canonical.json` |
| `Sabonete Dove 90g` | `(90, MASS, 1)` | mandatory |
| `Sabonete Dove 90g 6un` | `(90, MASS, 6)` | mandatory; probe Pinheiros (paraphrase) |
| `Sabonete Dove 6un 90g` | `(90, MASS, 6)` | order-independent, hypothetical |
| `Cerveja 6 x 350 ml` / `Cerveja 6x350ml` | `(350, VOLUME, 6)` | hypothetical |
| `Ovos Brancos 12 unidades` | `(12, COUNT, 1)` | hypothetical |

And the explicit inequalities:

```kotlin
@Test
fun `Dove 90g 6un is not Dove 90g`() {
    // Compare the measures, not the results: the results would differ by their spans alone, and the
    // test would pass even if the multipack collapsed into the single unit.
    val single = normalizePackageMeasure("Sabonete Dove 90g") as PackageMeasureResult.Normalized
    val multipack = normalizePackageMeasure("Sabonete Dove 90g 6un") as PackageMeasureResult.Normalized
    assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 1), single.measure)
    assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), multipack.measure)
    assertNotEquals(single.measure, multipack.measure)
}

@Test
fun `six times 90 g is not 540 g`() {
    val multipack = normalizePackageMeasure("Sabonete 6 x 90g") as PackageMeasureResult.Normalized
    val single = normalizePackageMeasure("Sabonete 540g") as PackageMeasureResult.Normalized
    assertEquals(NormalizedPackageMeasure(90, MeasureUnit.MASS, 6), multipack.measure)
    assertNotEquals(single.measure, multipack.measure)
}
```

- [ ] **Step 2: GREEN** — `N x SIZE` and a count expression next to a size set `packCount`; count-only
sets `COUNT`; never multiply. Expected: PASS.

## Task 6: Ambiguity — every reason

- [ ] **Step 1: RED** — each input triggers **exactly one** reason (design §8: the reason is
diagnostic; tests never depend on precedence):

| Input | Reason | Origin |
| --- | --- | --- |
| `Refrigerante Coca-Cola 2 L e Fanta Laranja 2 L` | `MULTIPLE_SIZES` | GPV §G-03 |
| `Arroz 500g 1kg` | `MULTIPLE_SIZES` | hypothetical |
| `Ovos 12 unidades 6un` | `MULTIPLE_SIZES` | hypothetical |
| `Sabonete Leve 3 Pague 2 90g` | `UNPARSED_PACK_SIGNAL` | hypothetical (promotion is S5) |
| `Refrigerante Pack 2L` | `UNPARSED_PACK_SIGNAL` | hypothetical |
| `V CHEIRO VERDE MATEUS A UN` | `UNPARSED_PACK_SIGNAL` | receipt R004 — bare count word |
| `Tempero 1,5 g` / `Essencia 0,5 ml` / `Ovos 1,5 un` | `NON_EXACT_QUANTITY` | hypothetical; `1,5 g` would need `mg` |
| `Sabonete 1,5 x 90g` | `NON_EXACT_QUANTITY` | hypothetical; `packCount` must be integer |
| `Arroz 0 g` / `Arroz 0x 1kg` | `ZERO_QUANTITY` | hypothetical |
| `Arroz 99999999999999999999 kg` / `Sabonete 99999999999 x 90g` | `OUT_OF_RANGE` | hypothetical |
| `Arroz 1.000 g` | `AMBIGUOUS_DECIMAL_SEPARATOR` | covered in Task 3 |

Plus: `normalizePackageMeasure` never throws for any of these.

- [ ] **Step 2: GREEN.** Expected: PASS.

## Task 7: Spans for S2 (D2)

- [ ] **Step 1: RED**

```kotlin
@Test
fun `spans point to the matched expressions in the original input`() {
    val input = "Sabonete Dove 90g 6un"
    val result = normalizePackageMeasure(input) as PackageMeasureResult.Normalized
    assertEquals(listOf("90g", "6un"), result.spans.map { input.substring(it) })
}

@Test
fun `span covers the whole pack expression`() {
    val input = "Cerveja 6 x 350 ml"
    val result = normalizePackageMeasure(input) as PackageMeasureResult.Normalized
    assertEquals(listOf("6 x 350 ml"), result.spans.map { input.substring(it) })
}

@Test
fun `spans keep the original spelling and case`() {
    val input = "ARROZ BRANCO 5KG"
    val result = normalizePackageMeasure(input) as PackageMeasureResult.Normalized
    assertEquals(listOf("5KG"), result.spans.map { input.substring(it) })
}
```

Also: spans are ascending and never overlap; `com 4 Rolos` spans `4 Rolos`.

- [ ] **Step 2: GREEN.** Expected: PASS.

## Task 8: Verification and stop

- [ ] **Step 1:** `./gradlew.bat test --tests '*MarketTextKey*' --tests '*UnitNormalization*'` — the S1
verification line of the delivery plan. Expected: all green.
- [ ] **Step 2:** `./gradlew.bat test --continue` — Expected: Task 0 baseline plus the new tests; the
**same** failing set and nothing else.
- [ ] **Step 3:** `git status --porcelain` shows only the four files of the File Structure.
- [ ] **Step 4: STOP.** Commit, push and PR are `HUMAN_AUTHORITY`. Report and wait.

## Definition of done

Design §15. In short: design and plan approved; tests written first and green; full suite equal to the
baseline plus the new tests; integrated through the normal PR flow with PO authorization. **Only then**
S1 = `COMPLETE`. S2 becomes plannable; its implementation still waits for the F1 gate (resolved by
ADR-016 on 2026-09-18).

## Risks

| Risk | Mitigation |
| --- | --- |
| Real multipack titles differ from the paraphrased evidence | D3: unparsed pack language → `Ambiguous`. Revisit the grammar with the first S9 data |
| Rule N2 refuses legitimate `1,250 kg` / `2.500 ml` | Accepted by D8: coverage lost, never a wrong value. Measure with S9 data |
| `Arroz Tipo 1 kg` would read as `1 kg` | Not in any evidence (every real `Tipo 1` is followed by a brand or a glued size). Recorded; no heuristic added |
| Sale unit read as package measure | Design §4.3; D9 names; S1b never reads receipt `unit`; `PAO FRANCES KG` test |
| Alias creep | Closed table; any alias beyond design §6.4 is a PO-approved change with a test |

## Out of scope

`ProductKey`, `AttributeSignature`, `ProductIdentityLevel` (S2); adapters and source decoding (S9);
price per unit; promotions (S5); receipt-side use; any app change; F1 — recorded in the design as a
gate before S2, not handled here; F3 — the follow-up to the app delivery plan, after approval.
