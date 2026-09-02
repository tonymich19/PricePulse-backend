package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCorrelationReference
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Test-only in-memory [ReceiptAnalysisOperationStore]. Each operation (startOrGetExisting,
 * claimInvocation, applyOutcome) runs its whole decide-and-write step inside a real JVM lock,
 * never sequencing calls by convention -- mirroring the single-transaction guarantee required of
 * the real (future) persistence adapter. See receiptanalysis-slice-report.md 6.10.16.B/6.10.19.
 */
class InMemoryReceiptAnalysisOperationStore(
    private val clock: Clock = Clock.systemUTC()
) : ReceiptAnalysisOperationStore {

    private val lock = Any()
    private val byRequestKey = HashMap<RequestKey, ReceiptAnalysisOperationId>()
    private val byOperationId = HashMap<ReceiptAnalysisOperationId, ReceiptAnalysisOperation>()
    private val operationIdCounter = AtomicLong(0)
    private val attemptIdCounter = AtomicLong(0)

    /** Mirrors the future `receipt_analysis_provider_correlation` table (6.10.39): a durable
     * reference kept only while an operation is RECONCILING, never exposed through
     * [ReceiptAnalysisOperation]/[ReceiptAnalysisOperationLifecycle] -- a caller can never read it
     * back through this store's public return values, only observe its presence/absence via
     * [correlationReferenceFor], provided for tests only. */
    private val correlationByOperationId = HashMap<ReceiptAnalysisOperationId, ProviderCorrelationReference>()

    /** Mirrors the real store's `updated_at` for the moment an operation entered RECONCILING --
     * the single input [findReconcilable]'s cutoff and ordering depend on. Removed together with
     * the correlation reference on any terminal resolution, so a settled operation leaves nothing
     * behind here either. */
    private val reconcilingSinceByOperationId = HashMap<ReceiptAnalysisOperationId, Instant>()

    override suspend fun startOrGetExisting(command: StartReceiptAnalysisOperationCommand): StartOutcome =
        synchronized(lock) {
            val key = RequestKey(command.userId, command.requestId)
            val existingId = byRequestKey[key]
            // Derived from the image itself -- never a caller-supplied hash/size/MIME that could
            // diverge from the actual bytes. Never persists command.image's bytes: this fake still
            // only tracks metadata, exactly as before.
            val contentHash = ContentHash.sha256Of(command.image.bytes())
            if (existingId == null) {
                val operation = ReceiptAnalysisOperation(
                    operationId = ReceiptAnalysisOperationId("operation-${operationIdCounter.incrementAndGet()}"),
                    userId = command.userId,
                    requestId = command.requestId,
                    contentHash = contentHash,
                    sizeBytes = command.image.sizeBytes,
                    mimeType = command.image.mimeType,
                    attemptId = ReceiptAnalysisAttemptId("attempt-${attemptIdCounter.incrementAndGet()}"),
                    lifecycle = ReceiptAnalysisOperationLifecycle.Received
                )
                byRequestKey[key] = operation.operationId
                byOperationId[operation.operationId] = operation
                StartOutcome.Accepted(operation, isNew = true)
            } else {
                val existing = byOperationId.getValue(existingId)
                if (existing.contentHash == contentHash) {
                    StartOutcome.Accepted(existing, isNew = false)
                } else {
                    StartOutcome.HashConflict
                }
            }
        }

    override suspend fun findByRequestId(userId: UserId, requestId: RequestId): RequestLookup =
        synchronized(lock) {
            val id = byRequestKey[RequestKey(userId, requestId)]
            if (id == null) RequestLookup.NotFound else RequestLookup.Found(byOperationId.getValue(id))
        }

    override suspend fun claimInvocation(operationId: ReceiptAnalysisOperationId): ClaimOutcome =
        synchronized(lock) {
            val operation = byOperationId.getValue(operationId)
            if (operation.lifecycle != ReceiptAnalysisOperationLifecycle.Received) {
                ClaimOutcome.AlreadyClaimedOrResolved
            } else {
                val claimed = operation.copy(lifecycle = ReceiptAnalysisOperationLifecycle.InvocationClaimed)
                byOperationId[operationId] = claimed
                ClaimOutcome.Claimed(claimed)
            }
        }

    override suspend fun applyOutcome(
        operationId: ReceiptAnalysisOperationId,
        application: OutcomeApplication
    ): ApplyOutcomeResult = synchronized(lock) {
        val operation = byOperationId.getValue(operationId)

        when (operation.lifecycle) {
            is ReceiptAnalysisOperationLifecycle.Terminal ->
                ApplyOutcomeResult.AlreadyResolved(operation)

            ReceiptAnalysisOperationLifecycle.Received ->
                // Only a rejection before ANY provider call is valid here -- every other
                // application would let a caller record a financial effect without ever winning
                // claimInvocation (receiptanalysis-slice-report.md 6.10.16.B/6.10.17 correction).
                if (application is OutcomeApplication.FailedNoProvider) {
                    terminal(operation, ReceiptAnalysisOperationResult.FailedNoProvider(application.reason))
                } else {
                    ApplyOutcomeResult.Rejected(operation)
                }

            ReceiptAnalysisOperationLifecycle.InvocationClaimed ->
                when (application) {
                    is OutcomeApplication.Succeeded ->
                        terminal(operation, ReceiptAnalysisOperationResult.Succeeded(application.document))
                    is OutcomeApplication.Failed ->
                        terminal(operation, ReceiptAnalysisOperationResult.Failed)
                    is OutcomeApplication.FailedNoProvider ->
                        terminal(operation, ReceiptAnalysisOperationResult.FailedNoProvider(application.reason))
                    is OutcomeApplication.ReconcilingDetected ->
                        reconciling(operation)
                    is OutcomeApplication.ReconcilingWithProviderCorrelation ->
                        reconcilingWithCorrelation(operation, application.reference)
                    is OutcomeApplication.AttemptIdMismatch ->
                        reconciling(operation)
                }

            ReceiptAnalysisOperationLifecycle.Reconciling ->
                // A later terminal resolution, without a new claim or a new provider call
                // (receiptanalysis-slice-report.md 6.10.17 correction). ReconcilingDetected/
                // ReconcilingWithProviderCorrelation/AttemptIdMismatch never re-apply here --
                // RECONCILING is already recorded; a repeated/late correlated reconciliation can
                // never add or replace the correlation reference (6.10.39).
                when (application) {
                    is OutcomeApplication.Succeeded ->
                        terminal(operation, ReceiptAnalysisOperationResult.Succeeded(application.document))
                    is OutcomeApplication.Failed ->
                        terminal(operation, ReceiptAnalysisOperationResult.Failed)
                    is OutcomeApplication.FailedNoProvider ->
                        terminal(operation, ReceiptAnalysisOperationResult.FailedNoProvider(application.reason))
                    is OutcomeApplication.ReconcilingDetected,
                    is OutcomeApplication.ReconcilingWithProviderCorrelation,
                    is OutcomeApplication.AttemptIdMismatch ->
                        ApplyOutcomeResult.Rejected(operation)
                }
        }
    }

    /** Provided for tests only -- the real [ReceiptAnalysisOperationStore] API never exposes this
     * through any returned [ReceiptAnalysisOperation]. */
    fun correlationReferenceFor(operationId: ReceiptAnalysisOperationId): ProviderCorrelationReference? =
        synchronized(lock) { correlationByOperationId[operationId] }

    private fun terminal(
        operation: ReceiptAnalysisOperation,
        result: ReceiptAnalysisOperationResult
    ): ApplyOutcomeResult.Applied {
        val updated = operation.copy(lifecycle = ReceiptAnalysisOperationLifecycle.Terminal(result))
        byOperationId[operation.operationId] = updated
        // Removed atomically (same lock) on every terminal resolution -- the reference may exist
        // only while the operation remains RECONCILING (6.10.39).
        correlationByOperationId.remove(operation.operationId)
        reconcilingSinceByOperationId.remove(operation.operationId)
        return ApplyOutcomeResult.Applied(updated)
    }

    private fun reconciling(operation: ReceiptAnalysisOperation): ApplyOutcomeResult.Applied {
        val updated = operation.copy(lifecycle = ReceiptAnalysisOperationLifecycle.Reconciling)
        byOperationId[operation.operationId] = updated
        reconcilingSinceByOperationId[operation.operationId] = clock.instant()
        return ApplyOutcomeResult.Applied(updated)
    }

    private fun reconcilingWithCorrelation(
        operation: ReceiptAnalysisOperation,
        reference: ProviderCorrelationReference
    ): ApplyOutcomeResult.Applied {
        val updated = operation.copy(lifecycle = ReceiptAnalysisOperationLifecycle.Reconciling)
        byOperationId[operation.operationId] = updated
        correlationByOperationId[operation.operationId] = reference
        reconcilingSinceByOperationId[operation.operationId] = clock.instant()
        return ApplyOutcomeResult.Applied(updated)
    }

    /** Same contract as the real adapter: read-only, oldest first, inclusive cutoff, capped by
     * [limit] -- no lock or lease, because [applyOutcome] is the single authority. */
    override suspend fun findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate> =
        synchronized(lock) {
            require(limit > 0) { "limit must be positive, was $limit" }
            byOperationId.values
                .filter { it.lifecycle == ReceiptAnalysisOperationLifecycle.Reconciling }
                .mapNotNull { operation ->
                    val since = reconcilingSinceByOperationId[operation.operationId] ?: return@mapNotNull null
                    if (since.isAfter(notUpdatedSince)) null else operation to since
                }
                .sortedBy { (_, since) -> since }
                .take(limit)
                .map { (operation, since) ->
                    ReconciliationCandidate(
                        operationId = operation.operationId,
                        attemptId = operation.attemptId,
                        correlationReference = correlationByOperationId[operation.operationId],
                        reconcilingSince = since
                    )
                }
        }

    private data class RequestKey(val userId: UserId, val requestId: RequestId)
}
