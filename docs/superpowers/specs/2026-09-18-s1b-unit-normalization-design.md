# S1b — `UnitNormalization` — Design

**Status: `APPROVED_BY_PO`** (2026-09-18), including the alias table of §6.4. Decisions D1–D9 in
§13; ADR-015 D4 needs no change. Approval authorizes no execution by itself: each step of the plan
still needs explicit PO authorization.

Milestone 5, slice S1, capability S1b. **Backend only** (D5). Parent documents, all approved and in the
app repository `../PricePulse`:

- delivery plan `docs/product-development/plans/price-intelligence-foundation-v1.md` §6 S1b;
- specification `docs/product-development/specs/price-intelligence-foundation-v1.md` §6.2, §13;
- ADR-015 **D4** (`ProductKey` = GTIN, else brand + canonical name + variant + size + unit + pack
  count).

S1a (`MarketTextKey`) is `COMPLETE`. **S1 is complete only when S1a and S1b are both complete, and S2
(`ProductKey`) stays blocked until then** — and, independently, until the F1 gate of §14 is resolved.

---

## 1. Problem

`ProductKey` without a GTIN is an attribute signature that includes **size, unit and pack count**.
Carrefour exposes no GTIN (POC B: `GTIN Coverage = 0/15`), so for that source identity rests entirely
on the signature. Two failures observed in the POCs make the size/pack part load-bearing:

| Failure | Evidence | What went wrong |
| --- | --- | --- |
| F-04 multipack as unit | `test-data/price-intelligence/national-chain-data-coverage/probes/carrefour-basket-pinheiros.md` | «sabonete» matched a 6-unit multipack at R$ 19,49 instead of an 85–90 g bar; «Dove 90g 6un» at R$ 29,09 sat next to the single «Dove 90g» at R$ 5,19. Price inflated ~4× |
| G-03 combo | `test-data/price-intelligence/geographic-price-variation/GEOGRAPHIC-PRICE-VARIATION.md` §G-03 | «Coca-Cola 2 L **e** Fanta 2 L» passed a naive size regex as cola 2 L |

The same physical content is also written in different ways by different sources (`500g`, `500 g`,
`0,5 kg`, `0.5 kg`; `1L`, `1 Litro`, `2 L`), which text equality (`MarketTextKey`) deliberately does
**not** equate: it preserves numbers and units as written.

**S1b turns the package-measure expression inside a market product name into one canonical,
comparable value, and refuses — explicitly — whenever it cannot do so safely.**

## 2. Goals

1. `500 g`, `500g`, `0,5 kg` and `0.5 kg` produce the **same** normalized measure.
2. A multipack **never** produces the same measure as the single unit, and `90 g × 6` is never
   collapsed into `540 g`.
3. A combo, or any name with more than one size, **never** produces a measure.
4. No rounding, ever. What cannot be represented exactly is refused.
5. A number whose format is ambiguous (`1.000 g`) is refused, never guessed.
6. Deterministic and pure: same input, same output; no clock, no I/O, no locale dependence.

## 3. Scope

**IN_SCOPE:** package measure — mass, volume, count; multipack; spans; exact dimensional conversion;
fail-closed; the `Absent` / `Ambiguous` distinction.

**OUT_OF_SCOPE:**

| Not S1b | Why / where it belongs |
| --- | --- |
| Purchased quantity (`2`, `0,420`) | Property of a purchase event. App `Quantity`, receipt `quantity` |
| Receipt sale unit (`UN`, `KG` on a receipt line) | App `PurchaseItem.unit` / receipt `unit`: a *label of the purchased quantity* (PO decision 2026-09-01). Different concept; §4.3 |
| Total price; price per kg / per litre | Price domain (S4+). May later be derived from S1b output; not computed here |
| Product identity, `ProductKey`, S2 | S1b supplies one input |
| Receipt ingestion; receipts as input | S1b's consumer is market-source names. Receipt lines are only adversarial test evidence |
| Presentation / display text | S1b never formats |
| HTML entities, NBSP, source decoding | Source adapter boundary (S9), as in S1a |
| Length (`m`, `cm`), `mg`, `cl` | Not supported in this slice (D6). `3M` is a brand in real data |
| Promotions («leve 3 pague 2») | S5. S1b only refuses to read them as a pack |
| Any change to the app, including `RuleBasedProductNameNormalizer` | D5, F1 |

## 4. Current behaviour (repository evidence)

### 4.1 Backend (`origin/main` `1c9f791`)

- **No measure model exists.** No `NormalizedUnit`, `UnitKind`, `normalizeUnit`, or any size/pack type.
- `application/priceintelligence/identity/` contains only `MarketTextKey` (S1a), with **no production
  consumer** yet.
- The receipt contract (`contracts/receipt-analysis-result.v1.schema.json`) carries item `quantity`
  (canonical positive decimal string) and `unit` (free non-blank text). It is a wire contract of the
  receipt feature, **not** an input of S1b, and S1b does not touch it.
- There is **no `verify.ps1` harness** in the backend (agentic phase A1.8 not started).

### 4.2 App (`origin/main` `9d7ac6e`)

- `Product(id, name)` — **no size, unit or pack field**. Package size exists only inside the name text.
- `PurchaseItem.unit: String?` (SQLite `purchase_item.unit TEXT`) — the receipt's sale-unit label.
- `Quantity(amountScaled: Long)`, scale 4, **refuses, never rounds** (ADR-011 decision 170), through one
  rule, `BigDecimal.toScaledQuantityOrNull()`.
- `ReceiptUnit {UN, KG, G, L, ML}` — used only by the on-device OCR interpreter, which has **no
  production consumer** (`InterpretReceiptUseCase` is bound in DI and never injected).
- `RuleBasedProductNameNormalizer` — a **textual** rule on the live matching path: splits a glued
  integer+unit token (`500G` → `500 g`) for `g`, `kg`, `ml`, `l`, lowercases the unit, and expands a
  small abbreviation dictionary. **No conversion, no pack, integer only.** Its output feeds
  `ProductNameMatchKey` (see F1, §14).
- **The app has no package-size normalization and never computes `ProductKey`** (ADR-015 D4).

### 4.3 The word «unit» means two different things — and why S1b avoids it (D9)

| | Receipt / app «unit» | S1b package measure |
| --- | --- | --- |
| Example | `PAO FRANCES KG`, `quantity 0.42`, `unit KG` | `Arroz Branco Camil Tipo 1 5kg` |
| Meaning | how the purchased quantity is counted | net content of one sellable package |
| Owner | purchase / receipt | market product identity |

`ARROZ BRANCO 5KG`, `quantity 1`, `unit UN` (contract fixture `complete-with-items.json`) shows both at
once: the sale unit is `UN`, the package measure is `5KG`. **S1b must never read one as the other**,
and its types never use a bare `unit` name (D9).

## 5. Cases

### 5.1 Mandatory cases (PO, 2026-09-18)

| Input | Result |
| --- | --- |
| `500 g` | `(500, MASS, 1)` |
| `0,5 kg` | `(500, MASS, 1)` |
| `0.5 kg` | `(500, MASS, 1)` |
| `1,5 L` | `(1500, VOLUME, 1)` |
| `1.5 L` | `(1500, VOLUME, 1)` |
| `330 ml` | `(330, VOLUME, 1)` |
| `1.000 g` | `Ambiguous(AMBIGUOUS_DECIMAL_SEPARATOR)` — neither 1 g nor 1000 g |
| `ARROZ BRANCO 5KG` | `(5000, MASS, 1)` |
| `PAO FRANCES KG` | `Absent` — no package size is invented from the sale unit |
| `ESPONJA BRITE 3M` | `Absent` — `M` is not a unit |
| `Arroz Tipo 1` | `Absent` — `1` is not a size |
| `Cafe 252 Graus` | `Absent` — `252 Graus` is not a measure |
| `Papel Higienico com 4 Rolos` | `(4, COUNT, 1)` |
| `Sabonete Dove 90g` | `(90, MASS, 1)` |
| `Sabonete Dove 90g 6un` | `(90, MASS, 6)` |
| `Dove 90g 6un` vs `Dove 90g` | **not equal** |
| `Sabonete 6 x 90g` vs `Sabonete 540g` | **not equal** — `(90, MASS, 6)` ≠ `(540, MASS, 1)` |

### 5.2 Real input evidence

`SUPPORTED_BY_REPO` — literal strings present in the repositories:

| Input | Source | Result |
| --- | --- | --- |
| `Arroz Branco Camil Tipo 1 5kg` | `test-data/price-intelligence/geographic-price-variation/data/carrefour-canonical.json` | `(5000, MASS, 1)` |
| `Feijao Carioca Tipo 1 Kicaldo 1Kg` | idem | `(1000, MASS, 1)` |
| `Leite Integral Piracanjuba 1 Litro` | idem | `(1000, VOLUME, 1)` |
| `Cafe Torrado e Moido Pilao 252 Graus Vacuo 500g` | idem | `(500, MASS, 1)` |
| `Oleo de Soja Soya 900ml` | idem | `(900, VOLUME, 1)` |
| `Molho de Tomate Tradicional Tarantella Sache 300 g` | idem | `(300, MASS, 1)` |
| `Farinha de Trigo Integral Dona Benta Integral Premium 1 Kg` | idem | `(1000, MASS, 1)` |
| `Sabonete em Barra Dove Karite e Baunilha 90g` | idem | `(90, MASS, 1)` |
| `Papel Higienico Mimmo Folha Dupla com 4 Rolos` | idem | `(4, COUNT, 1)` |
| `Refrigerante Coca-Cola Garrafa 2 L` | idem | `(2000, VOLUME, 1)` |
| `Agua Sanitaria Ype 1L` | idem | `(1000, VOLUME, 1)` |
| `ARROZ BRANCO 5KG` | backend contract fixture `complete-with-items.json` | `(5000, MASS, 1)` |
| `ESPONJA BRITE 3M` | `test-data/receipt-analysis/cases/R005/truth.v1.json` | `Absent` |
| `PAO FRANCES KG` | backend contract fixture `partial-with-invalid-field.json`; R002 | `Absent` |
| `LEITE LV ELEGE LT` | `test-data/receipt-analysis/controls/C001/truth.v1.json` | `Absent` |
| `ACHOC LIQ T.T.TP 200` | C001 | `Absent` — number without unit |
| `MUSCULO BOV RESF P.KG` | C001 | `Absent` |
| `VAGEM EMB / 250G` | C001 | `(250, MASS, 1)` |

The first eleven are market-source names (S1b's real input domain); the rest are receipt lines, used
only as adversarial evidence.

`SUPPORTED_WITH_LIMITS` — described in the repository, not as the literal storefront title:

| Case | Evidence | Limit |
| --- | --- | --- |
| multipack «Dove 90g 6un» | probe Pinheiros | the POC author's paraphrase, not the listing title |
| 6-unit Protex multipack | probe Pinheiros | title not recorded |
| Coca-Cola 2 L 6-pack; 2-bottle combo | probe Pinheiros | titles not recorded |
| «Coca-Cola 2 L e Fanta Laranja 2 L» | GPV §G-03 | quoted in the report |

`HYPOTHETICAL_CASE` — used only where labelled in the tests: the decimal-grammar cases of §6.3, `6 x
350 ml`, `leve 3 pague 2`, `kit com 3`, `12 unidades`, `1,5 g`.

**Consequence:** the single-size grammar is well supported; the **pack grammar is partly
hypothetical**. The first real multipack titles arrive with the source adapter (S9). That is why D3
makes unrecognized pack language fail closed instead of defaulting to «single».

## 6. Domain contract

Package `com.tonycorreia.pricepulsebackend.application.priceintelligence.identity`, file
`UnitNormalization.kt` (the file named by the delivery plan).

```kotlin
/** Dimension of a package measure. Each value fixes its canonical base unit. */
enum class MeasureUnit { MASS, VOLUME, COUNT }       // grams, millilitres, units

/**
 * Canonical measure of a market package: the content of ONE sellable unit, in the base unit of
 * [measureUnit], and how many such units are sold together. Never the receipt's sale unit.
 */
data class NormalizedPackageMeasure(val quantity: Long, val measureUnit: MeasureUnit, val packCount: Int) {
    init {
        require(quantity > 0) { "quantity must be positive" }
        require(packCount >= 1) { "packCount must be at least 1" }
    }
}

sealed interface PackageMeasureResult {
    data class Normalized(val measure: NormalizedPackageMeasure, val spans: List<IntRange>) : PackageMeasureResult
    data object Absent : PackageMeasureResult
    data class Ambiguous(val reason: PackageMeasureAmbiguity) : PackageMeasureResult
}

enum class PackageMeasureAmbiguity {
    AMBIGUOUS_DECIMAL_SEPARATOR, OUT_OF_RANGE, ZERO_QUANTITY, NON_EXACT_QUANTITY,
    MULTIPLE_SIZES, UNPARSED_PACK_SIGNAL
}

fun normalizePackageMeasure(raw: String): PackageMeasureResult
```

### 6.1 Input

A canonical textual market product name, already decoded by the source adapter (S9), exactly as
`MarketTextKey` expects. A blank input is `Absent`.

### 6.2 Recognition rules

1. Case-insensitive, with `lowercase(Locale.ROOT)`. Token separators are `\p{javaWhitespace}` runs.
   Internal NBSP is **not** a separator (consistent with S1a; decoding belongs to S9): `500 g`
   is not recognized and yields `Absent`, never a wrong value.
2. **Size expression** = `NUMBER MEASURE`, glued (`500g`) or separated by one whitespace run (`500 g`,
   `1 Litro`), starting and ending at a token boundary. `MEASURE` must be in the closed alias table
   (§6.4). Anything else is not a size: `Tipo 1`, `252 Graus`, `3M`, `T.T.TP 200`, `P.KG`.
3. `NUMBER` follows the decimal grammar of §6.3.
4. **Pack expressions** (closed): `N x SIZE` / `NxSIZE` (with or without spaces around `x`), and a
   count expression `N un|und|unid|unidade|unidades` next to a size (`90g 6un`, `6un 90g`). Result:
   `packCount = N`. `N` must be an integer: a decimal `N` is `NON_EXACT_QUANTITY`.
5. **Count-only content** (D4): a count expression with **no** mass or volume size in the name
   (`com 4 Rolos`, `12 unidades`) → `NormalizedPackageMeasure(N, COUNT, 1)`. `packCount` is reserved
   for several equivalent units sold together; a count of rolls is the content of one package.
6. **Pack signal words** (D3): `pack`, `kit`, `fardo`, `caixa`, `cx`, `leve`, `combo`, `c/`, and any
   count word (`un`, `unidades`, `rolos`, …) **not consumed** by rules 4–5 →
   `Ambiguous(UNPARSED_PACK_SIGNAL)`. The absence of pack language means a single unit; *unparsed*
   pack language never does.
7. More than one size expression, or more than one count expression → `Ambiguous(MULTIPLE_SIZES)`. This
   covers the combo «2 L e 2 L».
8. `quantity` and `packCount` are **never multiplied** (D4): `6 × 90 g` is `(90, MASS, 6)`.

### 6.3 Decimal grammar (D8)

```text
NUMBER = INT [ SEP FRAC ]        INT = [0-9]+    SEP = ',' | '.'    FRAC = [0-9]+
```

Applied to the number of a size or pack expression, in this order:

| Rule | Condition | Result | Examples |
| --- | --- | --- | --- |
| N1 | more than one separator inside the number | `Ambiguous(AMBIGUOUS_DECIMAL_SEPARATOR)` | `1.000,5 kg`, `1,000.5 g`, `1.000.000 g` |
| N2 | one separator, `FRAC` has **exactly 3** digits, and `INT` is 1–3 digits **not starting with 0** — the number is also a valid thousands-grouped integer | `Ambiguous(AMBIGUOUS_DECIMAL_SEPARATOR)` | `1.000 g`, `1,000 g`, `2.500 ml`, `1,250 kg` |
| N3 | otherwise | the separator is the decimal point | `1,5` · `1.5` · `0,5` · `0.5` · `0,33` · `0.330` · `12,5` · `1000.5` |

- N2 applies to **both** separators. The number is refused only when its *text* has two legitimate
  readings; the separator character alone never decides.
- `INT = 0` can never be a thousands group, so `0,250 kg` and `0.330 L` are unambiguous (N3).
- An integer written with digits only (`1000 g`) never reaches N1/N2.

### 6.4 Alias table (closed; D6)

| Dimension | Canonical | Aliases — **bold** = seen in repository data |
| --- | --- | --- |
| MASS | g | **g**, gr, grs, grama, gramas |
| MASS | kg (×1000 → g) | **kg**, kgs, kilo, kilos, quilo, quilos, quilograma, quilogramas |
| VOLUME | ml | **ml** |
| VOLUME | l (×1000 → ml) | **l**, **lt** (receipts, without number), lts, **litro**, litros |
| COUNT | un | **un**, und, unid, unidade, unidades, rolo, **rolos** |

Aliases outside bold are pt-BR spellings **submitted for the PO's approval with this document**; each
one has a test. No fuzzy match, no typo tolerance, no stemming. **Not supported: `m`, `mg`, `cl`**, nor
any length unit.

### 6.5 Conversion and precision (D7)

1. Parse `NUMBER` into a `BigDecimal` (after §6.3), never a `Double`.
2. Scale to the base unit exactly: `movePointRight(3)` for `kg` and `l`; unchanged for `g`, `ml`, `un`.
3. The **canonical** value must be an exact positive integer: `stripTrailingZeros()` must leave scale
   ≤ 0, then `longValueExact()`.
4. Non-integer canonical value → `NON_EXACT_QUANTITY`. Zero → `ZERO_QUANTITY`. Overflow of `Long`
   (quantity) or `Int` (`packCount`) → `OUT_OF_RANGE`.

**The input may be decimal; only the canonical result must be integer.** `0,5 kg` → 500 g, `1.5 L` →
1500 ml, `0,33 L` → 330 ml. `1,5 g` is refused because representing it would need `mg`, which this
slice does not support. Never rounds.

### 6.6 Spans (D2)

`Normalized.spans` are the index ranges of the matched size and pack expressions **in the original
input**, ascending and non-overlapping. S2 uses them to remove from the textual component of the
identity what the measure already represents structurally. **S2 must not re-implement size parsing**:
a second notion of «the size token» is the duplication ADR-012 D7 forbids for text.

## 7. Invariants

1. Pure and deterministic; no dependency on `MarketTextKey`, locale, clock or I/O.
2. Equal content of one unit and equal pack count ⇔ equal `NormalizedPackageMeasure`.
3. `packCount` differs ⇒ measures differ. **Multipack never equals unit.** `(90, MASS, 6)` ≠
   `(540, MASS, 1)`.
4. `Normalized` is never produced for a name with two sizes, two counts, unparsed pack language, or an
   ambiguous number.
5. Never rounds. Never guesses a measure for a bare number. Never reads a measure word without a
   number. Never reads a receipt sale unit as a package measure.
6. `NormalizedPackageMeasure` cannot hold `quantity ≤ 0` or `packCount < 1` — also through `copy()`,
   because `init` runs there too.

## 8. Error and unknown behaviour

| Situation | Result | S2 consequence (for S2 to decide) |
| --- | --- | --- |
| no size and no pack language | `Absent` | S2 decides whether a signature without size is admissible |
| ambiguous | `Ambiguous(reason)` | expected: `AMBIGUOUS_IDENTITY`, never compared (parent spec §6.2) |
| blank input | `Absent` | — |

The `PackageMeasureAmbiguity` reason is **diagnostic**. When several apply, the one reported is the
first in the enum's declaration order; S2 must treat every `Ambiguous` the same way and never branch
on the reason.

`normalizePackageMeasure` never throws for any input string. Invalid *construction* of
`NormalizedPackageMeasure` throws `IllegalArgumentException`, like `MarketTextKey.from`.

## 9. Cross-repository contract (D5)

**Backend only.** The S1a pattern (byte-identical fixture, contract test in both repositories) existed
because the backend had to reproduce a rule **the app already executes** (ADR-012 D7). For package
measure there is no equivalent implementation in the app, and ADR-015 D4 puts the canonical catalog in
the backend. Therefore:

- no code in the app; no cross-repo fixture; no contract parity with the app; no shared module;
- no file under `contracts/`;
- the delivery plan's S1 verification line («mais o teste de contrato do app») refers to S1a's app test,
  already green.

If the app ever needs the same equivalence, that is a new decision, and the S1a mechanism is the
template.

## 10. Naming and compatibility with approved documents (D9)

| Approved document | What it says | Compatible? |
| --- | --- | --- |
| ADR-015 **D4** | signature components «tamanho + unidade + contagem de pacote» | **Yes.** The ADR names components, not types. `quantity` + `measureUnit` + `packCount` are those components. **No ADR change is required** |
| Parent spec §6.2 | same components | Yes |
| Delivery plan §6 S1b, line 188 | `normalizeUnit(raw: String): NormalizedUnit(quantity: Long, unit: UnitKind, packCount: Int)` | **Diverges**, by D1 (explicit result instead of a bare value) and D9 (names). File `UnitNormalization.kt`, test `UnitNormalizationTest.kt` and the verification filter `*UnitNormalization*` are kept |

The delivery plan is not an ADR and not an authority-gated path, and it states that each slice's
detailed plan is written right before execution. The divergence is recorded here, not silent. A
follow-up change to line 188 in the app repository is needed after this design is approved (F3). It is
**not** made now: this slice changes nothing in the app.

`normalizeUnit` becomes `normalizePackageMeasure` for the same reason as the type (D9): the function
returns a package measure, not a «unit». The capability keeps its name, S1b — `UnitNormalization`.

## 11. Migration and backward compatibility

None. New pure types; no schema, no migration, no HTTP contract, no persisted value. `MarketTextKey`
unchanged.

## 12. Test strategy

- `kotlin.test`, backticked names, table-driven where it helps — the backend convention.
- Every case of §5.1 and every `SUPPORTED_BY_REPO` case of §5.2 is a test, with its source in a comment.
- Hypothetical cases are labelled as such.
- Every rule of §6.3 (N1, N2, N3) has positive and negative tests; every alias of §6.4 has a test;
  every `PackageMeasureAmbiguity` value has at least one single-reason test.
- No JSON fixture (D5): there is no second consumer.
- Gate: targeted `*UnitNormalization*` tests, plus the full backend suite compared with a baseline
  captured **before the first edit** (expected at plan time: 427 tests, the same 2 pre-existing Docker
  failures). The backend has no `verify.ps1`.

## 13. Decisions (PO, 2026-09-18)

| # | Decision |
| --- | --- |
| D1 | Explicit result with three states — `Normalized`, `Absent`, `Ambiguous(reason)`. No nullable return. Fail-closed |
| D2 | S1b returns the spans of the recognized text. S2 removes them from the textual identity and never re-implements size parsing |
| D3 | Pack/multipack language that cannot be interpreted safely → `Ambiguous`. Never silently a single product |
| D4 | `com 4 Rolos` → `quantity 4, COUNT, packCount 1`. `packCount` is reserved for several equivalent units. `Dove 90g 6un` → `90, MASS, 6`. `90 g × 6` is never collapsed into `540 g` |
| D5 | Backend only. No app code, no cross-repo fixture, no parity with the app, no shared module |
| D6 | Closed alias table: evidenced aliases, or pt-BR spellings deliberately approved. No `m`, `mg`, `cl`. `ESPONJA BRITE 3M` keeps `M` as text |
| D7 | Canonical result is an integer `Long` — MASS in grams, VOLUME in millilitres, COUNT in units. Decimal input is accepted when the canonical value is exactly an integer; `BigDecimal` + `longValueExact()`; never rounds |
| D8 | Both `,` and `.` are decimal separators when unambiguous. A number that is also a valid thousands grouping is `Ambiguous` — `1.000 g` is neither 1 g nor 1000 g. Exact rule in §6.3 |
| D9 | No bare `unit` concept. Type `NormalizedPackageMeasure`, fields `quantity`, `measureUnit`, `packCount`. ADR-015 D4 does not freeze a name (§10) |

No decision is open.

## 14. Findings outside S1b

- **F1 — `FULL_TEXT_IDENTITY_PARITY = NOT_ESTABLISHED`. Mandatory gate before S2 implementation.** On
  the app's live path, `RuleBasedProductNameNormalizer` runs **before** `ProductNameMatchKey` (ADR-012,
  FACT at line 23), so in the app `CAFE 500G` keys as `café 500 g`, while the backend's `MarketTextKey`
  keys it as `cafe 500g`. S1a proved parity of `ProductNameMatchKey` only, which is what its approved
  design asked for; the M5 documents do not mention the upstream stage. **F1 does not block S1b.** It
  **does** block the implementation of S2 / `ProductKey`: S2 may not start until the product owner
  decides how full text identity parity is established or explicitly scoped out. Not corrected now; the
  app is not changed.
- **F2 — «unit» naming collision** (§4.3). Resolved inside S1b by D9.
- **F3 — The app delivery plan** (line 188: names and signature; «plano detalhado ainda não escrito»)
  needs a follow-up update after approval. Separate app-repository change.

## 15. Definition of done (S1b)

1. This design and its plan approved by the product owner, including the alias table of §6.4.
2. `UnitNormalization.kt` and `UnitNormalizationTest.kt` exist in the backend, written test-first.
3. Every case of §5.1, every `SUPPORTED_BY_REPO` case of §5.2, every rule of §6.3 and every invariant of
   §7 has a passing test.
4. Targeted tests green; full suite equal to the pre-edit baseline plus the new tests, with no new
   failure.
5. Integrated into backend `main` through the normal PR flow, with the PO's authorization for commit,
   push and PR.
6. **Then** S1 = `COMPLETE` (S1a ∧ S1b). S2 becomes plannable, and its implementation still waits for
   the F1 gate.
