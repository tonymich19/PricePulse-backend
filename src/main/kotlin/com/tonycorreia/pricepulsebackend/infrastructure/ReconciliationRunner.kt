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
 * `ResolveReconcilingOperationsUseCase`; this class only decides *when*, which is why its whole
 * contract is testable in virtual time with a lambda.
 *
 * A failing sweep is logged and the loop continues: a sweep that fails never mutated state, so the
 * next interval simply retries the same candidates. Cancellation always ends the loop and is never
 * absorbed by that failure handling -- the `CancellationException` catch precedes the general one,
 * the same discipline every other class in this backend follows.
 *
 * The first sweep runs immediately on start, not after one interval: a process that has just come
 * back up is exactly when reconciling operations are most likely to be waiting.
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
                // Silent when there was nothing to do: a 60-second heartbeat logging zeros forever
                // would bury the lines that matter.
                if (report.examined > 0) {
                    LOGGER.info(
                        "reconciliation sweep examined={} resolved={} expired={} stillPending={}",
                        report.examined,
                        report.resolved,
                        report.expired,
                        report.stillPending
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                // Never the exception object or its message: a JDBC or HTTP client failure can
                // carry the database host, the user, or a fragment of a connection URL. The same
                // discipline the routes already apply to store failures.
                LOGGER.warn("reconciliation sweep failed; retrying at the next interval")
            }
            delay(interval.toMillis())
        }
    }

    private companion object {
        private val LOGGER = LoggerFactory.getLogger(ReconciliationRunner::class.java)
    }
}
