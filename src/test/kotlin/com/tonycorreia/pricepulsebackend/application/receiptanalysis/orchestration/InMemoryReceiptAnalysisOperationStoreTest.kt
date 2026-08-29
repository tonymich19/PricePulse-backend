package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderRejectionReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InMemoryReceiptAnalysisOperationStoreTest {

    private val userId = UserId("user-1")
    private val requestId = RequestId("request-1")
    private val image = PreparedReceiptImage(byteArrayOf(1, 2, 3), "image/jpeg")
    private val otherImage = PreparedReceiptImage(byteArrayOf(9, 9, 9), "image/jpeg")

    /**
     * Races [threadCount] real JVM threads against [action] on the same store, releasing them
     * together after all are ready -- genuine contention, never a sequential simulation.
     */
    private fun <T> raceConcurrently(threadCount: Int, action: suspend () -> T): List<T> {
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val results = Collections.synchronizedList(mutableListOf<T>())
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            pool.execute {
                ready.countDown()
                start.await()
                results.add(runBlocking { action() })
                done.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        return results.toList()
    }

    private fun assertSameDocument(expected: ValidatedReceiptAnalysisResultV1, actual: ValidatedReceiptAnalysisResultV1) {
        assertEquals(expected.serialize().toList(), actual.serialize().toList())
    }

    @Test
    fun `startOrGetExisting is idempotent for the same hash`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore()

        val first = store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        val second = store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))

        val firstAccepted = assertIs<StartOutcome.Accepted>(first)
        val secondAccepted = assertIs<StartOutcome.Accepted>(second)
        assertTrue(firstAccepted.isNew)
        assertTrue(!secondAccepted.isNew)
        assertEquals(firstAccepted.operation.operationId, secondAccepted.operation.operationId)
        assertEquals(firstAccepted.operation.attemptId, secondAccepted.operation.attemptId)
    }

    @Test
    fun `startOrGetExisting returns HashConflict for a divergent hash without mutating the existing record`(): Unit =
        runBlocking {
            val store = InMemoryReceiptAnalysisOperationStore()
            val created = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
            )

            val conflict = store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, otherImage))

            assertIs<StartOutcome.HashConflict>(conflict)
            val stillThere = assertIs<RequestLookup.Found>(store.findByRequestId(userId, requestId))
            assertEquals(created.operation, stillThere.operation)
        }

    @Test
    fun `concurrent startOrGetExisting for the same request creates exactly one reservation`() {
        val store = InMemoryReceiptAnalysisOperationStore()

        val results = raceConcurrently(8) {
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        }

        val accepted = results.map { assertIs<StartOutcome.Accepted>(it) }
        assertEquals(1, accepted.count { it.isNew })
        assertEquals(1, accepted.map { it.operation.operationId }.distinct().size)
    }

    @Test
    fun `N concurrent claims for the same operation -- exactly one Claimed`() {
        val store = InMemoryReceiptAnalysisOperationStore()
        val operationId = runBlocking {
            assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
            ).operation.operationId
        }

        val results = raceConcurrently(8) { store.claimInvocation(operationId) }

        assertEquals(1, results.count { it is ClaimOutcome.Claimed })
        assertEquals(7, results.count { it is ClaimOutcome.AlreadyClaimedOrResolved })
    }

    @Test
    fun `applyOutcome is idempotent and never produces a second ledger effect`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore()
        val operationId = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        ).operation.operationId
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))

        val first = store.applyOutcome(operationId, OutcomeApplication.Failed)
        val second = store.applyOutcome(operationId, OutcomeApplication.Failed)

        val applied = assertIs<ApplyOutcomeResult.Applied>(first)
        val alreadyResolved = assertIs<ApplyOutcomeResult.AlreadyResolved>(second)
        assertEquals(applied.operation, alreadyResolved.operation)
        val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(applied.operation.lifecycle)
        assertIs<ReceiptAnalysisOperationResult.Failed>(terminal.result)
        assertEquals(ReceiptAnalysisLedgerEffect.DEBITED, terminal.ledgerEffect)
    }

    @Test
    fun `FAILED_NO_PROVIDER preserves its reason, via applyOutcome, findByRequestId and idempotent retry`(): Unit =
        runBlocking {
            val store = InMemoryReceiptAnalysisOperationStore()
            val operationId = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
            ).operation.operationId
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))

            val application = OutcomeApplication.FailedNoProvider(ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED)
            val applied = assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(operationId, application))
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(applied.operation.lifecycle)
            val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
            assertEquals(ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED, result.reason)
            assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, terminal.ledgerEffect)

            val found = assertIs<RequestLookup.Found>(store.findByRequestId(userId, requestId))
            val foundTerminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(found.operation.lifecycle)
            val foundResult = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(foundTerminal.result)
            assertEquals(ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED, foundResult.reason)

            val retried = assertIs<ApplyOutcomeResult.AlreadyResolved>(store.applyOutcome(operationId, application))
            assertEquals(applied.operation, retried.operation)
        }

    @Test
    fun `FAILED_NO_PROVIDER preserves a ProviderRejectionReason, via applyOutcome, findByRequestId and idempotent retry`(): Unit =
        runBlocking {
            val store = InMemoryReceiptAnalysisOperationStore()
            val operationId = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
            ).operation.operationId
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))

            val application = OutcomeApplication.FailedNoProvider(ProviderRejectionReason.MALFORMED_REQUEST)
            val applied = assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(operationId, application))
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(applied.operation.lifecycle)
            val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
            assertEquals(ProviderRejectionReason.MALFORMED_REQUEST, result.reason)
            assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, terminal.ledgerEffect)

            val found = assertIs<RequestLookup.Found>(store.findByRequestId(userId, requestId))
            val foundTerminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(found.operation.lifecycle)
            val foundResult = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(foundTerminal.result)
            assertEquals(ProviderRejectionReason.MALFORMED_REQUEST, foundResult.reason)

            val retried = assertIs<ApplyOutcomeResult.AlreadyResolved>(store.applyOutcome(operationId, application))
            assertEquals(applied.operation, retried.operation)
        }

    @Test
    fun `Succeeded document is retrievable via findByRequestId and preserved through an idempotent retry`(): Unit =
        runBlocking {
            val store = InMemoryReceiptAnalysisOperationStore()
            val operationId = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
            ).operation.operationId
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))
            val document = sampleDocument()

            val applied = assertIs<ApplyOutcomeResult.Applied>(
                store.applyOutcome(operationId, OutcomeApplication.Succeeded(document))
            )
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(applied.operation.lifecycle)
            val result = assertIs<ReceiptAnalysisOperationResult.Succeeded>(terminal.result)
            assertSameDocument(document, result.document)
            assertEquals(ReceiptAnalysisLedgerEffect.DEBITED, terminal.ledgerEffect)

            val found = assertIs<RequestLookup.Found>(store.findByRequestId(userId, requestId))
            val foundTerminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(found.operation.lifecycle)
            val foundResult = assertIs<ReceiptAnalysisOperationResult.Succeeded>(foundTerminal.result)
            assertSameDocument(document, foundResult.document)

            val retried = assertIs<ApplyOutcomeResult.AlreadyResolved>(
                store.applyOutcome(operationId, OutcomeApplication.Succeeded(document))
            )
            val retriedResult = assertIs<ReceiptAnalysisOperationResult.Succeeded>(
                assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(retried.operation.lifecycle).result
            )
            assertSameDocument(document, retriedResult.document)
        }

    @Test
    fun `a pending claim converts to RECONCILING and is never claimable again, without a new provider call`(): Unit =
        runBlocking {
            val store = InMemoryReceiptAnalysisOperationStore()
            val operationId = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
            ).operation.operationId
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))

            val applied = assertIs<ApplyOutcomeResult.Applied>(
                store.applyOutcome(operationId, OutcomeApplication.ReconcilingDetected)
            )
            assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, applied.operation.lifecycle)

            assertIs<ClaimOutcome.AlreadyClaimedOrResolved>(store.claimInvocation(operationId))
        }

    @Test
    fun `ReconcilingWithProviderCorrelation converts to RECONCILING, persists the reference, never exposes it through the returned operation`(): Unit =
        runBlocking {
            val store = InMemoryReceiptAnalysisOperationStore()
            val operationId = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
            ).operation.operationId
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))
            val reference = ProviderCorrelationReference("test-correlation-reference")

            val applied = assertIs<ApplyOutcomeResult.Applied>(
                store.applyOutcome(operationId, OutcomeApplication.ReconcilingWithProviderCorrelation(reference))
            )

            assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, applied.operation.lifecycle)
            assertEquals(reference, store.correlationReferenceFor(operationId))
        }

    @Test
    fun `a repeated or late correlated reconciliation from RECONCILING is Rejected, never replacing the stored reference`(): Unit =
        runBlocking {
            val store = InMemoryReceiptAnalysisOperationStore()
            val operationId = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
            ).operation.operationId
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))
            val originalReference = ProviderCorrelationReference("original-reference")
            val reconciling = assertIs<ApplyOutcomeResult.Applied>(
                store.applyOutcome(operationId, OutcomeApplication.ReconcilingWithProviderCorrelation(originalReference))
            ).operation

            val lateReference = ProviderCorrelationReference("late-reference")
            val rejected = assertIs<ApplyOutcomeResult.Rejected>(
                store.applyOutcome(operationId, OutcomeApplication.ReconcilingWithProviderCorrelation(lateReference))
            )

            assertEquals(reconciling, rejected.operation)
            assertEquals(originalReference, store.correlationReferenceFor(operationId))
        }

    @Test
    fun `a terminal resolution from a correlated RECONCILING removes the reference atomically`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore()
        val operationId = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        ).operation.operationId
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))
        val reference = ProviderCorrelationReference("test-correlation-reference")
        assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(operationId, OutcomeApplication.ReconcilingWithProviderCorrelation(reference))
        )
        assertEquals(reference, store.correlationReferenceFor(operationId))

        val resolved = assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(
                operationId,
                OutcomeApplication.FailedNoProvider(ProviderTerminalFailureReason.RETRY_EXHAUSTED)
            )
        )

        assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
        assertEquals(null, store.correlationReferenceFor(operationId))
    }

    @Test
    fun `FailedNoProvider is applied directly from RECEIVED, without ever claiming`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore()
        val operationId = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        ).operation.operationId

        val application = OutcomeApplication.FailedNoProvider(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE)
        val applied = assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(operationId, application))

        val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(applied.operation.lifecycle)
        val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
        assertEquals(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE, result.reason)
        assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, terminal.ledgerEffect)
    }

    @Test
    fun `every application other than FailedNoProvider is rejected from RECEIVED, never mutating state or ledger`(): Unit =
        runBlocking {
            val store = InMemoryReceiptAnalysisOperationStore()
            val forbidden = listOf(
                OutcomeApplication.Succeeded(sampleDocument()),
                OutcomeApplication.Failed,
                OutcomeApplication.ReconcilingDetected,
                OutcomeApplication.AttemptIdMismatch(
                    ReceiptAnalysisAttemptId("attempt-expected"),
                    ReceiptAnalysisAttemptId("attempt-received")
                )
            )

            forbidden.forEachIndexed { index, application ->
                val freshRequestId = RequestId("request-$index")
                val operation = assertIs<StartOutcome.Accepted>(
                    store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, freshRequestId, image))
                ).operation

                val rejected = assertIs<ApplyOutcomeResult.Rejected>(store.applyOutcome(operation.operationId, application))

                assertEquals(operation, rejected.operation)
                assertEquals(ReceiptAnalysisOperationLifecycle.Received, rejected.operation.lifecycle)
            }
        }

    @Test
    fun `RECONCILING resolves later to SUCCEEDED with a debit, preserving the document, without a new claim`(): Unit =
        runBlocking {
            val store = InMemoryReceiptAnalysisOperationStore()
            val operationId = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
            ).operation.operationId
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))
            assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(operationId, OutcomeApplication.ReconcilingDetected))
            val document = sampleDocument()

            val resolved = assertIs<ApplyOutcomeResult.Applied>(
                store.applyOutcome(operationId, OutcomeApplication.Succeeded(document))
            )
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
            val result = assertIs<ReceiptAnalysisOperationResult.Succeeded>(terminal.result)
            assertSameDocument(document, result.document)
            assertEquals(ReceiptAnalysisLedgerEffect.DEBITED, terminal.ledgerEffect)

            val found = assertIs<RequestLookup.Found>(store.findByRequestId(userId, requestId))
            assertEquals(resolved.operation, found.operation)

            assertIs<ClaimOutcome.AlreadyClaimedOrResolved>(store.claimInvocation(operationId))
            val secondAttempt = assertIs<ApplyOutcomeResult.AlreadyResolved>(
                store.applyOutcome(operationId, OutcomeApplication.Failed)
            )
            assertEquals(resolved.operation, secondAttempt.operation)
        }

    @Test
    fun `RECONCILING resolves later to FAILED_NO_PROVIDER with a release, preserving the reason, without a new claim`(): Unit =
        runBlocking {
            val store = InMemoryReceiptAnalysisOperationStore()
            val operationId = assertIs<StartOutcome.Accepted>(
                store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
            ).operation.operationId
            assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))
            assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(operationId, OutcomeApplication.ReconcilingDetected))

            val application = OutcomeApplication.FailedNoProvider(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE)
            val resolved = assertIs<ApplyOutcomeResult.Applied>(store.applyOutcome(operationId, application))
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
            val result = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
            assertEquals(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE, result.reason)
            assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, terminal.ledgerEffect)

            val found = assertIs<RequestLookup.Found>(store.findByRequestId(userId, requestId))
            assertEquals(resolved.operation, found.operation)

            assertIs<ClaimOutcome.AlreadyClaimedOrResolved>(store.claimInvocation(operationId))
            val secondAttempt = assertIs<ApplyOutcomeResult.AlreadyResolved>(
                store.applyOutcome(operationId, OutcomeApplication.Succeeded(sampleDocument()))
            )
            assertEquals(resolved.operation, secondAttempt.operation)
        }

    @Test
    fun `RECONCILING rejects ReconcilingDetected and AttemptIdMismatch reapplied to it`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore()
        val operationId = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        ).operation.operationId
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))
        val reconciling = assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(operationId, OutcomeApplication.ReconcilingDetected)
        ).operation

        val rejectedAgain = assertIs<ApplyOutcomeResult.Rejected>(
            store.applyOutcome(operationId, OutcomeApplication.ReconcilingDetected)
        )
        val rejectedMismatch = assertIs<ApplyOutcomeResult.Rejected>(
            store.applyOutcome(
                operationId,
                OutcomeApplication.AttemptIdMismatch(
                    ReceiptAnalysisAttemptId("attempt-expected"),
                    ReceiptAnalysisAttemptId("attempt-received")
                )
            )
        )

        assertEquals(reconciling, rejectedAgain.operation)
        assertEquals(reconciling, rejectedMismatch.operation)
    }

    @Test
    fun `a second, different application never replaces the persisted result or ledger effect`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore()
        val operationId = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        ).operation.operationId
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))
        val document = sampleDocument()
        val original = assertIs<ApplyOutcomeResult.Applied>(
            store.applyOutcome(operationId, OutcomeApplication.Succeeded(document))
        ).operation

        val afterFailed = assertIs<ApplyOutcomeResult.AlreadyResolved>(store.applyOutcome(operationId, OutcomeApplication.Failed))
        val afterFailedNoProvider = assertIs<ApplyOutcomeResult.AlreadyResolved>(
            store.applyOutcome(
                operationId,
                OutcomeApplication.FailedNoProvider(ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED)
            )
        )

        assertEquals(original, afterFailed.operation)
        assertEquals(original, afterFailedNoProvider.operation)
        val result = assertIs<ReceiptAnalysisOperationResult.Succeeded>(
            assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(afterFailedNoProvider.operation.lifecycle).result
        )
        assertSameDocument(document, result.document)
    }

    private fun sampleDocument(): ValidatedReceiptAnalysisResultV1 {
        val projectRoot = File(System.getProperty("user.dir"))
        val fixture = File(projectRoot, "contracts/fixtures/valid/complete-with-items.json")
        return requireNotNull(ValidatedReceiptAnalysisResultV1.from(fixture.readBytes()))
    }
}
