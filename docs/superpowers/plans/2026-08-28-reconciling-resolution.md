# RECONCILING Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `RECONCILING` a resolvable state instead of an absorbing one, so every receipt-analysis operation reaches `TERMINAL`, its credit is settled, and its stored image becomes deletable.

**Architecture:** A provider-neutral `ReceiptAnalysisRetrievalPort` reads the current state of a pending provider invocation by the correlation reference already persisted for it. A `ResolveReconcilingOperationsUseCase` lists reconcilable operations, retrieves each one, and applies the outcome through the existing `applyOutcome` — which stays the single authority for lifecycle and ledger. Operations that cannot be resolved within an expiry window settle as `FAILED_NO_PROVIDER` + `RELEASED`. A small background runner drives the sweep on an interval. Finally, the terminal transition deletes the stored payload, and a migration purges the accumulated backlog.

**Tech Stack:** Kotlin 2.2.10, Ktor 3.0.1 (server Netty, client CIO), PostgreSQL via JDBC + HikariCP, Flyway 13.3.0, JUnit 5, Testcontainers 1.21.4, Jackson 2.22.2.

## Global Constraints

- **No wire-contract change.** `contracts/openapi.json` and `contracts/receipt-analysis-result.v1.schema.json` must not be edited by this plan. Every terminal reason used here already exists in the v1 vocabulary.
- **`applyOutcome` stays the single authority** for lifecycle transitions and ledger effects. No task may write `lifecycle_state`, `credit_ledger_entry` or `credit_grant` outside it.
- **The provider destination stays fixed and non-configurable.** No runtime-supplied base URL, ever. The existing invariant in `OpenAiCioHttpTransport` closes a credential-exfiltration risk and must survive this plan.
- **`ProviderCorrelationReference` never reaches a wire response.** This plan makes it readable inside the backend for the first time; it must not appear in `ReceiptAnalysisStatusResponses` or any route.
- **`ProviderCorrelationReference.toString()` is redacted.** Never log `value()`.
- **Cancellation always propagates.** Every `catch (Exception)` must be preceded by `catch (CancellationException) { throw it }`, matching the discipline used throughout this codebase.
- **Clock injection.** No `Instant.now()` or `Clock.systemUTC()` inside application or persistence classes; the injected `Clock` is the only source of now.
- **Build command:** this environment has no `JAVA_HOME` by default. Every Gradle command below must be run as:
  `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew ...`
- **Do not commit, branch or push** unless the user has explicitly asked. The `git commit` steps assume that permission was granted; if it was not, stop after the passing test and report.

## Context an implementer needs

Read before starting:

- `src/main/kotlin/.../application/receiptanalysis/orchestration/ReceiptAnalysisOperationStore.kt` — the state machine contract, including which `OutcomeApplication` values are valid from `RECONCILING`.
- `src/main/kotlin/.../infrastructure/persistence/PostgresReceiptAnalysisOperationStore.kt` — `withConnection`, `applyTerminalTransition`, the ledger writes.
- `src/main/resources/db/migration/V1__...sql` and `V2__...sql` — the triggers that reject a wrong ordering of statements.

**Why `RECONCILING` is absorbing today** (the defect this plan fixes): `applyOutcome` is the only writer of `TERMINAL`, and its only production caller is `StartReceiptAnalysisUseCase`, reachable only after `claimInvocation` returns `Claimed`. `claimInvocation`'s SQL is `... WHERE operation_id = ? AND lifecycle_state = 'RECEIVED'`, so a `RECONCILING` row can never be re-claimed. No worker, sweeper or scheduler exists in `src/main`.

**Why exclusivity is not needed:** two runners resolving the same operation concurrently is safe. Retrieval is a read. `applyOutcome` locks the row `FOR UPDATE` and returns `AlreadyResolved` for a row already `TERMINAL`, so the ledger is written exactly once. This plan therefore adds no new lifecycle state, no lease column and no distributed lock.

## File structure

| File | Responsibility |
| --- | --- |
| `application/receiptanalysis/orchestration/ReconciliationCandidate.kt` (create) | The value a store returns for one resolvable operation. |
| `application/receiptanalysis/orchestration/ReceiptAnalysisOperationStore.kt` (modify) | Adds `findReconcilable`. |
| `application/receiptanalysis/ReceiptAnalysisRetrievalPort.kt` (create) | Provider-neutral retrieval of a pending invocation. |
| `application/receiptanalysis/orchestration/ResolveReconcilingOperationsUseCase.kt` (create) | The resolution policy. Every decision lives here. |
| `infrastructure/persistence/PostgresReceiptAnalysisOperationStore.kt` (modify) | Implements `findReconcilable`; deletes the payload on terminal. |
| `infrastructure/openai/OpenAiResponseClassifier.kt` (create) | Classification extracted from the provider adapter so both adapters share one implementation. |
| `infrastructure/openai/OpenAiReceiptAnalysisProviderAdapter.kt` (modify) | Delegates classification to the extracted classifier. |
| `infrastructure/openai/OpenAiOutboundRequest.kt` (modify) | Adds the validated retrieval request factory. |
| `infrastructure/openai/OpenAiCioHttpTransport.kt` (modify) | Honours `request.path` against the fixed origin. |
| `infrastructure/openai/OpenAiReceiptAnalysisRetrievalAdapter.kt` (create) | The OpenAI implementation of the retrieval port. |
| `infrastructure/ReconciliationRunner.kt` (create) | Interval loop. Owns no policy. |
| `infrastructure/Application.kt` (modify) | Wires and shuts down the runner. |
| `src/main/resources/db/migration/V3__purge_terminal_payload_backlog.sql` (create) | One-off purge of already-terminal payloads. |


## Assumption audit

Run with `.claude/skills/pricepulse-assumption-audit`. Confirmed facts and unresolved questions
are kept separate on purpose: a row marked UNRESOLVED must not be treated as an instruction.

| Assumption | Evidence inspected | Planning consequence |
| --- | --- | --- |
| Operation age comes from an injected clock, never wall time | `PostgresReceiptAnalysisOperationStore.nowTimestamp()`/`clock`; `MutableClock` in `PostgresReceiptAnalysisOperationStoreTest`, fixed at `2026-08-15T12:00:00Z` | CONFIRMED. Every cutoff, in production and in tests, derives from the injected clock. `Instant.now()` would pass or fail by accident depending on the wall date. Corrected during Task 1. |
| `ProviderCorrelationReference` has a private constructor plus a validating companion `invoke` | `ProviderCorrelationReference.kt` | CONFIRMED. `::ProviderCorrelationReference` binds the private constructor and does not compile; use an explicit lambda. Corrected during Task 1. |
| Complete set of `ReceiptAnalysisOperationStore` implementers | Search across `src/main` and `src/test` | CONFIRMED, six: `PostgresReceiptAnalysisOperationStore`, `InMemoryReceiptAnalysisOperationStore`, `StartReceiptAnalysisUseCaseTest.StubOperationStore`, `StartReceiptAnalysisUseCaseTest.RecordingOperationStore`, `ReceiptAnalysisStatusRouteTest.FakeStore`, `StartReceiptAnalysisRouteTest.FakeStore`. Every one is named in Task 1's file list. |
| Unreachable methods on a test double raise rather than return empty | `ReceiptAnalysisStatusRouteTest.FakeStore` uses `UnsupportedOperationException("out of this slice")` | CONFIRMED. New double methods follow that idiom; `RecordingOperationStore` delegates instead, matching how it treats every other member. |
| `OpenAiCredential` and `OpenAiCredentialProvider` construction | `OpenAiCredential.kt` (private constructor + companion `invoke`), `OpenAiCredentialProvider.kt` (`fun interface`) | CONFIRMED. `OpenAiCredential("sk-test")` and `OpenAiCredentialProvider { credential }` are both valid as written in Task 3. |
| An existing test pins the invocation URL | `OpenAiCioHttpTransportTest.kt:97` asserts the exact URL, `HttpMethod.Post`, `Authorization`, `Content-Type` and body | CONFIRMED. That test is the regression guard for Task 3's origin+path change and must pass untouched. `Content-Type` stays set because the POST body is non-empty, so the conditional body block does not affect it. |
| `kotlinx-coroutines-test` version must match the resolved core | `dependencyInsight --configuration testRuntimeClasspath` resolves `kotlinx-coroutines-core:1.9.0` | CONFIRMED. Pin `kotlinx-coroutines-test:1.9.0` — an exact match, not a guess. |
| slf4j is an available logging path | `LoggingOpenAiInvocationTelemetrySink` uses `org.slf4j.LoggerFactory` | CONFIRMED. `ReconciliationRunner` may use it directly. |
| Raw-SQL verification convention in the Postgres test | `correlationRowCount` / `storedCorrelationReference` use the shared `verificationConnection` and `UUID.fromString(operationId.value)` | CONFIRMED. Task 5's payload helper must mirror that shape, not open its own `dataSource` connection. Task 5 corrected below. |
| A V3 data migration does not disturb the schema contract test | `PostgresSchemaContractTest` runs Flyway against an empty database before inserting any row of its own | CONFIRMED. The purge deletes nothing there and asserts no structure the migration changes. |
| SAM conversion of a `suspend fun interface` | Every transport/provider fake in the repository is an explicit named class (`RecordingTransport`, `DeterministicReceiptAnalysisProviderFake`); `ReceiptAnalysisProviderPort` is not even a `fun interface` | **UNRESOLVED.** The lambda fakes originally written into Tasks 2 and 3 prescribed a construction path this repository never uses and this audit could not confirm. Both tasks now use a small explicit fake class that takes a behaviour lambda — proven pattern, no SAM conversion required. |

### Execution status

All five tasks are implemented. 459 tests pass, none skipped; nothing was committed, because no
commit was ever authorized. Six plan assumptions were disproved during execution and corrected
locally -- none of them changed approved behaviour, a contract, the persistence model, a security
boundary or scope, so none required stopping for approval:

| # | Disproved assumption | Correction |
| --- | --- | --- |
| 1 | Test cutoffs could use `Instant.now()` | Derived from the injected `MutableClock` instead; the store writes `updated_at` from that clock |
| 2 | `?.let(::ProviderCorrelationReference)` compiles | Callable reference binds the private constructor; replaced with an explicit lambda |
| 3 | "Every other implementer will compile" | Four test doubles needed the new method; all four now named in Task 1 |
| 4 | Preconditions needed no negative tests | Added negative tests for `limit`, `expiry` and `batchLimit` |
| 5 | Lambda fakes for a `suspend fun interface` | Caught by the assumption audit before execution; both tasks use explicit fake classes |
| 6 | A class with `operator fun invoke` can be passed as `sweep` | Kotlin does not implicitly convert it to a function type; wired as `sweep = { resolveReconciling() }` |

Assumptions 1-4 were found by executing Task 1, assumption 5 by the audit, assumption 6 by
executing Task 4. The audit is what stopped 5 from becoming two more execution failures.

### Original status note for Task 1

Task 1 was executed before this audit ran. It is complete: 440 tests pass, the three new
`findReconcilable` cases run against real PostgreSQL, and nothing was committed. Four plan
assumptions were disproved during that execution — the first four rows above — and each was
corrected locally without changing approved behaviour, contracts, persistence or scope. That the
same four failures are precisely what this skill exists to catch is the argument for running the
audit before the remaining tasks, not after.

---

### Task 1: Store exposes reconcilable operations

**Files:**
- Create: `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/ReconciliationCandidate.kt`
- Modify: `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/ReceiptAnalysisOperationStore.kt`
- Modify: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/persistence/PostgresReceiptAnalysisOperationStore.kt`
- Modify: `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/InMemoryReceiptAnalysisOperationStore.kt`
- Test: `src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/persistence/PostgresReceiptAnalysisOperationStoreTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `ReconciliationCandidate(operationId: ReceiptAnalysisOperationId, attemptId: ReceiptAnalysisAttemptId, correlationReference: ProviderCorrelationReference?, reconcilingSince: Instant)` and `suspend fun ReceiptAnalysisOperationStore.findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate>`.

- [ ] **Step 1: Write the failing test**

Append to `PostgresReceiptAnalysisOperationStoreTest`. Use the file's existing helpers for building a store and a start command; if their names differ from `newStore()` / `command(...)`, adapt the calls rather than adding new helpers.

```kotlin
@Test
fun `findReconcilable returns RECONCILING operations with and without a correlation reference`() = runBlocking {
    val store = newStore()

    val withRef = store.startOrGetExisting(command(user = "u-recon-1", request = "r-1")) as StartOutcome.Accepted
    store.claimInvocation(withRef.operation.operationId)
    store.applyOutcome(
        withRef.operation.operationId,
        OutcomeApplication.ReconcilingWithProviderCorrelation(ProviderCorrelationReference("resp_abc123"))
    )

    val withoutRef = store.startOrGetExisting(command(user = "u-recon-2", request = "r-2")) as StartOutcome.Accepted
    store.claimInvocation(withoutRef.operation.operationId)
    store.applyOutcome(withoutRef.operation.operationId, OutcomeApplication.ReconcilingDetected)

    val settled = store.startOrGetExisting(command(user = "u-recon-3", request = "r-3")) as StartOutcome.Accepted
    store.claimInvocation(settled.operation.operationId)
    store.applyOutcome(settled.operation.operationId, OutcomeApplication.Failed)

    val candidates = store.findReconcilable(limit = 10, notUpdatedSince = Instant.now().plusSeconds(60))

    val byId = candidates.associateBy { it.operationId }
    assertEquals(2, candidates.size, "only the two RECONCILING operations are reconcilable")
    assertEquals(
        ProviderCorrelationReference("resp_abc123"),
        byId.getValue(withRef.operation.operationId).correlationReference
    )
    assertNull(byId.getValue(withoutRef.operation.operationId).correlationReference)
    assertFalse(byId.containsKey(settled.operation.operationId), "a TERMINAL operation is never reconcilable")
}

@Test
fun `findReconcilable excludes operations updated after the cutoff and honours the limit`() = runBlocking {
    val store = newStore()
    repeat(3) { index ->
        val accepted = store.startOrGetExisting(command(user = "u-cut-$index", request = "r-$index")) as StartOutcome.Accepted
        store.claimInvocation(accepted.operation.operationId)
        store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingDetected)
    }

    assertEquals(
        0,
        store.findReconcilable(limit = 10, notUpdatedSince = Instant.now().minusSeconds(3600)).size,
        "nothing is old enough for a cutoff one hour in the past"
    )
    assertEquals(
        2,
        store.findReconcilable(limit = 2, notUpdatedSince = Instant.now().plusSeconds(60)).size,
        "the limit caps the batch"
    )
}
```

Add the imports `java.time.Instant`, `kotlin.test.assertNull` and `kotlin.test.assertFalse` if the file lacks them.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*PostgresReceiptAnalysisOperationStoreTest*"`
Expected: FAIL to compile — `Unresolved reference: findReconcilable`.

- [ ] **Step 3: Create the candidate type**

Create `ReconciliationCandidate.kt`:

```kotlin
package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import java.time.Instant

/**
 * One operation stuck in [ReceiptAnalysisOperationLifecycle.Reconciling], carrying everything a
 * resolver needs and nothing more. [correlationReference] is null for the uncertain-invocation and
 * attempt-mismatch paths, which never recorded one -- those can only be settled by expiry.
 *
 * This is the first and only place [ProviderCorrelationReference] becomes readable outside the
 * persistence layer, deliberately: it stays internal backend metadata and must never reach a wire
 * response. [reconcilingSince] is the operation's `updated_at`, i.e. when it entered RECONCILING.
 */
data class ReconciliationCandidate(
    val operationId: ReceiptAnalysisOperationId,
    val attemptId: ReceiptAnalysisAttemptId,
    val correlationReference: ProviderCorrelationReference?,
    val reconcilingSince: Instant
)
```

- [ ] **Step 4: Add the port method**

In `ReceiptAnalysisOperationStore.kt`, add inside the interface, after `claimInvocation`, and add `import java.time.Instant`:

```kotlin
    /**
     * Read-only. Returns at most [limit] non-tombstoned operations in
     * [ReceiptAnalysisOperationLifecycle.Reconciling] whose last update is at or before
     * [notUpdatedSince], oldest first -- never claims, locks or mutates anything. Exclusivity is
     * deliberately not offered: two resolvers acting on the same candidate are safe, because
     * [applyOutcome] is the single authority and answers the loser with
     * [ApplyOutcomeResult.AlreadyResolved].
     */
    suspend fun findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate>
```

- [ ] **Step 5: Implement it in the Postgres store**

In `PostgresReceiptAnalysisOperationStore.kt`, add after `claimInvocation`:

```kotlin
    override suspend fun findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate> =
        withContext(Dispatchers.IO) {
            require(limit > 0) { "limit must be positive, was $limit" }
            withConnection { connection ->
                connection.prepareStatement(
                    "SELECT o.operation_id, o.attempt_id, o.updated_at, c.correlation_reference " +
                        "FROM receipt_analysis_operation o " +
                        "LEFT JOIN receipt_analysis_provider_correlation c ON c.operation_id = o.operation_id " +
                        "WHERE o.lifecycle_state = 'RECONCILING' AND o.tombstoned_at IS NULL " +
                        "AND o.updated_at <= ? ORDER BY o.updated_at LIMIT ?"
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(notUpdatedSince))
                    statement.setInt(2, limit)
                    statement.executeQuery().use { rs ->
                        val candidates = mutableListOf<ReconciliationCandidate>()
                        while (rs.next()) {
                            candidates += ReconciliationCandidate(
                                operationId = ReceiptAnalysisOperationId(
                                    rs.getObject("operation_id", UUID::class.java).toString()
                                ),
                                attemptId = ReceiptAnalysisAttemptId(
                                    rs.getObject("attempt_id", UUID::class.java).toString()
                                ),
                                correlationReference = rs.getString("correlation_reference")
                                    ?.let(::ProviderCorrelationReference),
                                reconcilingSince = rs.getTimestamp("updated_at").toInstant()
                            )
                        }
                        candidates
                    }
                }
            }
        }
```

Add `import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReconciliationCandidate` and `import java.time.Instant`.

- [ ] **Step 6: Implement it in the in-memory test store**

`InMemoryReceiptAnalysisOperationStore` has no clock today. Give it one, with a default so existing call sites keep compiling:

```kotlin
class InMemoryReceiptAnalysisOperationStore(
    private val clock: Clock = Clock.systemUTC()
) : ReceiptAnalysisOperationStore {
```

Add a backing map next to `correlationByOperationId`:

```kotlin
    /** Mirrors the real store's `updated_at` for the moment an operation entered RECONCILING. */
    private val reconcilingSinceByOperationId = HashMap<ReceiptAnalysisOperationId, Instant>()
```

In `applyOutcome`, wherever this fake transitions an operation to `ReceiptAnalysisOperationLifecycle.Reconciling` (both the plain and the with-correlation branches), record the moment:

```kotlin
    reconcilingSinceByOperationId[operationId] = clock.instant()
```

Then add the query:

```kotlin
    override suspend fun findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate> =
        synchronized(lock) {
            require(limit > 0) { "limit must be positive, was $limit" }
            byOperationId.values
                .filter { it.lifecycle is ReceiptAnalysisOperationLifecycle.Reconciling }
                .filter { (reconcilingSinceByOperationId[it.operationId] ?: Instant.EPOCH) <= notUpdatedSince }
                .sortedBy { reconcilingSinceByOperationId[it.operationId] ?: Instant.EPOCH }
                .take(limit)
                .map { operation ->
                    ReconciliationCandidate(
                        operationId = operation.operationId,
                        attemptId = operation.attemptId,
                        correlationReference = correlationByOperationId[operation.operationId],
                        reconcilingSince = reconcilingSinceByOperationId[operation.operationId] ?: Instant.EPOCH
                    )
                }
        }
```

Add imports `java.time.Clock` and `java.time.Instant`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*PostgresReceiptAnalysisOperationStoreTest*" --tests "*InMemoryReceiptAnalysisOperationStoreTest*"`
Expected: PASS.

- [ ] **Step 8: Run the whole suite**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test`
Expected: PASS — every other implementer of `ReceiptAnalysisOperationStore` must still compile.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/ReconciliationCandidate.kt src/main/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/ReceiptAnalysisOperationStore.kt src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/persistence/PostgresReceiptAnalysisOperationStore.kt src/test/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/InMemoryReceiptAnalysisOperationStore.kt src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/persistence/PostgresReceiptAnalysisOperationStoreTest.kt
git commit -m "feat: expose reconcilable operations from the operation store"
```

---

### Task 2: Resolution policy

**Files:**
- Create: `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/ReceiptAnalysisRetrievalPort.kt`
- Create: `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/ResolveReconcilingOperationsUseCase.kt`
- Test: `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/ResolveReconcilingOperationsUseCaseTest.kt`

**Interfaces:**
- Consumes: `ReconciliationCandidate` and `findReconcilable` from Task 1.
- Produces: `fun interface ReceiptAnalysisRetrievalPort { suspend fun retrieve(attemptId: ReceiptAnalysisAttemptId, reference: ProviderCorrelationReference): ReceiptAnalysisProviderOutcome }`; `data class ResolutionSweepReport(val examined: Int, val resolved: Int, val expired: Int, val stillPending: Int)`; `class ResolveReconcilingOperationsUseCase(store, retrieval, clock, expiry: Duration, batchLimit: Int)` with `suspend operator fun invoke(): ResolutionSweepReport`.

**Policy this task implements.** For each candidate:

1. `correlationReference != null` → call `retrieve`, map the outcome with the existing `mapReceiptAnalysisProviderOutcome(candidate.attemptId, outcome)`. A terminal mapping (`Succeeded` / `Failed` / `FailedNoProvider`) is applied. A non-terminal mapping (still `queued`/`in_progress`, or an attempt-id mismatch) leaves the operation alone unless it has expired.
2. `correlationReference == null` → nothing can be retrieved; only expiry can settle it.
3. Expired means `reconcilingSince + expiry <= clock.instant()`. An expired operation settles as `OutcomeApplication.FailedNoProvider(ProviderTerminalFailureReason.RETRY_EXHAUSTED)` → `FAILED_NO_PROVIDER` + `RELEASED`.

**Why `RETRY_EXHAUSTED`:** it is the only reason already in the v1 wire vocabulary meaning "the backend gave up", so expiry needs no contract change. It is an imperfect fit for the uncertain-invocation path, where no response was ever confirmed. This is a deliberate, recorded trade-off — a truthful new reason would edit `contracts/openapi.json`, which this plan forbids.

- [ ] **Step 1: Write the failing test**

Create `ResolveReconcilingOperationsUseCaseTest.kt`. Note that no test here needs a valid receipt document: every terminal assertion uses an outcome that carries none.

```kotlin
package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisRetrievalPort
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResolveReconcilingOperationsUseCaseTest {

    private val startedAt: Instant = Instant.parse("2026-08-28T12:00:00Z")
    private val image = PreparedReceiptImage(ByteArray(32) { 3 }, "image/jpeg")

    private fun clockAt(instant: Instant) = Clock.fixed(instant, ZoneOffset.UTC)

    private suspend fun reconciling(
        store: InMemoryReceiptAnalysisOperationStore,
        user: String,
        application: OutcomeApplication
    ) {
        val accepted = store.startOrGetExisting(
            StartReceiptAnalysisOperationCommand(UserId(user), RequestId("req-$user"), image)
        ) as StartOutcome.Accepted
        store.claimInvocation(accepted.operation.operationId)
        store.applyOutcome(accepted.operation.operationId, application)
    }

    private suspend fun lifecycleOf(
        store: InMemoryReceiptAnalysisOperationStore,
        user: String
    ): ReceiptAnalysisOperationLifecycle =
        (store.findByRequestId(UserId(user), RequestId("req-$user")) as RequestLookup.Found).operation.lifecycle

    @Test
    fun `a terminal retrieval resolves the operation`() = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore(clockAt(startedAt))
        reconciling(
            store, "u1",
            OutcomeApplication.ReconcilingWithProviderCorrelation(ProviderCorrelationReference("resp_1"))
        )

        val useCase = ResolveReconcilingOperationsUseCase(
            store = store,
            retrieval = FakeRetrievalPort { attemptId, _ ->
                ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(
                    attemptId, ProviderTerminalFailureReason.RESPONSE_FAILED
                )
            },
            clock = clockAt(startedAt.plusSeconds(60)),
            expiry = Duration.ofHours(6),
            batchLimit = 50
        )

        val report = useCase()

        assertEquals(1, report.resolved)
        val lifecycle = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(lifecycleOf(store, "u1"))
        val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(lifecycle.result)
        assertEquals(ProviderTerminalFailureReason.RESPONSE_FAILED, result.reason)
    }

    @Test
    fun `a still-pending retrieval leaves the operation reconciling`() = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore(clockAt(startedAt))
        reconciling(
            store, "u2",
            OutcomeApplication.ReconcilingWithProviderCorrelation(ProviderCorrelationReference("resp_2"))
        )

        val useCase = ResolveReconcilingOperationsUseCase(
            store = store,
            retrieval = FakeRetrievalPort { attemptId, reference ->
                ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse(attemptId, reference)
            },
            clock = clockAt(startedAt.plusSeconds(60)),
            expiry = Duration.ofHours(6),
            batchLimit = 50
        )

        val report = useCase()

        assertEquals(0, report.resolved)
        assertEquals(1, report.stillPending)
        assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, lifecycleOf(store, "u2"))
    }

    @Test
    fun `an operation with no correlation reference is never retrieved and expires`() = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore(clockAt(startedAt))
        reconciling(store, "u3", OutcomeApplication.ReconcilingDetected)
        var retrievalCalls = 0

        val useCase = ResolveReconcilingOperationsUseCase(
            store = store,
            retrieval = FakeRetrievalPort { _, _ ->
                retrievalCalls++
                error("retrieval must never be called without a reference")
            },
            clock = clockAt(startedAt.plus(Duration.ofHours(7))),
            expiry = Duration.ofHours(6),
            batchLimit = 50
        )

        val report = useCase()

        assertEquals(0, retrievalCalls)
        assertEquals(1, report.expired)
        val lifecycle = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(lifecycleOf(store, "u3"))
        val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(lifecycle.result)
        assertEquals(ProviderTerminalFailureReason.RETRY_EXHAUSTED, result.reason)
        assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, lifecycle.ledgerEffect)
    }

    @Test
    fun `an operation younger than the expiry with no reference is left alone`() = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore(clockAt(startedAt))
        reconciling(store, "u4", OutcomeApplication.ReconcilingDetected)

        val useCase = ResolveReconcilingOperationsUseCase(
            store = store,
            retrieval = FakeRetrievalPort { _, _ -> error("retrieval must never be called without a reference") },
            clock = clockAt(startedAt.plus(Duration.ofHours(1))),
            expiry = Duration.ofHours(6),
            batchLimit = 50
        )

        val report = useCase()

        assertEquals(0, report.expired)
        assertEquals(1, report.stillPending)
        assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, lifecycleOf(store, "u4"))
    }

    @Test
    fun `a retrieval failure never stops the sweep`() = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore(clockAt(startedAt))
        reconciling(
            store, "u5",
            OutcomeApplication.ReconcilingWithProviderCorrelation(ProviderCorrelationReference("resp_5"))
        )
        reconciling(
            store, "u6",
            OutcomeApplication.ReconcilingWithProviderCorrelation(ProviderCorrelationReference("resp_6"))
        )

        val useCase = ResolveReconcilingOperationsUseCase(
            store = store,
            retrieval = FakeRetrievalPort { attemptId, reference ->
                if (reference == ProviderCorrelationReference("resp_5")) throw IllegalStateException("boom")
                ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure(
                    attemptId, ProviderTerminalFailureReason.RESPONSE_FAILED
                )
            },
            clock = clockAt(startedAt.plusSeconds(60)),
            expiry = Duration.ofHours(6),
            batchLimit = 50
        )

        val report = useCase()

        assertEquals(2, report.examined)
        assertEquals(1, report.resolved, "the healthy candidate is still resolved")
        assertTrue(report.stillPending >= 1)
    }

    /**
     * An explicit named fake, never a SAM lambda: every provider/transport double in this
     * repository is a class (`RecordingTransport`, `DeterministicReceiptAnalysisProviderFake`),
     * and SAM conversion of a `suspend fun interface` is not a pattern this codebase has ever
     * exercised. Taking the behaviour as a constructor lambda keeps each test as short as the
     * lambda form would have been.
     */
    private class FakeRetrievalPort(
        private val behaviour: suspend (ReceiptAnalysisAttemptId, ProviderCorrelationReference) -> ReceiptAnalysisProviderOutcome
    ) : ReceiptAnalysisRetrievalPort {
        override suspend fun retrieve(
            attemptId: ReceiptAnalysisAttemptId,
            reference: ProviderCorrelationReference
        ): ReceiptAnalysisProviderOutcome = behaviour(attemptId, reference)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*ResolveReconcilingOperationsUseCaseTest*"`
Expected: FAIL to compile — `Unresolved reference: ResolveReconcilingOperationsUseCase`.

- [ ] **Step 3: Create the retrieval port**

Create `ReceiptAnalysisRetrievalPort.kt`:

```kotlin
package com.tonycorreia.pricepulsebackend.application.receiptanalysis

/**
 * Retrieves the current state of an invocation already confirmed as pending, identified only by
 * its opaque [ProviderCorrelationReference]. Provider-neutral by construction: no OpenAI type,
 * model id or endpoint appears in this signature.
 *
 * Never starts a new invocation -- it reads one that already exists, so it costs no additional
 * provider invocation and needs no claim. Returns the same [ReceiptAnalysisProviderOutcome]
 * vocabulary the invocation path uses, so one mapper serves both.
 *
 * Implementations normalize every transport failure into an outcome and never throw, except
 * [kotlinx.coroutines.CancellationException], which always propagates.
 */
fun interface ReceiptAnalysisRetrievalPort {
    suspend fun retrieve(
        attemptId: ReceiptAnalysisAttemptId,
        reference: ProviderCorrelationReference
    ): ReceiptAnalysisProviderOutcome
}
```

- [ ] **Step 4: Write the use case**

Create `ResolveReconcilingOperationsUseCase.kt`:

```kotlin
package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisRetrievalPort
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What one sweep did. Counters only -- never an operation id, user id or correlation reference. */
data class ResolutionSweepReport(
    val examined: Int,
    val resolved: Int,
    val expired: Int,
    val stillPending: Int
)

/**
 * Resolves operations stuck in [ReceiptAnalysisOperationLifecycle.Reconciling]. Never calls
 * [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderPort] --
 * a reconciling operation already consumed its single invocation; this only reads the provider's
 * view of that invocation through [retrieval], and applies the outcome through
 * [ReceiptAnalysisOperationStore.applyOutcome], which stays the single authority for state and
 * credit.
 *
 * Safe to run concurrently with itself and with the start path: a candidate resolved by someone
 * else answers [ApplyOutcomeResult.AlreadyResolved], never a second ledger entry.
 */
class ResolveReconcilingOperationsUseCase(
    private val store: ReceiptAnalysisOperationStore,
    private val retrieval: ReceiptAnalysisRetrievalPort,
    private val clock: Clock,
    private val expiry: Duration,
    private val batchLimit: Int
) {
    init {
        require(!expiry.isNegative && !expiry.isZero) { "expiry must be positive, was $expiry" }
        require(batchLimit > 0) { "batchLimit must be positive, was $batchLimit" }
    }

    suspend operator fun invoke(): ResolutionSweepReport {
        val now = clock.instant()
        val candidates = store.findReconcilable(limit = batchLimit, notUpdatedSince = now)

        var resolved = 0
        var expired = 0
        var stillPending = 0

        for (candidate in candidates) {
            val application = try {
                decide(candidate, now)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // One candidate's retrieval failure never aborts the sweep and never guesses an
                // outcome -- the operation stays RECONCILING until a later pass or expiry.
                null
            }

            if (application == null) {
                stillPending++
                continue
            }

            val applied = try {
                store.applyOutcome(candidate.operationId, application)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                stillPending++
                continue
            }

            when (applied) {
                is ApplyOutcomeResult.Applied, is ApplyOutcomeResult.AlreadyResolved ->
                    if (isExpiry(application)) expired++ else resolved++
                is ApplyOutcomeResult.Rejected -> stillPending++
            }
        }

        return ResolutionSweepReport(
            examined = candidates.size,
            resolved = resolved,
            expired = expired,
            stillPending = stillPending
        )
    }

    /** Null means "leave it RECONCILING". */
    private suspend fun decide(candidate: ReconciliationCandidate, now: Instant): OutcomeApplication? {
        val hasExpired = !candidate.reconcilingSince.plus(expiry).isAfter(now)

        val reference = candidate.correlationReference
            ?: return if (hasExpired) expiredApplication() else null

        val application = mapReceiptAnalysisProviderOutcome(
            candidate.attemptId,
            retrieval.retrieve(candidate.attemptId, reference)
        )

        return when (application) {
            is OutcomeApplication.Succeeded,
            is OutcomeApplication.Failed,
            is OutcomeApplication.FailedNoProvider -> application
            // Still pending, or an attempt-id mismatch: never resolved on this evidence. Expiry is
            // the only way out, exactly as for a candidate that never had a reference.
            is OutcomeApplication.ReconcilingDetected,
            is OutcomeApplication.ReconcilingWithProviderCorrelation,
            is OutcomeApplication.AttemptIdMismatch -> if (hasExpired) expiredApplication() else null
        }
    }

    private fun isExpiry(application: OutcomeApplication): Boolean =
        application is OutcomeApplication.FailedNoProvider &&
            application.reason == ProviderTerminalFailureReason.RETRY_EXHAUSTED

    /**
     * RETRY_EXHAUSTED is reused deliberately: it is the only reason already in the v1 wire
     * vocabulary meaning "the backend gave up", so expiry needs no contract change. It is an
     * imperfect fit for the uncertain-invocation path, where no response was ever confirmed --
     * a truthful new reason belongs to a slice allowed to version the contract.
     */
    private fun expiredApplication(): OutcomeApplication =
        OutcomeApplication.FailedNoProvider(ProviderTerminalFailureReason.RETRY_EXHAUSTED)
}
```

Note the known imprecision in `isExpiry`: a provider that genuinely answers `RETRY_EXHAUSTED` is counted as expired. The counters are observability only and never affect state, so this is acceptable; do not add a second code path to disambiguate them.

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*ResolveReconcilingOperationsUseCaseTest*"`
Expected: PASS, all five tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/ReceiptAnalysisRetrievalPort.kt src/main/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/ResolveReconcilingOperationsUseCase.kt src/test/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/ResolveReconcilingOperationsUseCaseTest.kt
git commit -m "feat: resolve reconciling operations through retrieval and expiry"
```

---

### Task 3: OpenAI retrieval adapter

**Files:**
- Create: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai/OpenAiResponseClassifier.kt`
- Modify: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai/OpenAiReceiptAnalysisProviderAdapter.kt`
- Modify: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai/OpenAiOutboundRequest.kt`
- Modify: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai/OpenAiCioHttpTransport.kt`
- Create: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai/OpenAiReceiptAnalysisRetrievalAdapter.kt`
- Test: `src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai/OpenAiReceiptAnalysisRetrievalAdapterTest.kt`

**Interfaces:**
- Consumes: `ReceiptAnalysisRetrievalPort` from Task 2.
- Produces: `OpenAiReceiptAnalysisRetrievalAdapter(credentialProvider: OpenAiCredentialProvider, transport: OpenAiHttpTransport) : ReceiptAnalysisRetrievalPort`; `OpenAiOutboundRequest.Companion.retrieval(reference: ProviderCorrelationReference, credential: OpenAiCredential): OpenAiOutboundRequest?`; `internal object OpenAiResponseClassifier` with `fun classify(attemptId: ReceiptAnalysisAttemptId, received: OpenAiHttpTransportResult.Received): ReceiptAnalysisProviderOutcome`.

**Security requirement for this task.** The retrieval path builds a URL from a value the provider supplied. The correlation reference must be validated against `^[A-Za-z0-9_-]{1,128}$` before it can become part of a path. Anything else returns `null` from the factory and the adapter reports `NotInvoked(REQUEST_CONSTRUCTION_FAILED)` — never a request. This closes path traversal (`../`), absolute-URL substitution and query injection, each of which would send the `Authorization` header somewhere unintended.

- [ ] **Step 1: Write the failing test**

Create `OpenAiReceiptAnalysisRetrievalAdapterTest.kt`. No valid receipt document is needed: every assertion uses a response status that carries none.

```kotlin
package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class OpenAiReceiptAnalysisRetrievalAdapterTest {

    private val attemptId = ReceiptAnalysisAttemptId("attempt-1")
    private val credential = OpenAiCredential("sk-test")

    private fun adapter(transport: OpenAiHttpTransport) =
        OpenAiReceiptAnalysisRetrievalAdapter(OpenAiCredentialProvider { credential }, transport)

    /**
     * Explicit named fake, matching `OpenAiReceiptAnalysisProviderAdapterTest.RecordingTransport`.
     * Never a SAM lambda: no test in this repository SAM-converts a `suspend fun interface`, and
     * the assumption audit could not confirm that path.
     */
    private class FakeTransport(
        private val respond: (OpenAiOutboundRequest) -> OpenAiHttpTransportResult
    ) : OpenAiHttpTransport {
        override suspend fun send(request: OpenAiOutboundRequest): OpenAiHttpTransportResult = respond(request)
    }

    @Test
    fun `a retrieval is a GET on the response path`() = runBlocking {
        var seenMethod: String? = null
        var seenPath: String? = null
        val body = """{"id":"resp_abc123","status":"failed"}""".toByteArray()

        val outcome = adapter(
            FakeTransport { request ->
                seenMethod = request.method
                seenPath = request.path
                OpenAiHttpTransportResult.Received(200, body, false)
            }
        ).retrieve(attemptId, ProviderCorrelationReference("resp_abc123"))

        assertEquals("GET", seenMethod)
        assertEquals("/v1/responses/resp_abc123", seenPath)
        val failure = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.RESPONSE_FAILED, failure.reason)
    }

    @Test
    fun `a still-queued retrieval stays pending and preserves the reference`() = runBlocking {
        val body = """{"id":"resp_abc123","status":"queued"}""".toByteArray()

        val outcome = adapter(FakeTransport { OpenAiHttpTransportResult.Received(200, body, false) })
            .retrieve(attemptId, ProviderCorrelationReference("resp_abc123"))

        val pending = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedPendingResponse>(outcome)
        assertEquals(ProviderCorrelationReference("resp_abc123"), pending.correlationReference)
    }

    @Test
    fun `no response leaves the invocation uncertain`() = runBlocking {
        val outcome = adapter(FakeTransport { OpenAiHttpTransportResult.NoResponse })
            .retrieve(attemptId, ProviderCorrelationReference("resp_abc123"))

        assertIs<ReceiptAnalysisProviderOutcome.InvocationUncertain>(outcome)
    }

    @Test
    fun `a reference that is not a safe path segment never reaches the transport`() = runBlocking {
        val hostile = listOf("../../secrets", "resp/../x", "resp abc", "https://evil.example/x", "resp?x=1", "a".repeat(129))
        for (raw in hostile) {
            var called = false
            val outcome = adapter(
                FakeTransport {
                    called = true
                    OpenAiHttpTransportResult.NoResponse
                }
            ).retrieve(attemptId, ProviderCorrelationReference(raw))

            assertFalse(called, "the transport must never be reached for reference: $raw")
            val notInvoked = assertIs<ReceiptAnalysisProviderOutcome.NotInvoked>(outcome)
            assertEquals(ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED, notInvoked.reason)
        }
    }

    @Test
    fun `a 404 is an unrecognized response rather than a silent success`() = runBlocking {
        val outcome = adapter(FakeTransport { OpenAiHttpTransportResult.Received(404, "{}".toByteArray(), false) })
            .retrieve(attemptId, ProviderCorrelationReference("resp_abc123"))

        val failure = assertIs<ReceiptAnalysisProviderOutcome.InvocationConfirmedTerminalFailure>(outcome)
        assertEquals(ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE, failure.reason)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*OpenAiReceiptAnalysisRetrievalAdapterTest*"`
Expected: FAIL to compile — `Unresolved reference: OpenAiReceiptAnalysisRetrievalAdapter`.

- [ ] **Step 3: Extract the classifier**

Create `OpenAiResponseClassifier.kt` as an `internal object`, and move into it — unchanged — these private members currently in `OpenAiReceiptAnalysisProviderAdapter`: `classifyReceived`, `classify429`, `classify200`, `classifyPending`, `classifyCompleted`, `parseJsonOrNull`, `MAPPER` and `BILLING_OR_QUOTA_ERROR_CODES`. Rename `classifyReceived` to `classify` and make it the object's only non-private member:

```kotlin
internal fun classify(
    attemptId: ReceiptAnalysisAttemptId,
    received: OpenAiHttpTransportResult.Received
): ReceiptAnalysisProviderOutcome
```

Do not change any classification behaviour in this step. The move must be behaviour-preserving; the existing `OpenAiReceiptAnalysisProviderAdapterTest` is the proof.

- [ ] **Step 4: Point the provider adapter at the classifier**

In `OpenAiReceiptAnalysisProviderAdapter`, replace `classifyReceived(attempt.attemptId, result)` with `OpenAiResponseClassifier.classify(attempt.attemptId, result)` and delete the moved private members. Keep `emitTelemetry`, `parseUsage` and `nonNegativeLongOrNull` where they are — they are telemetry, not classification, and the retrieval path deliberately emits none. `emitTelemetry` uses `parseJsonOrNull`; give the adapter its own private copy of that one-liner and its own `MAPPER` rather than exposing the classifier's internals.

- [ ] **Step 5: Run the existing adapter tests**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*OpenAiReceiptAnalysisProviderAdapterTest*"`
Expected: PASS — proving the extraction changed no behaviour.

- [ ] **Step 6: Add the validated retrieval request factory**

In `OpenAiOutboundRequest.kt`, add to the companion object, and add `import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference`:

```kotlin
        private val SAFE_REFERENCE = Regex("^[A-Za-z0-9_-]{1,128}$")

        /**
         * Returns null -- never a request -- when [reference] is not a safe single path segment.
         * The value comes from the provider, so it is never trusted into a URL unchecked: `../`,
         * an absolute URL, a query string or whitespace would each redirect a request carrying the
         * `Authorization` header to an unintended destination.
         */
        fun retrieval(reference: ProviderCorrelationReference, credential: OpenAiCredential): OpenAiOutboundRequest? {
            val value = reference.value()
            if (!SAFE_REFERENCE.matches(value)) return null
            return OpenAiOutboundRequest(
                "GET",
                "/v1/responses/$value",
                "application/json",
                "Bearer " + credential.value(),
                ByteArray(0)
            )
        }
```

- [ ] **Step 7: Make the transport honour the path**

In `OpenAiCioHttpTransport.send`, replace the fixed-URL request with a fixed-**origin** request plus the caller's already-validated path, sending a body only when there is one. The body-reading half of the method is unchanged:

```kotlin
        val response = try {
            client.request(PRODUCTION_ORIGIN + request.path) {
                method = HttpMethod.parse(request.method)
                header(HttpHeaders.Authorization, request.authorizationHeaderValue())
                val body = request.bodyBytes()
                if (body.isNotEmpty()) {
                    contentType(ContentType.parse(request.contentType))
                    setBody(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return OpenAiHttpTransportResult.NoResponse
        }
```

In the companion object, replace `PRODUCTION_URL` with:

```kotlin
        // Origin only, still a literal: the destination host can never come from configuration or
        // from a provider-supplied value. Only the path varies, and only after the caller has
        // validated it as a single safe segment (OpenAiOutboundRequest.retrieval).
        private const val PRODUCTION_ORIGIN = "https://api.openai.com"
```

`OpenAiOutboundRequest`'s `POST` factory already sets `path = "/v1/responses"`, so the invocation URL is byte-identical to before this change.

- [ ] **Step 8: Write the retrieval adapter**

Create `OpenAiReceiptAnalysisRetrievalAdapter.kt`:

```kotlin
package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisRetrievalPort

/**
 * Retrieves an already-pending OpenAI response by its id. Reads only: never sends a prompt, an
 * image or a new invocation, so it can never consume a second provider invocation for an
 * operation. Shares [OpenAiResponseClassifier] with the invocation adapter, so a `completed`
 * response retrieved here is validated by exactly the same rules that would have applied had it
 * arrived synchronously.
 *
 * Emits no telemetry: an [OpenAiInvocationTelemetry] event means "an invocation happened", and a
 * retrieval is not one.
 */
class OpenAiReceiptAnalysisRetrievalAdapter(
    private val credentialProvider: OpenAiCredentialProvider,
    private val transport: OpenAiHttpTransport
) : ReceiptAnalysisRetrievalPort {

    override suspend fun retrieve(
        attemptId: ReceiptAnalysisAttemptId,
        reference: ProviderCorrelationReference
    ): ReceiptAnalysisProviderOutcome {
        val credential = credentialProvider.credential()
            ?: return ReceiptAnalysisProviderOutcome.NotInvoked(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE)

        val request = OpenAiOutboundRequest.retrieval(reference, credential)
            ?: return ReceiptAnalysisProviderOutcome.NotInvoked(ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED)

        return when (val result = transport.send(request)) {
            is OpenAiHttpTransportResult.NoResponse ->
                ReceiptAnalysisProviderOutcome.InvocationUncertain(attemptId)
            is OpenAiHttpTransportResult.Received ->
                OpenAiResponseClassifier.classify(attemptId, result)
        }
    }
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*OpenAi*"`
Expected: PASS — the new retrieval tests and every existing OpenAI test. The specific regression
guard is `OpenAiCioHttpTransportTest.kt:97`, which asserts the exact invocation URL, `HttpMethod.Post`,
`Authorization`, `Content-Type` and body: it must pass untouched. `Content-Type` survives the
conditional body block because the POST body is never empty.

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai
git commit -m "feat: retrieve a pending provider response by its correlation reference"
```

---

### Task 4: Background runner and wiring

**Files:**
- Create: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/ReconciliationRunner.kt`
- Modify: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/Application.kt`
- Modify: `build.gradle.kts`
- Test: `src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/ReconciliationRunnerTest.kt`

**Interfaces:**
- Consumes: `ResolveReconcilingOperationsUseCase` and `ResolutionSweepReport` from Task 2; `OpenAiReceiptAnalysisRetrievalAdapter` from Task 3.
- Produces: `class ReconciliationRunner(sweep: suspend () -> ResolutionSweepReport, interval: Duration)` with `fun start(scope: CoroutineScope): Job`.

- [ ] **Step 1: Add the coroutines test dependency**

In `build.gradle.kts`, add to `dependencies`:

```kotlin
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

- [ ] **Step 2: Write the failing test**

Create `ReconciliationRunnerTest.kt`:

```kotlin
package com.tonycorreia.pricepulsebackend.infrastructure

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ResolutionSweepReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReconciliationRunnerTest {

    @Test
    fun `sweeps repeatedly on the configured interval until cancelled`() = runTest {
        val sweeps = AtomicInteger(0)
        val runner = ReconciliationRunner(
            sweep = { ResolutionSweepReport(sweeps.incrementAndGet(), 0, 0, 0) },
            interval = Duration.ofSeconds(30)
        )

        val job = runner.start(this)
        advanceTimeBy(95_000)
        job.cancel()

        assertTrue(sweeps.get() >= 3, "expected at least 3 sweeps in 95s at a 30s interval, was ${sweeps.get()}")
    }

    @Test
    fun `a failing sweep never stops the loop`() = runTest {
        val sweeps = AtomicInteger(0)
        val runner = ReconciliationRunner(
            sweep = {
                val n = sweeps.incrementAndGet()
                if (n == 1) throw IllegalStateException("boom")
                ResolutionSweepReport(0, 0, 0, 0)
            },
            interval = Duration.ofSeconds(30)
        )

        val job = runner.start(this)
        advanceTimeBy(95_000)
        job.cancel()

        assertTrue(sweeps.get() >= 3, "the loop survived the first failure, was ${sweeps.get()}")
    }

    @Test
    fun `rejects a non-positive interval`() {
        val failure = runCatching {
            ReconciliationRunner(sweep = { ResolutionSweepReport(0, 0, 0, 0) }, interval = Duration.ZERO)
        }.exceptionOrNull()

        assertIs<IllegalArgumentException>(failure)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*ReconciliationRunnerTest*"`
Expected: FAIL to compile — `Unresolved reference: ReconciliationRunner`.

- [ ] **Step 4: Write the runner**

Create `ReconciliationRunner.kt`:

```kotlin
package com.tonycorreia.pricepulsebackend.infrastructure

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ResolutionSweepReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Drives [sweep] on a fixed interval. Owns no policy whatsoever -- what a sweep decides lives in
 * `ResolveReconcilingOperationsUseCase`; this class only decides *when*.
 *
 * A failing sweep is logged and the loop continues: the next interval retries the same candidates,
 * because a failed sweep never mutates state. Cancellation always ends the loop and is never
 * swallowed by the failure handling.
 */
class ReconciliationRunner(
    private val sweep: suspend () -> ResolutionSweepReport,
    private val interval: Duration
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive, was $interval" }
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            try {
                val report = sweep()
                if (report.examined > 0) {
                    LOGGER.info(
                        "reconciliation sweep examined={} resolved={} expired={} stillPending={}",
                        report.examined, report.resolved, report.expired, report.stillPending
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                // Never the exception object: a driver or client message can carry connection
                // details. Same discipline the routes already apply.
                LOGGER.warn("reconciliation sweep failed; retrying at the next interval")
            }
            delay(interval.toMillis())
        }
    }

    private companion object {
        private val LOGGER = LoggerFactory.getLogger(ReconciliationRunner::class.java)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*ReconciliationRunnerTest*"`
Expected: PASS, all three tests.

- [ ] **Step 6: Wire it into `Application.kt`**

Add these constants next to the other operational values:

```kotlin
private const val RECONCILIATION_INTERVAL_SECONDS = 60L
private const val RECONCILIATION_EXPIRY_HOURS = 6L
private const val RECONCILIATION_BATCH_LIMIT = 50
```

In `main()`, inside the existing `try { ... } finally { transport.close() }` block, after `startReceiptAnalysis` is built and before `embeddedServer(...)`:

```kotlin
            val reconciliationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val reconciliationJob = ReconciliationRunner(
                sweep = ResolveReconcilingOperationsUseCase(
                    store = operationStore,
                    retrieval = OpenAiReceiptAnalysisRetrievalAdapter(credentialProvider, transport),
                    clock = Clock.systemUTC(),
                    expiry = Duration.ofHours(RECONCILIATION_EXPIRY_HOURS),
                    batchLimit = RECONCILIATION_BATCH_LIMIT
                ),
                interval = Duration.ofSeconds(RECONCILIATION_INTERVAL_SECONDS)
            ).start(reconciliationScope)
```

Wrap the server start in its own `try`/`finally` so the loop always stops before the transport closes:

```kotlin
            try {
                embeddedServer(Netty, port = port, host = "0.0.0.0") {
                    module(
                        firebaseIdTokenVerifier,
                        operationStore,
                        pollingRateLimiter,
                        startReceiptAnalysis,
                        InProcessAdmissionRateLimiter(Clock.systemUTC()),
                        KtorReceiptUploadReader()
                    )
                }.start(wait = true)
            } finally {
                reconciliationJob.cancel()
                reconciliationScope.cancel()
            }
```

Add imports: `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.SupervisorJob`, `kotlinx.coroutines.cancel`, `java.time.Duration`, `com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ResolveReconcilingOperationsUseCase`, `com.tonycorreia.pricepulsebackend.infrastructure.openai.OpenAiReceiptAnalysisRetrievalAdapter`.

- [ ] **Step 7: Run the whole suite**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test`
Expected: PASS, including `ApplicationTest`.

- [ ] **Step 8: Commit**

```bash
git add build.gradle.kts src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/ReconciliationRunner.kt src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/Application.kt src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/ReconciliationRunnerTest.kt
git commit -m "feat: run the reconciliation sweep on an interval"
```

---

### Task 5: Delete the payload on terminal resolution (ADR-003)

**Files:**
- Modify: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/persistence/PostgresReceiptAnalysisOperationStore.kt`
- Create: `src/main/resources/db/migration/V3__purge_terminal_payload_backlog.sql`
- Test: `src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/persistence/PostgresReceiptAnalysisOperationStoreTest.kt`
- Delete: `src/test/kotlin/com/tonycorreia/pricepulsebackend/diagnostics/ReconcilingLatencyDiagnosticTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: no new public API. The observable change is that `receipt_analysis_payload` holds no row for a `TERMINAL` operation.

**Ordering requirement.** `guard_payload_mutation` rejects a payload `DELETE` unless the operation is already `TERMINAL`. The delete must therefore come **after** the `UPDATE ... SET lifecycle_state = 'TERMINAL'` inside `applyTerminalTransition`, in the same transaction — exactly where the correlation-row delete already sits.

- [ ] **Step 1: Write the failing test**

Append to `PostgresReceiptAnalysisOperationStoreTest`:

```kotlin
@Test
fun `the stored payload is deleted when the operation resolves terminally`() = runBlocking {
    val store = newStore()
    val accepted = store.startOrGetExisting(command(user = "u-payload", request = "r-payload")) as StartOutcome.Accepted

    assertEquals(1, payloadRowCount(accepted.operation.operationId), "a fresh operation keeps its payload")

    store.claimInvocation(accepted.operation.operationId)
    store.applyOutcome(accepted.operation.operationId, OutcomeApplication.Failed)

    assertEquals(0, payloadRowCount(accepted.operation.operationId), "a terminal operation keeps no payload")
}

@Test
fun `a reconciling operation keeps its payload until it resolves`() = runBlocking {
    val store = newStore()
    val accepted = store.startOrGetExisting(command(user = "u-payload-2", request = "r-payload-2")) as StartOutcome.Accepted

    store.claimInvocation(accepted.operation.operationId)
    store.applyOutcome(accepted.operation.operationId, OutcomeApplication.ReconcilingDetected)
    assertEquals(1, payloadRowCount(accepted.operation.operationId), "RECONCILING is not terminal, so the payload stays")

    store.applyOutcome(
        accepted.operation.operationId,
        OutcomeApplication.FailedNoProvider(ProviderTerminalFailureReason.RETRY_EXHAUSTED)
    )
    assertEquals(0, payloadRowCount(accepted.operation.operationId))
}
```

Add this helper next to the existing `correlationRowCount`, mirroring its exact shape -- the shared
`verificationConnection` (autoCommit) and a `ReceiptAnalysisOperationId`, never a second connection
opened from `dataSource`:

```kotlin
private fun payloadRowCount(operationId: ReceiptAnalysisOperationId): Int =
    verificationConnection.prepareStatement(
        "SELECT count(*) FROM receipt_analysis_payload WHERE operation_id = ?"
    ).use { statement ->
        statement.setObject(1, UUID.fromString(operationId.value))
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*PostgresReceiptAnalysisOperationStoreTest*"`
Expected: FAIL — `expected: <0> but was: <1>`.

- [ ] **Step 3: Delete the payload in the terminal transition**

In `applyTerminalTransition`, immediately after the existing correlation-row `DELETE`, add:

```kotlin
        // ADR-003: the bytes sent to the provider exist only while the operation might still need
        // them. The operation row above is already TERMINAL, satisfying guard_payload_mutation,
        // which rejects this delete in any other order. Same transaction as the state and ledger
        // writes, so a resolved operation never leaves its image behind.
        connection.prepareStatement(
            "DELETE FROM receipt_analysis_payload WHERE operation_id = ?"
        ).use { statement ->
            statement.setObject(1, opUuid)
            statement.executeUpdate()
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*PostgresReceiptAnalysisOperationStoreTest*" --tests "*PostgresSchemaContractTest*"`
Expected: PASS. `PostgresSchemaContractTest` proves the invariant function still accepts the resulting rows.

- [ ] **Step 5: Write the backlog purge migration**

Create `V3__purge_terminal_payload_backlog.sql`:

```sql
-- ---------------------------------------------------------------------------------------------
-- One-off purge of the payload backlog accumulated before ADR-003.
--
-- Until this version, nothing ever deleted receipt_analysis_payload: the schema authorized the
-- delete for a TERMINAL operation and no code ever issued it, so every uploaded receipt image was
-- retained indefinitely. Deleting on terminal resolution is now part of applyTerminalTransition;
-- this migration settles the rows that predate it.
--
-- Deliberately limited to TERMINAL operations. guard_payload_mutation rejects any other row, and
-- a non-terminal operation may still legitimately need its payload for crash recovery. Rows for
-- operations stuck in RECONCILING are therefore NOT removed here -- the reconciliation sweep
-- resolves them, and their delete then happens through the normal terminal path.
-- ---------------------------------------------------------------------------------------------

DELETE FROM receipt_analysis_payload p
USING receipt_analysis_operation o
WHERE p.operation_id = o.operation_id
  AND o.lifecycle_state = 'TERMINAL';
```

- [ ] **Step 6: Remove the temporary diagnostic test**

`src/test/kotlin/com/tonycorreia/pricepulsebackend/diagnostics/ReconcilingLatencyDiagnosticTest.kt` is the investigation harness. Its central assertion — that `RECONCILING` never resolves — is now false by design, and its remaining cases are covered by `ResolveReconcilingOperationsUseCaseTest`.

```bash
rm src/test/kotlin/com/tonycorreia/pricepulsebackend/diagnostics/ReconcilingLatencyDiagnosticTest.kt
```

- [ ] **Step 7: Run the whole suite**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test`
Expected: PASS. The Testcontainers tests run Flyway, so a migration the triggers reject fails here.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/persistence/PostgresReceiptAnalysisOperationStore.kt src/main/resources/db/migration/V3__purge_terminal_payload_backlog.sql src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/persistence/PostgresReceiptAnalysisOperationStoreTest.kt
git rm src/test/kotlin/com/tonycorreia/pricepulsebackend/diagnostics/ReconcilingLatencyDiagnosticTest.kt
git commit -m "feat: delete the receipt payload on terminal resolution and purge the backlog"
```

---

## Verification after all tasks

Evidence to record, compared against the root-cause investigation's measurements:

1. **Time to terminal is finite.** With a 60s interval and a 6h expiry, an operation entering `RECONCILING` resolves at the next sweep once the provider has finished, or at expiry otherwise. Before this plan it was unbounded — 51 POSTs and 50 polls left it `Reconciling`.
2. **Provider invocations stay at exactly one per operation.** Verify with:
   `grep -rn "ReceiptAnalysisProviderPort" src/main/kotlin/com/tonycorreia/pricepulsebackend/application/receiptanalysis/orchestration/ResolveReconcilingOperationsUseCase.kt` — expected: no match.
3. **No contract drift.** Compare `contracts/` against the state at the start of this work, not
   against `HEAD`: `receipt-analysis-result.v1.schema.json` already carries unrelated uncommitted
   local edits, so a bare `git diff --stat contracts/` reports them and looks like a false alarm.
   The check that matters is that no task in this plan opens a file under `contracts/`.
4. **No payload survives a terminal operation.** Covered by the two Postgres tests in Task 5.

Regression risks a reviewer should probe:

- The invocation path must be byte-identical after the transport change: same URL, same method, same headers, same body.
- Expiry must never fire for an operation younger than the window, even after repeated retrieval failures.
- `AlreadyResolved` must never produce a second ledger entry when a sweep races the start path.
- A `429` from the provider during retrieval must not settle an operation as terminal on rate-limiting alone — check what `OpenAiResponseClassifier` returns for 429 and confirm the resulting lifecycle is the intended one.

## Known limitations carried by this plan

- `RETRY_EXHAUSTED` is reused as the expiry reason to avoid a contract change. It is semantically imprecise for the uncertain-invocation path. Revisit in a slice authorized to version the v1 contract.
- There is still no cancellation route: a user abandoning the UI leaves the operation running and its credit reserved until the sweep or expiry settles it. Out of scope here, recorded in the pilot specification.
- The interval, expiry and batch size are compile-time constants, matching how every other operational value in `Application.kt` is set today. Making them configurable is a separate decision.
