package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderInvocationFailureObserver
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttempt
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderPort
import kotlinx.coroutines.CancellationException

/**
 * Never a raw HTTP token, header, DTO, or client-supplied hash -- only what a verified caller has
 * already established. [StartReceiptAnalysisUseCase] always recomputes the content hash from
 * [image]'s actual bytes.
 */
data class StartReceiptAnalysisCommand(
    val userId: UserId,
    val requestId: RequestId,
    val image: PreparedReceiptImage
)

sealed interface StartReceiptAnalysisResult {
    data class Resolved(val operation: ReceiptAnalysisOperation) : StartReceiptAnalysisResult
    data object HashConflict : StartReceiptAnalysisResult

    /**
     * [ReceiptAnalysisOperationStore.findByRequestId] returned NotFound right after
     * [ReceiptAnalysisOperationStore.startOrGetExisting] returned Accepted for the same
     * (userId, requestId) in this same call -- a postcondition violation of the store, not an
     * ordinary outcome. Represented explicitly rather than fabricating an operation or throwing.
     */
    data object OperationUnexpectedlyMissing : StartReceiptAnalysisResult

    /** Mirrors [StartOutcome.InsufficientCredits] -- no claim, provider call, or apply ever attempted. */
    data object InsufficientCredits : StartReceiptAnalysisResult

    /** Mirrors [StartOutcome.Tombstoned]/[RequestLookup.Tombstoned] -- no claim, provider call, or apply ever attempted. */
    data object Tombstoned : StartReceiptAnalysisResult
}

/**
 * Connects the contracts already accepted in `application/receiptanalysis`(`.orchestration`):
 * reserve/reuse the operation -> claim the exclusive right to invoke the provider -> call it AT
 * MOST once -> map its outcome -> apply it once via the store, never a parallel state or ledger.
 * See receiptanalysis-slice-report.md 6.10.21.
 */
class StartReceiptAnalysisUseCase(
    private val store: ReceiptAnalysisOperationStore,
    private val port: ReceiptAnalysisProviderPort,
    private val failureObserver: ProviderInvocationFailureObserver
) {
    suspend operator fun invoke(command: StartReceiptAnalysisCommand): StartReceiptAnalysisResult {
        val accepted = when (
            val startOutcome = store.startOrGetExisting(
                StartReceiptAnalysisOperationCommand(
                    userId = command.userId,
                    requestId = command.requestId,
                    image = command.image
                )
            )
        ) {
            is StartOutcome.HashConflict -> return StartReceiptAnalysisResult.HashConflict
            is StartOutcome.InsufficientCredits -> return StartReceiptAnalysisResult.InsufficientCredits
            is StartOutcome.Tombstoned -> return StartReceiptAnalysisResult.Tombstoned
            is StartOutcome.Accepted -> startOutcome
        }

        // Attempted regardless of isNew -- a Received record left over from a crash before any
        // claim was ever attempted is claimed exactly the same way as a brand-new one.
        return when (val claim = store.claimInvocation(accepted.operation.operationId)) {
            is ClaimOutcome.Claimed -> resolveByInvokingProvider(claim.operation, command.image)
            ClaimOutcome.AlreadyClaimedOrResolved -> resolveByReloading(command.userId, command.requestId)
        }
    }

    /** Calls [port] exactly once -- only reachable after winning [ReceiptAnalysisOperationStore.claimInvocation]. */
    private suspend fun resolveByInvokingProvider(
        claimedOperation: ReceiptAnalysisOperation,
        image: PreparedReceiptImage
    ): StartReceiptAnalysisResult {
        val attempt = ReceiptAnalysisAttempt(claimedOperation.attemptId, image)

        val application = try {
            mapReceiptAnalysisProviderOutcome(claimedOperation.attemptId, port(attempt))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Exception) {
            // Whether the provider actually ran is unknown -- never guessed as success or
            // failure, never retried; the reservation stays intact until reconciliation resolves it.
            // The exception stops here, but it is never discarded: without reporting it, this line
            // turns every possible failure into an indistinguishable RECONCILING, which is how six
            // operations were lost with nothing in the logs to explain them.
            reportFailure(claimedOperation.operationId, unexpected)
            OutcomeApplication.ReconcilingDetected
        }

        val applied = store.applyOutcome(claimedOperation.operationId, application)
        return StartReceiptAnalysisResult.Resolved(applied.persistedOperation())
    }

    /** Never calls [port] -- either another caller already owns the claim, or it is resolved. */
    private suspend fun resolveByReloading(userId: UserId, requestId: RequestId): StartReceiptAnalysisResult =
        when (val lookup = store.findByRequestId(userId, requestId)) {
            is RequestLookup.Found -> StartReceiptAnalysisResult.Resolved(lookup.operation)
            RequestLookup.NotFound -> StartReceiptAnalysisResult.OperationUnexpectedlyMissing
            RequestLookup.Tombstoned -> StartReceiptAnalysisResult.Tombstoned
        }

    /**
     * Observation is best-effort and never influences the outcome: a broken observer must not turn
     * an already-uncertain invocation into a different result. Cancellation still propagates, so a
     * cancelled call is never absorbed here either.
     */
    private fun reportFailure(operationId: ReceiptAnalysisOperationId, failure: Exception) {
        try {
            failureObserver.invocationFailedUnexpectedly(operationId, failure)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Deliberately empty: reporting a failure must never create one.
        }
    }

    private fun ApplyOutcomeResult.persistedOperation(): ReceiptAnalysisOperation = when (this) {
        is ApplyOutcomeResult.Applied -> operation
        is ApplyOutcomeResult.AlreadyResolved -> operation
        is ApplyOutcomeResult.Rejected -> operation
    }
}
