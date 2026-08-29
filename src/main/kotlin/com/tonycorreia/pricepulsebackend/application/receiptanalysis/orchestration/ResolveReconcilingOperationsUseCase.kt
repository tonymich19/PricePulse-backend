package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisRetrievalPort
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * What one sweep did. Counters only -- never an operation id, user id or correlation reference, so
 * a caller can log this whole value without disclosing anything.
 */
data class ResolutionSweepReport(
    val examined: Int,
    val resolved: Int,
    val expired: Int,
    val stillPending: Int
)

/**
 * Resolves operations stuck in [ReceiptAnalysisOperationLifecycle.Reconciling] -- the one state
 * that, before this class existed, nothing in the backend could leave: [ReceiptAnalysisOperationStore.applyOutcome]
 * is the only writer of a terminal state, and its only other caller reaches it exclusively after
 * winning [ReceiptAnalysisOperationStore.claimInvocation], which claims from `RECEIVED` alone.
 *
 * Never calls [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderPort]:
 * a reconciling operation already consumed its single invocation. This only reads the provider's
 * view of that invocation through [retrieval], and applies the result through `applyOutcome`,
 * which stays the single authority for both state and credit.
 *
 * Safe to run concurrently with itself and with the start path. Retrieval is a read, and a
 * candidate someone else already settled answers [ApplyOutcomeResult.AlreadyResolved] -- never a
 * second ledger entry. That is why no lease, lock or new lifecycle state exists here.
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
                // outcome -- the operation stays RECONCILING until a later pass or its expiry.
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

    /** Null means "leave it RECONCILING" -- never a guessed outcome. */
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

    /**
     * Counter classification only, never a state decision. A provider that genuinely answers
     * `RETRY_EXHAUSTED` is counted here as expired; the two are indistinguishable by design, since
     * [expiredApplication] deliberately reuses that reason. The counters are observability and
     * never affect what was persisted, so a second code path to tell them apart would add
     * branching for no behavioural gain.
     */
    private fun isExpiry(application: OutcomeApplication): Boolean =
        application is OutcomeApplication.FailedNoProvider &&
            application.reason == ProviderTerminalFailureReason.RETRY_EXHAUSTED

    /**
     * `RETRY_EXHAUSTED` is reused deliberately: it is the only reason already in the v1 wire
     * vocabulary meaning "the backend gave up", so expiry needs no contract change. It is an
     * imperfect fit for the uncertain-invocation path, where no response was ever confirmed --
     * a truthful new reason belongs to a slice authorized to version the contract.
     *
     * The effect is `FAILED_NO_PROVIDER` + `RELEASED`: the reserved credit returns to the user,
     * which is the only defensible outcome for work this backend can no longer account for.
     */
    private fun expiredApplication(): OutcomeApplication =
        OutcomeApplication.FailedNoProvider(ProviderTerminalFailureReason.RETRY_EXHAUSTED)
}
