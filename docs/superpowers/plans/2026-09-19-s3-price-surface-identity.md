# S3 — Price surface identity — Implementation Plan

**Status: `APPROVED_BY_PO`** (2026-09-19, final TDD plan review). **S3 implementation: `NOT_STARTED`.** Approval of this
plan is not authorization to execute: execution needs the PO's explicit implementation authorization
(§14). Once granted, Tasks 0–10 run under the autonomous execution policy of §14 without task-by-task
authorization.

Design: `docs/superpowers/specs/2026-09-19-s3-price-surface-identity-design.md` (`APPROVED_BY_PO`,
2026-09-19). Parent: `../PricePulse/docs/product-development/plans/price-intelligence-foundation-v1.md`
§6 S3.

> **For agentic workers:** execute test-first (superpowers:test-driven-development). Steps use checkbox
> (`- [ ]`) syntax. Every RED must be **seen failing for the expected reason** before GREEN. Only the
> TDD tasks (T1–T6) have a RED; T7–T10 are verification tasks with no RED (§7, «Task types») — never
> fabricate one.

**Goal:** the pure, minimal surface identities of the approved design — `MerchantId`, `StoreId`,
`PriceRegionId`, `PriceSurface` (`StoreSurface` | `PriceRegionSurface`), `PriceScope` and
`StoreResolutionMethod` — with structural equality and nothing else.

**Architecture:** six production files in `application/priceintelligence/surface/`. No dependency on
`identity/` (S1/S2) or on any future S4 package. No I/O, no clock, no persistence, no HTTP, no
serialization. **Backend only.**

**Tech stack:** Kotlin/JVM 2.2.10, JDK 21 toolchain, `kotlin.test` on JUnit 5. No `kotlin-reflect`, no
property-testing library (neither is a dependency; none is added). **`KOTLIN_REFLECT_REQUIRED = NO`**:
field and method shape checks use only JDK reflection reached through `X::class.java`
(`Class.declaredFields`, `Class.declaredMethods`, `Field.isSynthetic`), which needs no Kotlin reflection
library; sealed shape is proved by compilation of an exhaustive `when` without `else`.

---

## 1. Authority

Precedence for this plan (from the PO's authorization of 2026-09-19):

1. The approved S3 design (`APPROVED_BY_PO`), including PO-S3-01 to PO-S3-07.
2. The M5 macro grill and the S3 deep grill findings, as reconciled in the design.
3. The parent plan's prose.

A conflict with a normative ADR (ADR-013, ADR-014, ADR-015, ADR-016) is **not** resolved by this plan or
by the executing agent: it is a `HUMAN_BLOCKER` (§15). None was found while writing this plan (design
§12.3).

**Parent overrides carried unchanged from the design (§12):**

- `PARENT_PLAN_RED_OVERRIDDEN_BY_PO` — the parent RED «`Store → PriceRegion` é n-para-1» is **not
  implemented**. No task models membership in any form.
- `PARENT_CONTRACT_REFINED_BY_PO` — `UNRESOLVED` is **not** a value of `StoreResolutionMethod`.

**Frozen (not rediscussed here):** the shapes, fields, computed properties and enum values of design
§5.2; the id policy of §5.3; the equality semantics of §7; the S3 → S4 contract of §11. Changing any of
them during execution is a `HUMAN_BLOCKER`.

## 2. Execution preconditions

S3 may not start until **all** of these hold:

1. The design is `APPROVED_BY_PO` — **satisfied on 2026-09-19**.
2. This plan is `APPROVED_BY_PO`.
3. The PO has granted explicit **implementation authorization** for S3.
4. A backend branch exists, created from `origin/main` (`87182e5` or a later commit that leaves the
   protected paths of §5 unchanged), **with explicit PO authorization** (backend `CLAUDE.md`).
   Suggested name: `feat/m5-s3-price-surface-identity`. The local checkout at authoring time
   (`feat/m5-s2-product-key`, already merged) must not be used as the base.
5. The design and this plan are carried onto that branch unchanged (blob check, Task 0 Step 4).
6. **Entry-state documentation gate.** The living documents an agent reads as its entry point do not
   contradict the real state. At minimum: backend `CLAUDE.md` and `AGENTS.md` (on the branch base), and
   the app's `.claude/docs/current-state.md` status header and `docs/product-development/roadmap.md`
   Marco 5 entry (on app `origin/main`). They must state **S2 = COMPLETE** and must not state an S3
   status that contradicts «S3 plan approved / ready for implementation» (or the equivalent state in
   force after this plan's approval). If any of them still states, factually and currently, that S2 has
   not started (for example «S2 (`ProductKey`) is ready for detailed planning; its implementation has
   not started» or «S2 has not started»), or an equivalent contradiction: **`HUMAN_BLOCKER` HB-10**, and
   Task 0 does not start. This plan does **not** edit those documents; their reconciliation has its own
   flow and PR.

## 3. Global constraints

- **Pure Kotlin/JVM.** No Ktor, Postgres, JDBC, Android, `java.time`, serialization library or
  annotation. Stdlib only.
- **S1 and S2 are read-only** (§5). S3 imports nothing from `identity/`.
- **Frozen shapes** (design §5.2): `MerchantId(value)`; `StoreId(merchantId, value)`;
  `PriceRegionId(merchantId, value)`; `PriceSurface` sealed with exactly `StoreSurface(storeId)` and
  `PriceRegionSurface(priceRegionId)`, `merchantId` and `scope` **computed** (no backing field);
  `PriceScope { STORE, PRICE_REGION, CHAIN, UNKNOWN }`;
  `StoreResolutionMethod { CNPJ, OFFICIAL_STORE_ID_PLUS_ADDRESS, OFFICIAL_LOCATOR }`.
- **Id policy** (design §5.3): opaque `String`; blank per Kotlin `String.isBlank()` is rejected with
  `require` in `init`; no trimming, case folding, Unicode normalization or textual canonicalization; no
  UUID; no composed string form.
- **Only structural equality** (PO-S3-07). No comparability predicate, `Comparable`, `compareTo`, or any
  cross-surface rule.
- **No** membership, registry, port, repository, catalog, geography, source-native field, display
  field, CNPJ logic, `UNRESOLVED`, `ChainSurface`, `UnknownSurface`, resolution result type.
- **No mutable state**: no `var`, no `lateinit`, no `object` holding state.
- **Production KDoc** states the frozen definitions of design §5.2 and refers to deferred concepts only by
  design section number (for example «membership: design §12.1»), never by naming a vendor or a
  source field.
- `require` messages are constant string literals (no string templates).
- **Backend test convention:** `kotlin.test`, backticked names, the design section of each case in a
  comment. Non-ASCII inputs are written as `\uXXXX` escapes, never as literal invisible characters.
- **Test cases below are the minimum RED set, not a ceiling.** They are written into test files only
  during execution, never during this authoring step.
- **No new dependency, no build-file change** (`build.gradle.kts`, `settings.gradle.kts`, `gradle/`).

## 4. Assumption audit

Performed on 2026-09-19 against backend `origin/main` `87182e5`.

| Assumption | Evidence inspected | Planning consequence |
| --- | --- | --- |
| No surface type exists yet | `git grep -nE "MerchantId\|StoreId\|PriceRegion\|PriceSurface\|PriceScope\|StoreResolutionMethod" origin/main -- src contracts`: zero hits | New files only; no caller, fake, DI binding or route to update |
| `surface/` package does not exist | `application/priceintelligence/` contains only `identity/` | Six new production files |
| Value-class id convention: `@JvmInline value class X(val value: String)` with `require(value.isNotBlank())` in `init` | `receiptanalysis/orchestration/UserId.kt` | `MerchantId` follows it (design §5.4) |
| Kotlin `String.isBlank()` treats NBSP, U+2007 and U+202F as whitespace and ZWSP (U+200B) as not | Kotlin `Char.isWhitespace` = `Character.isWhitespace` ∨ `Character.isSpaceChar`; relied on and tested in S2 (design §7, AC7) | Blank tests include NBSP/U+2007/U+202F (rejected) and ZWSP (accepted), exactly as design §5.3 states |
| No `kotlin-reflect` on the test classpath | `build.gradle.kts`: `testImplementation(kotlin("test-junit5"))`, no reflect | Field-shape checks use **Java** reflection (`Class.declaredFields`, filtering synthetic); «exactly two variants» is proved by an exhaustive `when` without `else` (a third variant breaks compilation of the test) |
| No property-testing framework | `build.gradle.kts` test dependencies | Totality uses explicit enumerated matrices in plain `kotlin.test` |
| Full-suite baseline after S2 | S2 final evidence (2026-09-19): 556 total, 554 passed, 2 failed, 0 skipped; failures `PostgresReceiptAnalysisOperationStoreTest.initializationError`, `PostgresSchemaContractTest.initializationError` (no Docker) | **Expectation only.** Task 0 measures the real baseline before any edit |
| The backend has no `verify.ps1`, no evidence harness | `scripts/` contains only `Start-LocalApi.ps1`, `Test-ReceiptAnalysis.ps1` | Gate = targeted tests + full suite vs baseline + guards + blobs + scope, recorded in local evidence (§17) |
| `build/` is git-ignored | `.gitignore`: `build/` | Local evidence lives under `build/agent-evidence/m5-s3/`; `gradlew clean` is forbidden during the run (§17) |
| Protected blobs are stable | `git rev-parse origin/main:<path>` for the nine paths of design §15.3; `core.autocrlf=false` | Gate by `git hash-object` + `git diff --exit-code $BASE` (§5) |
| The approved design's blob | `git hash-object docs/superpowers/specs/2026-09-19-s3-price-surface-identity-design.md` = `94bc24632dd049b6b6a755baf7f7eb2ca6a39220` (2026-09-19, after approval) | Task 0 and Task 10 verify the design is unchanged |

**Unresolved questions:** none.

## 5. Protected paths

`ID=src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity`,
`IT=src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity`.
Blobs on backend `origin/main` `87182e5` (design §15.3):

```text
$ID/MarketTextKey.kt                               3338193a18de468fb305edf9a21be5749af881ef   S1a
$ID/UnitNormalization.kt                           9323148d00dff697521a346815e5719b7a01b9d2   S1b
contracts/fixtures/text-key/parity-cases.v1.json   0e3f4f4f32a8382dae615e258cd10f40cb7ea1cc   S1a contract
contracts/fixtures/text-key/README.md              a17a83395cd089a7ac06d925b1862a4d5d857b81   S1a contract
$IT/MarketTextKeyParityTest.kt                     2493878791682634d55493746a2c282de8b2d84c   S1a contract test
$ID/Gtin.kt                                        3bcf6a11f72500b217450baaafe7215dee3b7f41   S2
$ID/AttributeSignature.kt                          666021fdfa7d017b7f6c0f583c766c25f1b8332f   S2
$ID/ProductIdentityLevel.kt                        d54aea7a0f42ac5521cd4636e643a5abae2d4a0c   S2
$ID/ProductKey.kt                                  fc1285fdd735939416852da0508df292fd94c6d5   S2
```

**Protected-state check (`PROTECTED_CHECK`)**, run in Task 0 and after every task:

1. `git hash-object <path>` equals the blob above, for each of the nine paths;
2. `git diff --exit-code $BASE -- <the nine paths>` exits 0;
3. `git diff --exit-code $BASE -- $ID $IT contracts/ src/main/resources/ build.gradle.kts settings.gradle.kts gradle/`
   exits 0 (no other S1/S2 file, test, contract, migration or build file changed either);
4. the design's blob equals `94bc24632dd049b6b6a755baf7f7eb2ca6a39220`, and this plan's blob equals the
   value recorded in Task 0 Step 4.

`PROTECTED_STATE = UNCHANGED` only if all four hold.

## 6. File Structure

`SM=src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface`,
`ST=src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface`.
One row, one file, full path.

| Path | Action | Owner |
| --- | --- | --- |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/MerchantId.kt` | Create. `MerchantId` | Task 1 |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/StoreId.kt` | Create. `StoreId` | Task 2 |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/PriceRegionId.kt` | Create. `PriceRegionId`, with the frozen KDoc of design §5.2 | Task 3 |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/PriceScope.kt` | Create. `PriceScope` | Task 4 |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/PriceSurface.kt` | Create. `PriceSurface`, `StoreSurface`, `PriceRegionSurface` | Task 5 |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/StoreResolutionMethod.kt` | Create. `StoreResolutionMethod` | Task 6 |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/MerchantIdTest.kt` | Create | Task 1 |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/StoreIdTest.kt` | Create | Task 2 |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/PriceRegionIdTest.kt` | Create | Task 3 |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/PriceScopeTest.kt` | Create | Task 4 |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/PriceSurfaceTest.kt` | Create (the parent plan's named test file) | Task 5 |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/StoreResolutionMethodTest.kt` | Create | Task 6 |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/surface/SurfaceIdentityMatrixTest.kt` | Create. Cross-type boundary (Task 7) and totality/determinism (Task 9) | Tasks 7, 9 |
| `docs/superpowers/specs/2026-09-19-s3-price-surface-identity-design.md` | Create (design step; unchanged during execution) | — |
| `docs/superpowers/plans/2026-09-19-s3-price-surface-identity.md` | Create (this plan; unchanged during execution) | — |

**Declared file set:** the 13 source/test files above plus the design and this plan (15 paths). The
parent plan lists the six production files and one test file (`PriceSurfaceTest.kt`); the six other
test files are **added**, one per type plus one matrix file, which the design leaves to the plan (design
§5.1, §16). Evidence under `build/agent-evidence/m5-s3/` is ignored by git and is not part of the file
set. Editing any other path is a `HUMAN_BLOCKER` (§15).

## 7. Task dependency order

```text
T0 ─► T1 ─► T2 ─► T3 ─► [CP-A] ─► T4 ─► T5 ─► [CP-B] ─► T6 ─► T7 ─► T8 ─► T9 ─► [CP-C] ─► T10 ─► STOP
```

Strictly linear: each task's types are used by the next. T2 and T3 both need T1; T5 needs T2, T3, T4;
T7 needs T5 and T6; T8 needs all production files; T9 needs everything. There is no independent task to
continue while a blocker is pending, so a `HUMAN_BLOCKER` in any task stops the run after that task's
atomic unit (§15).

**Task types.** The executor must not fabricate a RED where none is expected.

| Task | Type | Cycle | Expected first run | `TASK_GATE` passes when |
| --- | --- | --- | --- | --- |
| T0 | BASELINE | — | — | preconditions and Task 0 checks hold |
| T1–T6 | **TDD** | RED → GREEN → VERIFY | RED, for the stated reason (missing type) | RED seen for the expected reason, then GREEN |
| T7 | **CHARACTERIZATION / INVARIANT VERIFICATION** | write → run → VERIFY; no artificial RED | GREEN | the new tests are GREEN |
| T8 | **STRUCTURAL VERIFICATION** | run guards; no RED/GREEN implementation cycle | — | every guard PASS |
| T9 | **TOTALITY / DETERMINISM VERIFICATION** | write → run → VERIFY; no artificial RED | GREEN | the new tests are GREEN |
| T10 | **FINAL VERIFICATION** | run checks; no code change | — | every Task 10 step holds |

## 8. Commands

| Name | Command (Git Bash, backend root) |
| --- | --- |
| `TARGETED(<Class>)` | `./gradlew.bat test --tests '*surface.<Class>*'` |
| `SURFACE_ALL` | `./gradlew.bat test --tests '*priceintelligence.surface.*'` |
| `S1_S2_TARGETED` | `./gradlew.bat test --tests '*MarketTextKey*' --tests '*UnitNormalization*' --tests '*Gtin*' --tests '*AttributeSignature*' --tests '*ProductKey*'` |
| `FULL` | `./gradlew.bat test --continue` |
| `RESULTS` | read `build/test-results/test/TEST-*.xml`: total = Σ`tests`, failed = Σ`failures` + Σ`errors`, skipped = Σ`skipped`; the **failing set** = the `classname.name` of every `<testcase>` with a `<failure>` or `<error>` child |

`FULL` exits non-zero while the Docker tests fail; its **exit code is not the signal**. The signal is
`RESULTS` compared with the Task 0 baseline: `NEW_FULL_SUITE_FAILURES` = failing set minus baseline
failing set; `MISSING_TESTS` = baseline test cases absent now.

`gradlew clean` is **forbidden** during the run: it would delete `build/agent-evidence/`.

---

## Task 0: Baseline and protected state — before any edit

**Objective:** prove the starting point and record it. **Files:** none (evidence only).

- [ ] **Step 1: Base.** Record `git branch --show-current`, `git rev-parse HEAD origin/main`,
  `git status --porcelain`. Expected: the branch of precondition 4; `HEAD` is `origin/main` or a
  descendant carrying only the design and this plan; the working tree holds nothing outside the declared
  file set.
- [ ] **Step 2: `BASE`.** `BASE=$(git merge-base HEAD origin/main)`; record it.
- [ ] **Step 3: Protected blobs.** Items 1–3 of `PROTECTED_CHECK` (§5). If any blob differs: **STOP**
  (`HUMAN_BLOCKER` HB-5/HB-7); never update the table.
- [ ] **Step 4: Design and plan blobs.** `git hash-object` of the design must be
  `94bc24632dd049b6b6a755baf7f7eb2ca6a39220` (else `HUMAN_BLOCKER` HB-7). Record this plan's blob as
  `PLAN_BLOB`; it must not change afterwards.
- [ ] **Step 5: Surface package absent.** `git ls-files $SM $ST` and `ls $SM $ST`: nothing. Else
  `HUMAN_BLOCKER` HB-7.
- [ ] **Step 6: Full-suite baseline.** `FULL`, then `RESULTS`. Record total, passed, failed, skipped
  and the failing set. **Expected** (not assumed): 556 / 554 / 2 / 0, failing set =
  `PostgresReceiptAnalysisOperationStoreTest.initializationError`,
  `PostgresSchemaContractTest.initializationError`.
  - Counts differ but the failing set is exactly those two Docker tests, and the difference is explained
    by commits on `origin/main` after `87182e5` (`git log 87182e5..$BASE --stat`): record and continue.
  - Docker happens to be available and the two tests pass: record `DOCKER_AVAILABLE=true`, failing set
    empty; continue (the baseline is simply cleaner).
  - Any other failing test, or a difference that cannot be explained safely: **STOP**, `HUMAN_BLOCKER`
    HB-7.
- [ ] **Step 7: Evidence.** Create `build/agent-evidence/m5-s3/` and write `baseline.json` (§17) and the
  first entry of `events.jsonl` (`RUN_STARTED`).

**Auto-continue:** yes, if Steps 1–6 hold.

---

## Task 1: `MerchantId`

**Objective:** the opaque merchant identity and the id policy (design §5.3, AC-1).
**Files:** create `$ST/MerchantIdTest.kt`, then `$SM/MerchantId.kt`.

- [ ] **Step 1: RED.** `MerchantIdTest` with at least:

| Test | Asserts |
| --- | --- |
| `accepts a non-blank value and keeps it exactly` | `MerchantId("m-1").value == "m-1"` |
| `rejects the empty value` | `assertFailsWith<IllegalArgumentException> { MerchantId("") }` |
| `rejects whitespace-only values as blank` | same for `" "`, `"\t"`, `"\n"`, `" "`, `" "`, `" "` (design §5.3: Kotlin `isBlank`) |
| `accepts a zero-width-space-only value as supplied` | `MerchantId("​").value == "​"` (design §5.3) |
| `does not trim` | `MerchantId("a ").value == "a "`; `MerchantId(" a") != MerchantId("a")`; `MerchantId("a ") != MerchantId("a")` |
| `is case-sensitive` | `MerchantId("Loja") != MerchantId("loja")`; `.value` keeps `"Loja"` (no accidental lowercase) |
| `applies no Unicode normalization` | `MerchantId("café") != MerchantId("café")` |
| `equal values are equal with equal hash` | `MerchantId("m") == MerchantId("m")` and equal `hashCode()` |

  Run `TARGETED(MerchantId)`. **Expected RED reason:** test compilation fails, `Unresolved reference:
  MerchantId`.
- [ ] **Step 2: GREEN (minimum).** `@JvmInline value class MerchantId(val value: String)` with
  `init { require(value.isNotBlank()) { "<constant message>" } }`, KDoc per design §5.2. Nothing else.
- [ ] **Step 3: VERIFY.** `TARGETED(MerchantId)` green; `FULL` + `RESULTS`: `NEW_FULL_SUITE_FAILURES = 0`;
  `PROTECTED_CHECK`; guards G-1 to G-15 (§11) over the files existing so far; scope check (G-15).

**Stop condition:** RED fails for any reason other than the missing type, or GREEN needs anything beyond
the frozen shape ⇒ §15 routing. **Auto-continue:** yes, if §14 conditions hold.

---

## Task 2: `StoreId`

**Objective:** merchant-scoped store identity (design §5.2, §7, AC-2). **Files:** `$ST/StoreIdTest.kt`,
then `$SM/StoreId.kt`.

- [ ] **Step 1: RED.** `StoreIdTest`:

| Test | Asserts |
| --- | --- |
| `same merchant and same value are equal with equal hash` | `StoreId(MerchantId("m"), "s") == StoreId(MerchantId("m"), "s")`, equal hash |
| `the merchant participates in equality` | `StoreId(MerchantId("m1"), "s") != StoreId(MerchantId("m2"), "s")` |
| `the local value participates in equality` | `StoreId(MerchantId("m"), "s1") != StoreId(MerchantId("m"), "s2")` |
| `exposes its merchant` | `StoreId(m, "s").merchantId == m` |
| `rejects a blank local value` | `IllegalArgumentException` for `""`, `" "`, `" "` |
| `cannot be built from an invalid merchant` | `assertFailsWith<IllegalArgumentException> { StoreId(MerchantId(""), "s") }` (the merchant is rejected first) |
| `does not trim the local value` | `StoreId(m, "s ") != StoreId(m, "s")`; value kept |
| `local value is case-sensitive` | `StoreId(m, "S") != StoreId(m, "s")` |
| `has exactly the fields merchantId and value` | Java reflection: `StoreId::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet() == setOf("merchantId", "value")` — no membership, no resolution method (design §5.5, PO-S3-04) |

  **Expected RED reason:** `Unresolved reference: StoreId`.
- [ ] **Step 2: GREEN.** `data class StoreId(val merchantId: MerchantId, val value: String)` with
  `init { require(value.isNotBlank()) { "<constant>" } }`.
- [ ] **Step 3: VERIFY.** As Task 1 Step 3, with `TARGETED(StoreId)`.

**Stop / auto-continue:** as Task 1.

---

## Task 3: `PriceRegionId`

**Objective:** merchant-scoped price-region identity; frozen semantic KDoc (design §5.2, §7, AC-3,
AC-4). **Files:** `$ST/PriceRegionIdTest.kt`, then `$SM/PriceRegionId.kt`.

- [ ] **Step 1: RED.** `PriceRegionIdTest`:

| Test | Asserts |
| --- | --- |
| `same merchant and same value are equal with equal hash` | as Task 2 |
| `different merchant is a different region` | `PriceRegionId(m1, "r") != PriceRegionId(m2, "r")` |
| `different value is a different region` | `PriceRegionId(m, "r1") != PriceRegionId(m, "r2")` |
| `exposes its merchant` | `.merchantId == m` |
| `rejects a blank value` | `""`, `" "`, `" "` |
| `does not trim, is case-sensitive, applies no normalization` | `"r "` ≠ `"r"`; `"R"` ≠ `"r"`; `"café"` ≠ `"café"` |
| `is never equal to a store id with the same merchant and value` | `assertNotEquals<Any>(PriceRegionId(m, "x"), StoreId(m, "x"))` in both directions (AC-4) |
| `has exactly the fields merchantId and value` | Java reflection, as Task 2 — no native id, no membership, no metadata |

  **Not tested, deliberately:** any lexical inequality between `PriceRegionId.value` and a
  source-native region id. A coincidental textual equality has no domain meaning (design §5.2, §11 item
  9); the absence of any source-native field, type or mapping is proved by the field-set test above and
  guard G-3.
  **Expected RED reason:** `Unresolved reference: PriceRegionId`.
- [ ] **Step 2: GREEN.** `data class PriceRegionId(val merchantId: MerchantId, val value: String)` with
  the blank `require`, and the frozen KDoc of design §5.2 («canonical opaque identity … must not be
  defined by, derived automatically from, or assumed semantically equivalent to a source-native region
  identifier …»).
- [ ] **Step 3: VERIFY.** As Task 1 Step 3, with `TARGETED(PriceRegionId)`.
- [ ] **Step 4: CHECKPOINT CP-A** (§16): id value objects GREEN.

---

## Task 4: `PriceScope`

**Objective:** the four declared granularities (design §9, AC-9). **Files:** `$ST/PriceScopeTest.kt`,
then `$SM/PriceScope.kt`.

- [ ] **Step 1: RED.**

| Test | Asserts |
| --- | --- |
| `has exactly four values in declaration order` | `PriceScope.entries.map { it.name } == listOf("STORE", "PRICE_REGION", "CHAIN", "UNKNOWN")` |

  **Expected RED reason:** `Unresolved reference: PriceScope`.
- [ ] **Step 2: GREEN.** `enum class PriceScope { STORE, PRICE_REGION, CHAIN, UNKNOWN }`. No property, no
  function (in particular nothing that maps a scope to a surface: that would be resolution, design §4).
- [ ] **Step 3: VERIFY.** As Task 1 Step 3, with `TARGETED(PriceScope)`.

---

## Task 5: `PriceSurface`

**Objective:** the closed surface with computed merchant and scope (design §5.2, §6 I-3 to I-6, §7,
AC-5 to AC-8, AC-10). **Files:** `$ST/PriceSurfaceTest.kt`, then `$SM/PriceSurface.kt`.

- [ ] **Step 1: RED.**

| Test | Asserts |
| --- | --- |
| `store surface derives its merchant from the store id` | `StoreSurface(StoreId(m, "s")).merchantId == m` |
| `store surface has scope STORE` | `.scope == PriceScope.STORE` |
| `price region surface derives its merchant from the region id` | `PriceRegionSurface(PriceRegionId(m, "r")).merchantId == m` |
| `price region surface has scope PRICE_REGION` | `.scope == PriceScope.PRICE_REGION` |
| `surfaces over equal ids are equal with equal hash` | for both variants |
| `surfaces over different ids are different` | different value; different merchant; both variants |
| `a store surface is never equal to a price region surface` | `StoreSurface(StoreId(m, "x")) != PriceRegionSurface(PriceRegionId(m, "x"))`, both directions (AC-8) |
| `every surface is exhaustively one of two variants` | a private function `fun kindOf(s: PriceSurface): String = when (s) { is StoreSurface -> "store"; is PriceRegionSurface -> "region" }` — **no `else`**; asserted on one value of each. A third variant makes this test fail to compile (AC-10) |
| `no surface reports CHAIN or UNKNOWN` | over one instance of each variant: `scope in setOf(STORE, PRICE_REGION)` |
| `variants store only their id` | Java reflection: `StoreSurface` non-synthetic declared fields `== setOf("storeId")`; `PriceRegionSurface` `== setOf("priceRegionId")` — merchant and scope are computed, never stored (design §5.2) |

  Sealedness is proved by the exhaustive `when` above (a non-sealed `PriceSurface` would require an
  `else` branch and fail compilation). `KClass.isSealed` is **not** used: it needs `kotlin-reflect`,
  which is not on the test classpath (§4).

  Whether the variants are nested in `PriceSurface` or top-level in the same file is `AGENT_DECIDABLE`
  (design §5.2); tests refer to them consistently with that choice.
  **Expected RED reason:** `Unresolved reference: PriceSurface` / `StoreSurface` / `PriceRegionSurface`.
- [ ] **Step 2: GREEN.** Exactly design §5.2: `sealed interface PriceSurface { val merchantId: MerchantId;
  val scope: PriceScope }`; `data class StoreSurface(val storeId: StoreId)` and
  `data class PriceRegionSurface(val priceRegionId: PriceRegionId)` with `override val … get() = …`.
- [ ] **Step 3: VERIFY.** As Task 1 Step 3, with `TARGETED(PriceSurface)`.
- [ ] **Step 4: CHECKPOINT CP-B** (§16): `PriceSurface` + `PriceScope` GREEN.

---

## Task 6: `StoreResolutionMethod`

**Objective:** the provenance enum, without `UNRESOLVED` (design §10, §12.2, AC-11).
**Files:** `$ST/StoreResolutionMethodTest.kt`, then `$SM/StoreResolutionMethod.kt`.

- [ ] **Step 1: RED.**

| Test | Asserts |
| --- | --- |
| `has exactly the three methods approved by ADR-014 D7` | `StoreResolutionMethod.entries.map { it.name } == listOf("CNPJ", "OFFICIAL_STORE_ID_PLUS_ADDRESS", "OFFICIAL_LOCATOR")` |
| `has no UNRESOLVED value` | `"UNRESOLVED" !in StoreResolutionMethod.entries.map { it.name }` (design §12.2, `PARENT_CONTRACT_REFINED_BY_PO`) |
| `declares no confirmation predicate` | Java reflection: `StoreResolutionMethod::class.java.declaredMethods` has no public method other than the compiler-generated enum members (`values`, `valueOf`, `getEntries`) — no `isConfirmed` (design §10.2) |

  **Expected RED reason:** `Unresolved reference: StoreResolutionMethod`.
- [ ] **Step 2: GREEN.** `enum class StoreResolutionMethod { CNPJ, OFFICIAL_STORE_ID_PLUS_ADDRESS,
  OFFICIAL_LOCATOR }`, KDoc «provenance, never identity (design §10)». No property, no function.
- [ ] **Step 3: VERIFY.** As Task 1 Step 3, with `TARGETED(StoreResolutionMethod)`.

---

## Task 7: Cross-type boundary and negative cases — **CHARACTERIZATION**

**Objective:** prove the negative boundaries across types (design §7, I-8, AC-4, AC-7, AC-8, AC-12).
**Files:** create `$ST/SurfaceIdentityMatrixTest.kt` (boundary section).

These tests exercise behavior already produced by Tasks 1–6; they are **expected to pass on first run**.
A failure here is a defect introduced by this slice's earlier tasks (`IMPLEMENTATION_FAILURE`,
`INTRODUCED_BY_CURRENT_WORK`), handled by the repair budget of §15 — never by changing a test.

- [ ] **Step 1: Write.**

| Test | Asserts |
| --- | --- |
| `ids of different kinds are never equal` | `MerchantId("x")` vs `StoreId(MerchantId("x"), "x")` vs `PriceRegionId(MerchantId("x"), "x")`: pairwise unequal as `Any` |
| `the same store id yields equal surfaces whatever resolution method accompanies it` | two `Pair(StoreSurface(sid), method)` for two different `StoreResolutionMethod`s: the surfaces are equal with equal hash (AC-12: provenance is not identity) |
| `no id or surface holds a resolution method` | Java reflection over `MerchantId` (boxed class), `StoreId`, `PriceRegionId`, `StoreSurface`, `PriceRegionSurface`: no declared field whose type is `StoreResolutionMethod` (I-8) |
| `no id or surface holds a scope or a second merchant` | same reflection: no field of type `PriceScope`; `MerchantId`-typed fields only in `StoreId` and `PriceRegionId` |

- [ ] **Step 2: Run.** `TARGETED(SurfaceIdentityMatrix)`. **Expected:** GREEN on first run (declared
  characterization). If RED: classify per §15; production code may be repaired only within the frozen
  shape.
- [ ] **Step 3: VERIFY.** As Task 1 Step 3.

---

## Task 8: Structural guards — forbidden concepts

**Objective:** run every guard of §11 over the complete production and test file set; no code change is
expected. **Files:** none (evidence only).

- [ ] **Step 1:** run G-1 to G-15 exactly as written in §11; record each command and its output in
  `guards.json` (§17).
- [ ] **Step 2:** every guard that must be empty is empty; G-11 returns exactly its one expected line.
- [ ] **Step 3:** a hit is a **FAIL**. If the hit is a real forbidden concept introduced by this run,
  remove it (repair budget, §15). If the agent believes the hit is a false positive, or cannot classify
  its cause safely, it does **not** change the guard or add an exemption: it records **`HUMAN_BLOCKER`
  HB-9** (§15) with the line and the reasoning, and stops — `STRUCTURAL_GUARDS = PASS` cannot hold, so
  no task can auto-continue.

---

## Task 9: Totality and determinism matrix — **CHARACTERIZATION**

**Objective:** exhaustively show that construction, equality, hash and computed properties are total
and deterministic over an enumerated domain (design §5.3, §7, AC-7 to AC-10). **Files:**
`$ST/SurfaceIdentityMatrixTest.kt` (matrix section).

Expected to pass on first run (same rule as Task 7).

- [ ] **Step 1: Write.** Enumerated inputs:
  - valid texts `V = ["a", "A", "a ", " a", "café", "café", "​", "1"]` (8);
  - blank texts `B = ["", " ", "\t", "\n", " ", " ", " ", "   "]` (8).

| Test | Asserts |
| --- | --- |
| `every valid text builds every id and never throws` | for all `m ∈ V`, `v ∈ V`: `MerchantId(m)`, `StoreId(MerchantId(m), v)`, `PriceRegionId(MerchantId(m), v)` construct; `.value`s are exactly the inputs |
| `every blank text is rejected with IllegalArgumentException, and only that` | for all `b ∈ B`: `MerchantId(b)`, `StoreId(MerchantId("a"), b)`, `PriceRegionId(MerchantId("a"), b)` throw `IllegalArgumentException` (checked with `assertFailsWith<IllegalArgumentException>`, which fails on any other exception type) |
| `surface equality is exactly equality of kind, merchant and value` | build all `2 × |V| × |V|` = 128 surfaces; for every ordered pair, `(a == b) == (kind(a) == kind(b) && merchant(a) == merchant(b) && value(a) == value(b))`, where those are read from the input tuple, not from the objects |
| `equal surfaces have equal hashes` | for every pair with `a == b`: `a.hashCode() == b.hashCode()` |
| `merchant and scope are computed deterministically` | for every surface: `merchantId` equals the input merchant; `scope` is `STORE` for store surfaces and `PRICE_REGION` for region surfaces; reading both twice gives equal results |
| `building the same surface twice is equal` | reconstructing each surface from its input tuple yields an equal object with equal hash |
| `the number of distinct surfaces is the number of distinct input tuples` | `surfaces.toSet().size == 128` (all tuples distinct: no accidental normalization merged two) |

- [ ] **Step 2: Run.** `TARGETED(SurfaceIdentityMatrix)`. **Expected:** GREEN on first run.
- [ ] **Step 3: VERIFY.** As Task 1 Step 3.
- [ ] **Step 4: CHECKPOINT CP-C** (§16): before final verification.

---

## Task 10: Final verification and STOP

**Objective:** the complete technical-completion evidence (§18). **Files:** none (evidence only).

- [ ] **Step 1: Surface targeted.** `SURFACE_ALL`: all S3 tests green; record the count per test class.
- [ ] **Step 2: S1/S2 still green.** `S1_S2_TARGETED`: green.
- [ ] **Step 3: Full regression.** `FULL` + `RESULTS`: total = baseline total + S3 tests;
  `NEW_FULL_SUITE_FAILURES = 0`; `MISSING_TESTS = 0`; the failing set equals the baseline failing set
  (the two Docker tests, unless Task 0 recorded `DOCKER_AVAILABLE=true`).
- [ ] **Step 4: Guards.** G-1 to G-15 (§11) re-run on the final tree; all PASS.
- [ ] **Step 5: Protected state.** `PROTECTED_CHECK` (§5), including the design and `PLAN_BLOB`.
- [ ] **Step 6: Exact file set.** `git status --porcelain` and `git diff --name-only $BASE` list only the
  15 paths of §6 (13 source/test files + design + plan), and **all 13 source/test files exist**.
- [ ] **Step 7: AC map.** Fill `acceptance.json` (§17): each of design AC-1 to AC-15 mapped to the tests
  or checks that prove it, with their result.
- [ ] **Step 8: Decision queue.** No `HUMAN_BLOCKER` open. Any `HUMAN_NON_BLOCKING` or
  `HUMAN_BEFORE_MERGE` item is listed in the final report.
- [ ] **Step 9: STOP.** Emit `RUN_COMPLETE` (§17) and stop at
  **`READY_FOR_S3_IMPLEMENTATION_COMMIT_AUTHORIZATION`**. Branch commit, push and PR are
  `HUMAN_AUTHORITY`; S3 is not «integrated» or `COMPLETE` until merged (§18).

---

## 11. Structural guards

Run from the backend root with Git Bash. `SM` and `ST` as in §6. `CODE_ONLY` removes comment and KDoc
lines so that the frozen KDoc text (which legitimately says «geographic» and «source-native», design
§5.2) does not trigger vocabulary guards:

```bash
CODE_ONLY() { grep -vE '^[^:]+:[0-9]+:[[:space:]]*(/\*|\*|//)'; }
```

Guards on vendor names, `UNRESOLVED` and surface names apply to **all** text, KDoc included. Each guard
names its scope precisely so that the frozen names `Store…`, `PriceRegion…` and `CNPJ` never collide.

Every `grep` runs with `-H` (explicit, or implied by `-r`) so that each hit is `path:line:content`, the
form `CODE_ONLY` expects. In the table below, `\|` is Markdown escaping: the command uses a plain `|`.

| # | Intent (design §15.2) | Command | Expected |
| --- | --- | --- | --- |
| G-1 | no persistence / HTTP / serialization (prod and tests) | `grep -rnE 'import (java\.sql\|javax\.sql\|io\.ktor\|kotlinx\.serialization\|com\.fasterxml\|org\.postgresql\|org\.testcontainers)\|@Serializable\|@JvmRecord' $SM $ST` | empty |
| G-2 | no registry / repository / port / catalog; no interface other than `PriceSurface` | `grep -rniE 'registry\|repository\|catalog\|\bport\b\|\bdao\b' $SM \| CODE_ONLY` and `grep -rnE '\binterface\b' $SM \| grep -v 'sealed interface PriceSurface'` | both empty |
| G-3a | no vendor or source-field name anywhere (parent invariant 10) | `grep -rniE 'vtex\|carrefour\|atacad\|\bgpa\b\|extra ?mercado\|p[aã]o de a[cç]\|regionid\|accountname\|\bsc=' $SM` | empty |
| G-3b | no source-native field or mapping in code | `grep -rniE 'seller\|sourceid\|source_id\|nativeid\|native\|externalid\|mapping\|mapper' $SM \| CODE_ONLY` | empty |
| G-4 | no geography | `grep -rniE 'latitude\|longitude\|coordinat\|\bcep\b\|postal\|\buf\b\|metro\|\bcity\b\|cidade\|geo\|address\|endere\|distance' $SM \| CODE_ONLY` | empty |
| G-5 | no display metadata | `grep -rniE '\bname\b\|displayname\|label\|title' $SM \| CODE_ONLY` | empty |
| G-6 | no S1/S2/S4 dependency (prod and tests) | `grep -rnE 'priceintelligence\.(identity\|observation)\|ProductKey\|MarketTextKey\|normalizePackageMeasure\|PriceObservation' $SM $ST` | empty |
| G-7 | no canonical string, no accidental normalization, no UUID | `grep -rnE '\.trim\|strip\(\|lowercase\|uppercase\|Normalizer\|fun toString\|UUID\|\$\{\|\$[a-zA-Z]\|asString\|toKey\|encode\|\.format\(\|joinToString' $SM \| CODE_ONLY` | empty |
| G-8 | no comparability predicate | `grep -rniE 'comparable\|compareto\|granularity\|cancompare\|iscomparable\|equivalent\|similar\|\bmatches\b' $SM \| CODE_ONLY` | empty |
| G-9 | no membership (Store → PriceRegion in either direction) | `grep -HnE 'PriceRegion' $SM/StoreId.kt \| CODE_ONLY`; `grep -HnE 'Store' $SM/PriceRegionId.kt \| CODE_ONLY`; `grep -rniE 'storeids\|members\|membership' $SM` | all empty |
| G-10 | no `UNRESOLVED`, no chain/unknown surface | `grep -rnE 'UNRESOLVED\|Unresolved\|NOT_APPLICABLE\|ChainSurface\|UnknownSurface' $SM` | empty |
| G-11 | no CNPJ logic: CNPJ exists only as the enum constant | `grep -rniE 'cnpj' $SM \| CODE_ONLY` | **exactly one line**, in `$SM/StoreResolutionMethod.kt`: the declaration of the `CNPJ` constant |
| G-12 | resolution method is provenance, not identity | `grep -lE 'StoreResolutionMethod' $SM/MerchantId.kt $SM/StoreId.kt $SM/PriceRegionId.kt $SM/PriceSurface.kt $SM/PriceScope.kt` | empty |
| G-13 | no mutable state, no stateful singleton | `grep -rnE '\bvar \|lateinit\|^\s*(private \|internal )?object \|companion object' $SM \| CODE_ONLY` | empty |
| G-14 | no build, dependency, contract or resource change | `git diff --exit-code $BASE -- build.gradle.kts settings.gradle.kts gradle/ contracts/ src/main/resources/` | exit 0 |
| G-15 | scope: only the declared file set | `git status --porcelain` and `git diff --name-only $BASE` ⊆ the 15 paths of §6 | subset |

Adding a new guard is allowed. Changing or removing a guard, widening an exemption, or rewording a
pattern so that a hit disappears is **not** (it weakens the gate); a suspected false positive is
`HUMAN_BLOCKER` HB-9 (§15).

## 12. Totality and determinism

Covered by Task 9 over an enumerated matrix of 8 valid and 8 blank texts, both surface kinds, 128
surfaces and all 16 384 ordered pairs; by the exhaustive `when` of Task 5; and by the field-shape
reflection tests of Tasks 2, 3, 5 and 7. No randomness, no clock, no I/O: every test is deterministic
and runs in milliseconds.

## 13. Final verification

Task 10. Its outputs are the completion evidence of §17; nothing is claimed that §17 does not record.

## 14. AUTONOMOUS_EXECUTION_POLICY

**Activation.** Only when all hold: design `APPROVED_BY_PO`; this plan `APPROVED_BY_PO`; implementation
authorization granted by the PO; preconditions of §2 satisfied. Then the agent executes Tasks 0–10
**without asking for authorization task by task**.

**AUTO_CONTINUE_NEXT_TASK** after a task when **all** hold:

```text
TASK_GATE                 = PASS   (per task type, §7 «Task types»)
TARGETED_TESTS            = PASS
FULL_SUITE_NEW_FAILURES   = 0      (§8 NEW_FULL_SUITE_FAILURES)
MISSING_BASELINE_TESTS    = 0      (§8 MISSING_TESTS)
DIFF_WITHIN_FILE_SET      = YES    (G-15)
PROTECTED_STATE           = PASS   (PROTECTED_CHECK, §5)
STRUCTURAL_GUARDS         = PASS   (G-1..G-15 over the files existing so far)
HUMAN_BLOCKER             = NONE
```

Only then **AUTO_CONTINUE_NEXT_TASK**. If **any** of them is false: **no auto-continue**, and the run
follows §15. The agent never asks the PO to confirm a task that met these conditions, and never continues
past a task that did not.

**Never automatic, whatever the state:** branch creation (precondition, done before the run), commit,
push, PR, merge, any edit of the design, this plan, a parent document, an ADR, `CLAUDE.md`/`AGENTS.md`,
or any file outside §6.

## 15. Decision routing

| Class | Triggers in S3 | Action |
| --- | --- | --- |
| `HUMAN_BLOCKER` | **HB-1** a finding requires changing a frozen type, field, value set, equality rule or the S4 contract · **HB-2** a conflict with an ADR or other authority · **HB-3** a scope expansion is needed · **HB-4** a file outside §6 must be edited · **HB-5** S1/S2 would need to change, or a protected blob differs · **HB-6** behavior contradicts a design acceptance criterion and cannot be fixed within the frozen shape · **HB-7** the baseline or starting state is unexpected and cannot be explained safely · **HB-8** the repair budget below is exhausted · **HB-9** a structural guard fails and the cause is a suspected false positive, cannot be classified safely, or could only be cleared by changing the guard (which the agent may not do) · **HB-10** the entry-state documentation gate of §2 item 6 fails | finish the current atomic unit safely (never leave a half-written file); **do not apply** the decision; save evidence; append a `HUMAN_BLOCKER` event; continue only independent READY tasks — **S3 has none** (§7), so **STOP** |
| `HUMAN_NON_BLOCKING` | a decision that changes no type, contract, scope or AC **and** has an explicit default below | record it in the decision queue with the default applied; continue |
| `HUMAN_BEFORE_MERGE` | **none known.** Only an item that lets every auto-continue condition of §14 still hold, and must be accepted before merge, may be classified here; anything that makes a condition false is a `HUMAN_BLOCKER` | continue; implementation may finish; merge is blocked until the PO resolves it |
| `AGENT_DECIDABLE` | nesting of the surface variants; `require` message texts; test method names beyond the tables; additional test cases; ordering of tests in a file; KDoc wording beyond the frozen sentences | decide per design and plan; record only if material |

**Explicit `HUMAN_NON_BLOCKING` defaults** (the only ones the agent may apply):

- **NB-1** — the full-suite count differs from 556 only because of commits on `origin/main` after
  `87182e5` that do not touch §5 paths or `surface/`: default *use the measured baseline*.
- **NB-2** — Docker is available and the two Testcontainers tests pass: default *record
  `DOCKER_AVAILABLE=true`; the failing-set comparison uses the measured (empty) set*.

**Failure classification and repair budget — `S3_AUTONOMOUS_RUN_POLICY`.** No backend authority makes a
repair budget normative: backend `CLAUDE.md`, `AGENTS.md`, `README.md` and `docs/` define none, and the
backend has no `repair.ps1`. The classes and numbers below are therefore a **local policy of this S3
run, approved by this plan**, not an inherited backend rule. They are modelled on the app's `CLAUDE.md`
(«When verification fails»), which is normative only for the app repository. The run records them by
hand in `decisions.jsonl`:

| Class / origin | Allowed |
| --- | --- |
| `IMPLEMENTATION_FAILURE`, `INTRODUCED_BY_CURRENT_WORK` | `AUTO_REPAIR` within the frozen shape |
| `ENVIRONMENT_FAILURE` (JDK, Gradle daemon, file lock) | `RETRY_ONLY`; never edit code around it |
| `IMPLEMENTATION_FAILURE`, `PREEXISTING` | `STOP_AND_REPORT` (HB-7) |
| `SCOPE_FAILURE` | `STOP_AND_REPORT` (HB-3/HB-4); revert only this run's own out-of-scope edit |
| `UNKNOWN_FAILURE` | `RETRY_ONLY` once to gather evidence, then `STOP_AND_REPORT` |

**Terms.**

- **Retry** — re-running the same command with **no** change to any file.
- **Repair** — a change to a file of §6 that tests a written hypothesis about a failure.
- **Failure signature** — derived from the evidence, never typed in: the failing step (`TARGETED(<Class>)`,
  `SURFACE_ALL`, `S1_S2_TARGETED`, `FULL`, a guard id `G-n`, or `PROTECTED_CHECK`) plus a digest of what
  that step reported: the sorted set of failing test cases (`classname.name`) from `RESULTS`; or, for a
  compilation failure, the compiler diagnostics as (file, message) pairs without line numbers; or, for a
  guard, the guard id and its hit lines; or, for `PROTECTED_CHECK`, the differing paths. The class,
  origin, wording of a hypothesis or any other annotation is **not** part of the signature, so
  rewording it never creates a new signature.

**Budget (numbers unchanged):**

| Scope | Retry limit | Repair limit | Counter reset rule | When the limit is exhausted |
| --- | --- | --- | --- | --- |
| per failure signature | **2** | **2** | never within the run: the count follows the signature across tasks, and a new session continues it from the checkpoint's `repair_budget_used` (it is **not** reset by a handoff) | `HUMAN_BLOCKER` HB-8; stop |
| per task | **4** (all signatures together) | **3** (all signatures together) | starts at 0 when the task starts; not reset by a handoff inside the task | `HUMAN_BLOCKER` HB-8; stop |
| `UNKNOWN_FAILURE` | **1**, counted in both rows above | **0** | — | `STOP_AND_REPORT` after the single retry (HB-7) |

A limit is exhausted when the next action would exceed it; that action is not taken. A repair that
makes the original failure disappear but produces a different failure has created a new signature,
with its own per-signature counters, while the per-task counters keep counting.

Each repair records observation, hypothesis, repair and expected result before editing, and the actual
result after. A new failure after a repair is a regression: revert the attempt (it is this run's own
edit, inside §6) and form a new hypothesis. Tests are never deleted, skipped or loosened to obtain GREEN.

## 16. Checkpoints and session handoff

**Checkpoints:** CP-A after Task 3 (id value objects GREEN); CP-B after Task 5 (`PriceSurface` +
`PriceScope` GREEN); CP-C after Task 9 (before final verification). A checkpoint is also written whenever
the run stops (blocker, budget) or hands off.

Each checkpoint writes `build/agent-evidence/m5-s3/checkpoint-<CP>.json` with:

```text
branch · head · base · working_tree (git status --porcelain)
task_completed · next_task
targeted_test_counts (per S3 test class)
full_suite {total, passed, failed, skipped, failing_set}
known_failures (baseline failing set)
decision_queue (open and resolved items with class and default)
protected_blobs (the nine paths + design + PLAN_BLOB, each with expected/actual)
declared_file_set (the 15 paths) and files_present
guards (last result per guard)
repair_budget_used
unexpected_findings
```

**Session handoff.** If context is running low: do not start a new task; finish the current atomic unit;
write a checkpoint; emit **`CONTEXT_HANDOFF_READY`** with the checkpoint path. A new session rebuilds
state from `AGENTS.md`, `CLAUDE.md`, the design, this plan, the latest checkpoint, `git status` and
`git log`, re-runs `PROTECTED_CHECK` and the targeted tests of completed tasks (read-only confirmation,
no edits), and continues at `next_task`. Green tasks are not repeated; if the confirmation disagrees with
the checkpoint, that is HB-7.

## 17. Evidence format

All evidence is local, under `build/agent-evidence/m5-s3/` (git-ignored; never committed; never
hand-edited after the fact).

| File | Content |
| --- | --- |
| `baseline.json` | Task 0: branch, head, base, full-suite counts and failing set, protected blobs, design blob, `PLAN_BLOB`, `DOCKER_AVAILABLE` |
| `task-<n>.json` | per task: RED command + observed failure reason (or `CHARACTERIZATION`), GREEN command + counts, full-suite result, `PROTECTED_CHECK`, guards, scope, auto-continue decision |
| `guards.json` | each guard's command and raw output (Tasks 8 and 10) |
| `acceptance.json` | design AC-1..AC-15 → proving tests/checks → result |
| `decisions.jsonl` | decision queue and repair log |
| `events.jsonl` | notification events (below) |
| `checkpoint-<CP>.json` | §16 |

**Notification events** (logical format only; no script or infrastructure is created by this slice):

```json
{
  "event_type": "HUMAN_BLOCKER | HUMAN_NON_BLOCKING | HUMAN_BEFORE_MERGE | CONTEXT_HANDOFF_READY | RUN_STARTED | RUN_COMPLETE",
  "slice": "M5/S3",
  "task": "T0..T10",
  "decision_id": "HB-n | NB-n | HBM-n | null",
  "summary": "one sentence",
  "evidence_ref": "build/agent-evidence/m5-s3/<file>",
  "blocks": ["T<n>", "merge"],
  "does_not_block": ["..."],
  "next_safe_action": "one sentence",
  "head": "<sha>",
  "at": "<ISO-8601 UTC>"
}
```

The summary of `RUN_COMPLETE` is the completion evidence; a handoff or PR quotes it rather than
retyping it. Anything this evidence does not record is `NOT_VERIFIED`; in particular it never covers
behavior outside the pure types (there is none) and never covers merge or deployment.

## 18. Definition of done

S3 implementation is **technically complete** only when **all** hold:

- design AC-1 to AC-15 pass (`acceptance.json`);
- targeted S3 tests pass; S1/S2 targeted tests pass;
- no new full-suite failure and no missing test; known Docker failures unchanged;
- S1/S2 protected blobs unchanged;
- structural guards G-1 to G-15 pass;
- totality/determinism (Task 9) pass;
- exact file set confirmed (15 paths, 13 source/test files present);
- no `HUMAN_BLOCKER` pending; no unexpected scope expansion;
- design unchanged (blob `94bc2463…`); plan unchanged since approval (`PLAN_BLOB`).

Even then S3 is **not** integrated or `COMPLETE`: that requires the PO's commit/push/PR authorization and
the merge into backend `main`. Updating the parent plan (the design §12 overrides), the parent spec, the
roadmap, `current-state.md` and backend `CLAUDE.md`/`AGENTS.md` is a separate documentation step.

## 19. STOP gates

| Gate | When |
| --- | --- |
| before Task 0 | any precondition of §2 unmet, including the entry-state documentation gate (HB-10) |
| Task 0 | unexpected baseline, protected-blob mismatch, design blob mismatch, `surface/` already present (HB-5/HB-7) |
| any task | a `HUMAN_BLOCKER` trigger (HB-1..HB-9) — S3 has no independent task, so the run stops |
| any task | `CONTEXT_HANDOFF_READY` |
| Task 10 | **`READY_FOR_S3_IMPLEMENTATION_COMMIT_AUTHORIZATION`** — the normal end of the run |

## Risks

| Risk | Mitigation |
| --- | --- |
| A guard pattern collides with a frozen name | guards are path- and code-scoped (§11); a suspected false positive is `HUMAN_BLOCKER` HB-9, never a weaker guard |
| `isBlank()` semantics misread | Task 1 and Task 9 test NBSP/U+2007/U+202F (blank) and ZWSP (not blank) explicitly |
| Accidental normalization merges ids | Task 9 counts 128 distinct surfaces from 128 distinct tuples |
| Merchant or scope stored instead of computed | Java-reflection field-set tests (Tasks 5, 7) |
| A third surface variant slips in | exhaustive `when` without `else` (Task 5) fails compilation |
| Membership or resolution method creeps into an id | field-set tests (Tasks 2, 3, 7), G-9, G-12 |
| Characterization tasks mistaken for skipped RED | Tasks 7 and 9 declare it; failures there follow the repair budget |
| Evidence lost by `gradlew clean` | `clean` forbidden during the run (§8) |

## Out of scope

Membership (n:1 or any other form), registry, catalog, id minting, source-native mapping, geography,
display metadata, CNPJ logic, person location, `Purchase.storeName` link, `PriceRegionResolutionMethod`,
resolution result types, comparability or aggregation, `PriceObservation` (S4), persistence (S10), HTTP
and serialization (S12), and any change to S1, S2, contracts or the app.
