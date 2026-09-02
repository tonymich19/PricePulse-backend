package com.tonycorreia.pricepulsebackend.infrastructure

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ResolutionSweepReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Virtual-time tests: the runner's only job is *when*, so a real 30-second wait would prove the
 * same thing far more slowly. Nothing here touches a store, a provider or a clock -- the sweep is
 * a lambda, because the policy it drives is tested in `ResolveReconcilingOperationsUseCaseTest`.
 */
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
                val attempt = sweeps.incrementAndGet()
                if (attempt == 1) throw IllegalStateException("boom")
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
    fun `no sweep runs after cancellation`() = runTest {
        val sweeps = AtomicInteger(0)
        val runner = ReconciliationRunner(
            sweep = { ResolutionSweepReport(sweeps.incrementAndGet(), 0, 0, 0) },
            interval = Duration.ofSeconds(30)
        )

        val job = runner.start(this)
        advanceTimeBy(95_000)
        job.cancel()
        val afterCancel = sweeps.get()

        advanceTimeBy(300_000)

        assertEquals(afterCancel, sweeps.get(), "a cancelled runner never sweeps again")
    }

    @Test
    fun `cancellation propagates out of a sweep instead of being swallowed as a failure`() = runTest {
        val sweeps = AtomicInteger(0)
        val runner = ReconciliationRunner(
            sweep = {
                sweeps.incrementAndGet()
                throw CancellationException("cancelled inside the sweep")
            },
            interval = Duration.ofSeconds(30)
        )

        val job = runner.start(this)
        advanceTimeBy(95_000)

        assertTrue(job.isCancelled, "a CancellationException from the sweep must end the loop, not be retried")
        assertEquals(1, sweeps.get(), "the loop stopped at the first cancellation")
    }

    @Test
    fun `rejects a non-positive interval`() {
        assertFailsWith<IllegalArgumentException> {
            ReconciliationRunner(sweep = { ResolutionSweepReport(0, 0, 0, 0) }, interval = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            ReconciliationRunner(sweep = { ResolutionSweepReport(0, 0, 0, 0) }, interval = Duration.ofSeconds(-1))
        }
    }
}
