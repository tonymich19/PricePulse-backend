package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.DeterministicReceiptAnalysisProviderFake
import java.time.Instant
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderRejectionReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttempt
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderInvocationFailureObserver
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderPort
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StartReceiptAnalysisUseCaseTest {

    private val userId = UserId("user-1")
    private val requestId = RequestId("request-1")

    private fun sampleImage(bytes: ByteArray = byteArrayOf(1, 2, 3)) = PreparedReceiptImage(bytes, "image/jpeg")

    private fun sampleDocument(): ValidatedReceiptAnalysisResultV1 {
        val projectRoot = File(System.getProperty("user.dir"))
        val fixture = File(projectRoot, "contracts/fixtures/valid/complete-with-items.json")
        return requireNotNull(ValidatedReceiptAnalysisResultV1.from(fixture.readBytes()))
    }

    @Test
    fun `Completed outcome resolves to SUCCEEDED, provider called exactly once`(): Unit = runBlocking {
        val document = sampleDocument()
        val fake = DeterministicReceiptAnalysisProviderFake.completed(document)
        val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), fake)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

        val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
        val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
        val succeeded = assertIs<ReceiptAnalysisOperationResult.Succeeded>(terminal.result)
        assertEquals(document.serialize().toList(), succeeded.document.serialize().toList())
        assertEquals(ReceiptAnalysisLedgerEffect.DEBITED, terminal.ledgerEffect)
        assertEquals(1, fake.invocationCount)
    }

    @Test
    fun `NotInvoked outcome resolves to FAILED_NO_PROVIDER, provider called exactly once`(): Unit = runBlocking {
        val fake = DeterministicReceiptAnalysisProviderFake.notInvoked(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE)
        val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), fake)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

        val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
        val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
        val failedNoProvider = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
        assertEquals(ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE, failedNoProvider.reason)
        assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, terminal.ledgerEffect)
        assertEquals(1, fake.invocationCount)
    }

    @Test
    fun `InvocationRejected outcome resolves to FAILED_NO_PROVIDER, reason preserved, RELEASED, provider called exactly once`(): Unit =
        runBlocking {
            val fake = DeterministicReceiptAnalysisProviderFake.invocationRejected(ProviderRejectionReason.ACCESS_FORBIDDEN)
            val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), fake)

            val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

            val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
            val failedNoProvider = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
            assertEquals(ProviderRejectionReason.ACCESS_FORBIDDEN, failedNoProvider.reason)
            assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, terminal.ledgerEffect)
            assertEquals(1, fake.invocationCount)
        }

    @Test
    fun `InvocationConfirmedTerminalFailure outcome resolves to FAILED_NO_PROVIDER, reason preserved, RELEASED, provider called exactly once`(): Unit =
        runBlocking {
            val fake = DeterministicReceiptAnalysisProviderFake.invocationConfirmedTerminalFailure(
                ProviderTerminalFailureReason.RETRY_EXHAUSTED
            )
            val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), fake)

            val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

            val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
            val failedNoProvider = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
            assertEquals(ProviderTerminalFailureReason.RETRY_EXHAUSTED, failedNoProvider.reason)
            assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, terminal.ledgerEffect)
            assertEquals(1, fake.invocationCount)
        }

    @Test
    fun `InvocationConfirmedTerminalFailure with RATE_LIMITED resolves to FAILED_NO_PROVIDER, reason preserved, RELEASED, provider called exactly once`(): Unit =
        runBlocking {
            val fake = DeterministicReceiptAnalysisProviderFake.invocationConfirmedTerminalFailure(
                ProviderTerminalFailureReason.RATE_LIMITED
            )
            val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), fake)

            val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

            val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
            val failedNoProvider = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
            assertEquals(ProviderTerminalFailureReason.RATE_LIMITED, failedNoProvider.reason)
            assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, terminal.ledgerEffect)
            assertEquals(1, fake.invocationCount)
        }

    @Test
    fun `InvocationConfirmedTerminalFailure with TRANSIENT_PROVIDER_FAILURE resolves to FAILED_NO_PROVIDER, reason preserved, RELEASED, provider called exactly once`(): Unit =
        runBlocking {
            val fake = DeterministicReceiptAnalysisProviderFake.invocationConfirmedTerminalFailure(
                ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE
            )
            val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), fake)

            val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

            val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
            val failedNoProvider = assertIs<ReceiptAnalysisOperationResult.FailedNoProvider>(terminal.result)
            assertEquals(ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE, failedNoProvider.reason)
            assertEquals(ReceiptAnalysisLedgerEffect.RELEASED, terminal.ledgerEffect)
            assertEquals(1, fake.invocationCount)
        }

    @Test
    fun `InvocationConfirmedPendingResponse with the matching attemptId resolves to RECONCILING, never releasing or debiting, provider called exactly once, correlation reference persisted but never exposed`(): Unit =
        runBlocking {
            val reference = ProviderCorrelationReference("test-correlation-reference")
            val fake = DeterministicReceiptAnalysisProviderFake.invocationConfirmedPendingResponse(reference)
            val store = InMemoryReceiptAnalysisOperationStore()
            val useCase = useCase(store, fake)

            val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

            val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
            assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, resolved.operation.lifecycle)
            assertEquals(1, fake.invocationCount)
            assertEquals(reference, store.correlationReferenceFor(resolved.operation.operationId))
        }

    @Test
    fun `InvocationConfirmedInvalidResponse with the matching attemptId resolves to FAILED, provider called exactly once`(): Unit =
        runBlocking {
            val fake = DeterministicReceiptAnalysisProviderFake.invocationConfirmedInvalidResponse()
            val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), fake)

            val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

            val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
            val terminal = assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
            assertIs<ReceiptAnalysisOperationResult.Failed>(terminal.result)
            assertEquals(ReceiptAnalysisLedgerEffect.DEBITED, terminal.ledgerEffect)
            assertEquals(1, fake.invocationCount)
        }

    @Test
    fun `InvocationUncertain with the matching attemptId resolves to RECONCILING, provider called exactly once`(): Unit =
        runBlocking {
            val fake = DeterministicReceiptAnalysisProviderFake.invocationUncertain()
            val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), fake)

            val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

            val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
            assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, resolved.operation.lifecycle)
            assertEquals(1, fake.invocationCount)
        }

    @Test
    fun `a divergent attemptId from the port never resolves to SUCCEEDED or FAILED, always RECONCILING`(): Unit = runBlocking {
        val wrongIdPort = AttemptIdEchoingWrongIdPort(ReceiptAnalysisAttemptId("attempt-not-ours"))
        val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), wrongIdPort)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

        val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
        assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, resolved.operation.lifecycle)
        assertEquals(1, wrongIdPort.invocationCount)
    }

    @Test
    fun `a hash conflict is discriminated and never claims or calls the provider`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore()
        store.startOrGetExisting(
            StartReceiptAnalysisOperationCommand(userId, requestId, sampleImage(byteArrayOf(9, 9, 9)))
        )
        val fake = DeterministicReceiptAnalysisProviderFake.completed(sampleDocument())
        val useCase = useCase(store, fake)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage(byteArrayOf(1, 2, 3))))

        assertIs<StartReceiptAnalysisResult.HashConflict>(result)
        assertEquals(0, fake.invocationCount)
    }

    @Test
    fun `concurrent invocations for the same request call the provider exactly once`() {
        val store = InMemoryReceiptAnalysisOperationStore()
        val fake = DeterministicReceiptAnalysisProviderFake.completed(sampleDocument())
        val useCase = useCase(store, fake)
        val threadCount = 8
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val results = Collections.synchronizedList(mutableListOf<StartReceiptAnalysisResult>())
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            pool.execute {
                ready.countDown()
                start.await()
                results.add(runBlocking { useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage())) })
                done.countDown()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        results.forEach { assertIs<StartReceiptAnalysisResult.Resolved>(it) }
        assertEquals(1, fake.invocationCount)
    }

    @Test
    fun `a Received registration left over from a crash is still claimed and resolved normally`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore()
        val image = sampleImage()
        // Simulates a crash: the reservation was created by a previous, interrupted attempt, but
        // claimInvocation was never even tried.
        store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        val fake = DeterministicReceiptAnalysisProviderFake.completed(sampleDocument())
        val useCase = useCase(store, fake)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, image))

        val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
        assertIs<ReceiptAnalysisOperationLifecycle.Terminal>(resolved.operation.lifecycle)
        assertEquals(1, fake.invocationCount)
    }

    @Test
    fun `AlreadyClaimedOrResolved never calls the provider and reloads the current state`(): Unit = runBlocking {
        val store = InMemoryReceiptAnalysisOperationStore()
        val image = sampleImage()
        val operationId = assertIs<StartOutcome.Accepted>(
            store.startOrGetExisting(StartReceiptAnalysisOperationCommand(userId, requestId, image))
        ).operation.operationId
        // Simulates another caller already holding the exclusive claim.
        assertIs<ClaimOutcome.Claimed>(store.claimInvocation(operationId))
        val fake = DeterministicReceiptAnalysisProviderFake.completed(sampleDocument())
        val useCase = useCase(store, fake)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, image))

        val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
        assertEquals(ReceiptAnalysisOperationLifecycle.InvocationClaimed, resolved.operation.lifecycle)
        assertEquals(0, fake.invocationCount)
    }

    @Test
    fun `an unexpected non-cancellation exception from the port resolves to RECONCILING, never retried`(): Unit = runBlocking {
        val throwingPort = ThrowingPort(IllegalStateException("boom"))
        val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), throwingPort)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

        val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
        assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, resolved.operation.lifecycle)
        assertEquals(1, throwingPort.invocationCount)
    }

    @Test
    fun `a CancellationException from the port is rethrown, never converted to a result`(): Unit = runBlocking {
        val throwingPort = ThrowingPort(CancellationException("cancelled"))
        val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), throwingPort)

        assertFailsWith<CancellationException> {
            useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))
        }
    }

    @Test
    fun `StartOutcome InsufficientCredits maps directly, without claim, provider, or apply`(): Unit = runBlocking {
        val store = StubOperationStore(startOutcome = StartOutcome.InsufficientCredits)
        val fake = DeterministicReceiptAnalysisProviderFake.completed(sampleDocument())
        val useCase = useCase(store, fake)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

        assertIs<StartReceiptAnalysisResult.InsufficientCredits>(result)
        assertEquals(0, store.claimInvocationCount)
        assertEquals(0, store.findByRequestIdCount)
        assertEquals(0, store.applyOutcomeCount)
        assertEquals(0, fake.invocationCount)
    }

    @Test
    fun `StartOutcome Tombstoned maps directly, without claim, provider, or apply`(): Unit = runBlocking {
        val store = StubOperationStore(startOutcome = StartOutcome.Tombstoned)
        val fake = DeterministicReceiptAnalysisProviderFake.completed(sampleDocument())
        val useCase = useCase(store, fake)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

        assertIs<StartReceiptAnalysisResult.Tombstoned>(result)
        assertEquals(0, store.claimInvocationCount)
        assertEquals(0, store.findByRequestIdCount)
        assertEquals(0, store.applyOutcomeCount)
        assertEquals(0, fake.invocationCount)
    }

    @Test
    fun `RequestLookup Tombstoned maps via resolveByReloading, without provider or apply`(): Unit = runBlocking {
        val existingOperation = ReceiptAnalysisOperation(
            operationId = ReceiptAnalysisOperationId("operation-stub"),
            userId = userId,
            requestId = requestId,
            contentHash = ContentHash.sha256Of(sampleImage().bytes()),
            sizeBytes = 3,
            mimeType = "image/jpeg",
            attemptId = ReceiptAnalysisAttemptId("attempt-stub"),
            lifecycle = ReceiptAnalysisOperationLifecycle.InvocationClaimed
        )
        val store = StubOperationStore(
            startOutcome = StartOutcome.Accepted(existingOperation, isNew = false),
            claimOutcome = ClaimOutcome.AlreadyClaimedOrResolved,
            lookup = RequestLookup.Tombstoned
        )
        val fake = DeterministicReceiptAnalysisProviderFake.completed(sampleDocument())
        val useCase = useCase(store, fake)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

        assertIs<StartReceiptAnalysisResult.Tombstoned>(result)
        assertEquals(1, store.claimInvocationCount)
        assertEquals(1, store.findByRequestIdCount)
        assertEquals(0, store.applyOutcomeCount)
        assertEquals(0, fake.invocationCount)
    }

    @Test
    fun `the command's image bytes and MIME reach the store via the new command, never separate fields`(): Unit =
        runBlocking {
            val image = sampleImage(byteArrayOf(4, 5, 6, 7))
            val store = RecordingOperationStore()
            val fake = DeterministicReceiptAnalysisProviderFake.completed(sampleDocument())
            val useCase = useCase(store, fake)

            useCase(StartReceiptAnalysisCommand(userId, requestId, image))

            val received = checkNotNull(store.receivedCommand) { "startOrGetExisting was never called" }
            assertEquals(userId, received.userId)
            assertEquals(requestId, received.requestId)
            assertEquals(image.bytes().toList(), received.image.bytes().toList())
            assertEquals(image.mimeType, received.image.mimeType)
        }

    /**
     * Test-only: returns fixed, pre-configured outcomes -- proves StartReceiptAnalysisUseCase's
     * mapping in isolation, without depending on InMemoryReceiptAnalysisOperationStore ever being
     * able to produce InsufficientCredits/Tombstoned (it can't; those are store-implementation
     * concerns out of scope for this slice, receiptanalysis-slice-report.md 6.10.22).
     */
    private class StubOperationStore(
        private val startOutcome: StartOutcome,
        private val claimOutcome: ClaimOutcome? = null,
        private val lookup: RequestLookup? = null
    ) : ReceiptAnalysisOperationStore {
        /** Reconciliation is out of this slice; no test here drives a resolver. */
        override suspend fun findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate> =
            throw UnsupportedOperationException("out of this slice")

        var claimInvocationCount = 0
            private set
        var findByRequestIdCount = 0
            private set
        var applyOutcomeCount = 0
            private set

        override suspend fun startOrGetExisting(command: StartReceiptAnalysisOperationCommand): StartOutcome = startOutcome

        override suspend fun findByRequestId(userId: UserId, requestId: RequestId): RequestLookup {
            findByRequestIdCount++
            return checkNotNull(lookup) { "findByRequestId not expected to be called in this test" }
        }

        override suspend fun claimInvocation(operationId: ReceiptAnalysisOperationId): ClaimOutcome {
            claimInvocationCount++
            return checkNotNull(claimOutcome) { "claimInvocation not expected to be called in this test" }
        }

        override suspend fun applyOutcome(
            operationId: ReceiptAnalysisOperationId,
            application: OutcomeApplication
        ): ApplyOutcomeResult {
            applyOutcomeCount++
            error("applyOutcome must never be called in this test")
        }
    }

    /**
     * Test-only: records the exact [StartReceiptAnalysisOperationCommand] it receives, then
     * delegates to a real [InMemoryReceiptAnalysisOperationStore] so the rest of the use case's
     * flow (claim, provider call, apply) still runs normally.
     */
    private class RecordingOperationStore : ReceiptAnalysisOperationStore {
        private val delegate = InMemoryReceiptAnalysisOperationStore()

        var receivedCommand: StartReceiptAnalysisOperationCommand? = null
            private set

        /** Delegated like every other read: this double only intercepts the start command. */
        override suspend fun findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate> =
            delegate.findReconcilable(limit, notUpdatedSince)

        override suspend fun startOrGetExisting(command: StartReceiptAnalysisOperationCommand): StartOutcome {
            receivedCommand = command
            return delegate.startOrGetExisting(command)
        }

        override suspend fun findByRequestId(userId: UserId, requestId: RequestId): RequestLookup =
            delegate.findByRequestId(userId, requestId)

        override suspend fun claimInvocation(operationId: ReceiptAnalysisOperationId): ClaimOutcome =
            delegate.claimInvocation(operationId)

        override suspend fun applyOutcome(
            operationId: ReceiptAnalysisOperationId,
            application: OutcomeApplication
        ): ApplyOutcomeResult = delegate.applyOutcome(operationId, application)
    }

    /** Test-only: violates DeterministicReceiptAnalysisProviderFake's echo contract on purpose. */
    private class AttemptIdEchoingWrongIdPort(private val wrongAttemptId: ReceiptAnalysisAttemptId) : ReceiptAnalysisProviderPort {
        var invocationCount = 0
            private set

        override suspend fun invoke(attempt: ReceiptAnalysisAttempt): ReceiptAnalysisProviderOutcome {
            invocationCount++
            return ReceiptAnalysisProviderOutcome.InvocationUncertain(wrongAttemptId)
        }
    }

    private class ThrowingPort(private val throwable: Throwable) : ReceiptAnalysisProviderPort {
        var invocationCount = 0
            private set

        override suspend fun invoke(attempt: ReceiptAnalysisAttempt): ReceiptAnalysisProviderOutcome {
            invocationCount++
            throw throwable
        }
    }
    // ---------------------------------------------------------------------------------------
    // Failure observation
    // ---------------------------------------------------------------------------------------

    /** Every construction in this file goes through here, so the observer argument lives once. */
    private fun useCase(
        store: ReceiptAnalysisOperationStore,
        port: ReceiptAnalysisProviderPort,
        observer: ProviderInvocationFailureObserver = RecordingFailureObserver()
    ) = StartReceiptAnalysisUseCase(store, port, observer)

    private class RecordingFailureObserver : ProviderInvocationFailureObserver {
        val observed = mutableListOf<Pair<ReceiptAnalysisOperationId, Throwable>>()

        override fun invocationFailedUnexpectedly(
            operationId: ReceiptAnalysisOperationId,
            failure: Throwable
        ) {
            observed += operationId to failure
        }
    }

    @Test
    fun `an unexpected exception from the port is reported to the failure observer`(): Unit = runBlocking {
        val boom = IllegalStateException("boom")
        val observer = RecordingFailureObserver()
        val store = InMemoryReceiptAnalysisOperationStore()
        val useCase = useCase(store, ThrowingPort(boom), observer)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

        val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
        assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, resolved.operation.lifecycle)
        assertEquals(1, observer.observed.size, "the swallowed exception must not vanish")
        assertEquals(resolved.operation.operationId, observer.observed.single().first)
        assertEquals(boom, observer.observed.single().second)
    }

    @Test
    fun `a cancellation is never reported as a failure`(): Unit = runBlocking {
        val observer = RecordingFailureObserver()
        val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), ThrowingPort(CancellationException("cancelled")), observer)

        assertFailsWith<CancellationException> {
            useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))
        }
        assertEquals(0, observer.observed.size, "cancellation is control flow, never a provider failure")
    }

    @Test
    fun `a failing observer never changes the outcome`(): Unit = runBlocking {
        val exploding = ProviderInvocationFailureObserver { _, _ -> throw IllegalStateException("sink down") }
        val useCase = useCase(InMemoryReceiptAnalysisOperationStore(), ThrowingPort(IllegalStateException("boom")), exploding)

        val result = useCase(StartReceiptAnalysisCommand(userId, requestId, sampleImage()))

        val resolved = assertIs<StartReceiptAnalysisResult.Resolved>(result)
        assertEquals(ReceiptAnalysisOperationLifecycle.Reconciling, resolved.operation.lifecycle)
    }

}
