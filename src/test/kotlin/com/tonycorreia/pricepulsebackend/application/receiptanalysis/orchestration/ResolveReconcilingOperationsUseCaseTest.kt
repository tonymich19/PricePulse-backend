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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Network-free tests for the only class that decides how a RECONCILING operation is settled.
 * Every clock here is a fixed [Clock] -- the use case reads "now" only from its injected clock, so
 * a wall-clock test would prove nothing about the expiry boundary.
 */
class ResolveReconcilingOperationsUseCaseTest {

    private val startedAt: Instant = Instant.parse("2026-08-28T12:00:00Z")
    private val image = PreparedReceiptImage(ByteArray(32) { 3 }, "image/jpeg")

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    private suspend fun reconciling(
        store: InMemoryReceiptAnalysisOperationStore,
        user: String,
        application: OutcomeApplication
    ) {
        val accepted = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(
                StartReceiptAnalysisOperationCommand(UserId(user), RequestId("req-$user"), image)
            )
        )
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(accepted.operation.operationId))
        assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(accepted.operation.operationId, application))
    }

    private suspend fun lifecycleOf(
        store: InMemoryReceiptAnalysisOperationStore,
        user: String
    ): ReceiptAnalysisOperationLifecycle =
        assertIs<RequestLookup.Found>(store.findByRequestId(UserId(user), RequestId("req-$user"))).operation.lifecycle

    @Test
    fun `a terminal retrieval resolves the operation`(): Unit = runBlocking {
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

        assertEquals(1, report.examined)
        assertEquals(1, report.resolved)
        assertEquals(0, report.expired)
        val lifecycle = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(lifecycleOf(store, "u1"))
        val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(lifecycle.result)
        assertEquals(ProviderTerminalFailureReason.RESPONSE_FAILED, result.reason)
    }

    @Test
    fun `a still-pending retrieval leaves the operation reconciling`(): Unit = runBlocking {
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
    fun `an operation with no correlation reference is never retrieved and expires`(): Unit = runBlocking {
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
        assertEquals(0, report.resolved)
        val lifecycle = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(lifecycleOf(store, "u3"))
        val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(lifecycle.result)
        assertEquals(ProviderTerminalFailureReason.RETRY_EXHAUSTED, result.reason)
        assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, lifecycle.ledgerEffect)
    }

    @Test
    fun `an operation younger than the expiry with no reference is left alone`(): Unit = runBlocking {
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
    fun `expiry fires exactly at the boundary, never before it`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore(clockAt(startedAt))
        reconciling(store, "u7", OutcomeApplication.ReconcilingDetected)

        fun useCaseAt(now: Instant) = ResolveReconcilingOperationsUseCase(
            store = store,
            retrieval = FakeRetrievalPort { _, _ -> error("retrieval must never be called without a reference") },
            clock = clockAt(now),
            expiry = Duration.ofHours(6),
            batchLimit = 50
        )

        // One instant before the boundary the operation is untouched.
        assertEquals(0, useCaseAt(startedAt.plus(Duration.ofHours(6)).minusMillis(1))().expired)
        assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, lifecycleOf(store, "u7"))

        // Exactly at the boundary it expires -- the comparison is inclusive.
        assertEquals(1, useCaseAt(startedAt.plus(Duration.ofHours(6)))().expired)
        assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(lifecycleOf(store, "u7"))
    }

    @Test
    fun `a retrieval failure never stops the sweep`(): Unit = runBlocking {
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
        assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, lifecycleOf(store, "u5"))
    }

    @Test
    fun `the batch limit caps how many candidates one sweep examines`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore(clockAt(startedAt))
        reconciling(store, "b1", OutcomeApplication.ReconcilingDetected)
        reconciling(store, "b2", OutcomeApplication.ReconcilingDetected)
        reconciling(store, "b3", OutcomeApplication.ReconcilingDetected)

        val useCase = ResolveReconcilingOperationsUseCase(
            store = store,
            retrieval = FakeRetrievalPort { _, _ -> error("retrieval must never be called without a reference") },
            clock = clockAt(startedAt.plusSeconds(60)),
            expiry = Duration.ofHours(6),
            batchLimit = 2
        )

        assertEquals(2, useCase().examined)
    }

    @Test
    fun `rejects a non-positive expiry or batch limit`() {
        val store = InMemoryReceiptAnalysisOperationStore(clockAt(startedAt))
        val retrieval = FakeRetrievalPort { _, _ -> error("never called") }

        assertFailsWith<IllegalArgumentException> {
            ResolveReconcilingOperationsUseCase(store, retrieval, clockAt(startedAt), Duration.ZERO, 50)
        }
        assertFailsWith<IllegalArgumentException> {
            ResolveReconcilingOperationsUseCase(store, retrieval, clockAt(startedAt), Duration.ofHours(-1), 50)
        }
        assertFailsWith<IllegalArgumentException> {
            ResolveReconcilingOperationsUseCase(store, retrieval, clockAt(startedAt), Duration.ofHours(6), 0)
        }
    }

    /**
     * An explicit named fake, never a SAM lambda: every provider/transport double in this
     * repository is a class (`RecordingTransport`, `DeterministicReceiptAnalysisProviderFake`),
     * and SAM conversion of a `suspend fun interface` is not a pattern this codebase exercises.
     * Taking the behaviour as a constructor lambda keeps each test as short as a lambda fake.
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
