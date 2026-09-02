# Confidence Signal Clause Implementation Plan

**Status:** Complete — implemented and verified on 2026-09-02. Every step below is checked because
it was executed, not because it was planned.

**Goal:** Rewrite the confidence clause of the extraction prompt so the provider's per-field
confidence has a chance of discriminating right from wrong, per ADR-007 (decisions 146 and 149) in
the sibling app repository.

**Why:** The first field-by-field measurement of the remote extraction — slice 3.0 of Milestone 3,
scored against a transcribed ground truth — found **47 of 47 present fields returned `HIGH`,
including the three that were wrong**. Nothing in the backend produces that value: `confidence`
appeared in no production source file at all. It is self-graded by the model, requested by one
clause of rule 2 that named three levels, defined none of them, and never told the model to reserve
the top one.

**Architecture:** The prompt moves out of `Application.kt` into its own `internal object` so it can
be asserted, and one clause of it is rewritten. Nothing else moves.

**Tech Stack:** Kotlin 2.2.10, Jackson 2.22.2, JUnit 5.

## Global constraints

- **No contract change.** `confidence` in `contracts/receipt-analysis-result.v1.schema.json` has
  always had four literals; `UNKNOWN` was already permitted and merely never requested.
- **No provider call, no credit.** Verifying the new clause against a real receipt is decision 147,
  gated by the pilot budget in slice 3.4. This slice proves nothing about model behavior.
- **Extraction rules are untouchable.** Rules 1 and 3–6 govern extraction, which Milestone 3
  measures against the ceilings fixed in slice 3.1. Changing them mid-pilot would make a bad number
  unattributable.
- **Build command:** `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew ...`
- **Do not commit, branch or push** unless the user explicitly asks.

## Assumption audit

Run with `pricepulse-assumption-audit`. Every row was read in the source before the plan named a
value.

| Assumption | Source read — `file:line` | What it says there | Planning consequence |
| --- | --- | --- | --- |
| The prompt is the only place asking for a level | `Application.kt:76-97` (pre-change) | Rule 2, line 87: «um nível de confiança (HIGH/MEDIUM/LOW)» | Only that clause changes |
| The constant is testable today | `Application.kt:76` (pre-change) | Top-level `private val` — private to the file | Unreachable from any test. Extraction is a prerequisite, not a preference |
| Existing tests cover the real prompt | `OpenAiResponsesRequestFactoryTest.kt:25`, `OpenAiReceiptAnalysisProviderAdapterTest.kt:37` | Both pass `"Extract merchant, total, purchasedAt and items."` | All existing coverage is over an invented string |
| `UNKNOWN` needs a contract change | `receipt-analysis-result.v1.schema.json:26-28` | `"enum": ["HIGH","MEDIUM","LOW","UNKNOWN"]` | False. Already permitted; only the prompt withheld it |
| The provider schema comes from the contract | `build.gradle.kts:73`; `OpenAiStructuredOutputsReceiptAnalysisSchema.kt:65-72` | `from("contracts")` in `processResources`; `fromCanonicalResource()` reads it off the classpath | The enum the provider is given is the contract's. `UNKNOWN` passes strict mode |
| The backend validates the provider response | `OpenAiReceiptAnalysisProviderAdapter.kt` | No schema validation anywhere in the adapter | Shape is guaranteed by the provider's strict mode; whatever level returns reaches the app unmediated |
| A test can read the contract file | `ReceiptAnalysisResultSchemaTest.kt:17-20` | Reads `contracts/…json` via `System.getProperty("user.dir")` | The new test follows that convention — no assumption about the test classpath |

## Steps

- [x] **1. Extract the prompt.** New `infrastructure/openai/ReceiptExtractionInstructions.kt`, an
  `internal object` holding `TEXT`. `Application.kt` loses the file-private constant, gains the
  import, and passes `ReceiptExtractionInstructions.TEXT` to `OpenAiReceiptAnalysisRequestConfig`.
- [x] **2. Rewrite the confidence clause.** Rule 2 stops naming levels inline; a new rule 2.1
  defines all four by the **condition of the reading**, not by how plausible the value seems, and
  states that `HIGH` is exceptional rather than the default. Rules 1 and 3–6 are byte-identical.
- [x] **3. Pin the prompt against the contract.** New `ReceiptExtractionInstructionsTest`, six
  tests: every `confidence` literal is named; every `documentStatus` literal is named; each
  confidence level is *defined*, not merely listed; the `HIGH`-is-exceptional sentence is present;
  the four extraction rules survive; and the text is accepted by the request config. Expected
  literals are read from `contracts/`, never restated in the test.
- [x] **4. Run the focused test, then the whole suite, then the build.**

## What the new clause says, and why that shape

Each level is anchored in an observable event during reading:

| Level | Condition |
| --- | --- |
| `HIGH` | Every character was legible and transcribed exactly as printed — nothing expanded, completed, corrected or inferred. Stated to be exceptional, not the default |
| `MEDIUM` | Read, but with an ambiguous character resolved by context, an abbreviation expanded, cut/faded text completed, or an accent, brand or word restored from what the line suggested |
| `LOW` | Mostly inferred rather than read — from position on the receipt, from the sum or difference of other fields, or from what a receipt usually carries there |
| `UNKNOWN` | Read, but with no basis to judge the reading condition |

The two description errors that founded the baseline — a brand dropped from
`LEITE INTEGRAL TIROL 1L`, an accent lost from `DETERGENTE LÍQ.` — fall under `MEDIUM` by
construction. That is the design intent; whether the model applies it is what decision 147 tests.

## Evidence

| Command | Result |
| --- | --- |
| `./gradlew test --tests "*ReceiptExtractionInstructionsTest*"` | BUILD SUCCESSFUL — 6 tests, 0 failures |
| `./gradlew build` | BUILD SUCCESSFUL in 29s |
| Test report count (`build/test-results/test/*.xml`) | 35 classes, **484 tests, 0 skipped, 0 failures/errors** |

One test failed on the first run and was corrected: the `HIGH`-is-exceptional assertion matched a
literal phrase that the prompt wraps across a line. The assertion now runs against a
whitespace-flattened copy, so rewrapping a paragraph cannot fail a test about what the paragraph
says. The per-level definition test still uses the raw text, because it is genuinely about line
structure.

**Not verified, and not verifiable here:** that the model's returned levels actually change. No
provider call was made and no credit was spent. That is decision 147 — re-run `R002`, one credit,
before any of the tranche-1 budget.

## Files changed

| File | Change |
| --- | --- |
| `src/main/kotlin/…/infrastructure/openai/ReceiptExtractionInstructions.kt` | New — the prompt, with the rewritten clause |
| `src/main/kotlin/…/infrastructure/Application.kt` | Constant removed; import and reference added |
| `src/test/kotlin/…/infrastructure/openai/ReceiptExtractionInstructionsTest.kt` | New — 6 tests |

## References

- Decision: `../../../../PricePulse/docs/product-development/adr/ADR-007-confidence-signal.md`
- Baseline that produced it: `../../../../PricePulse/test-data/receipt-analysis/cases/R002/score.md`
- Slice handoff: `../../../../PricePulse/docs/superpowers/notes/2026-09-02-33-confidence-signal-handoff.md`
