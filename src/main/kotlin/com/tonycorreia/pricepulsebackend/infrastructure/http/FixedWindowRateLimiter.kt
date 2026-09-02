package com.tonycorreia.pricepulsebackend.infrastructure.http

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val SECONDS_PER_WINDOW = 60L

/**
 * Fixed one-minute window counter, keyed by [K]. Extracted verbatim from
 * [InProcessPollingRateLimiter] so the three abuse layers share one implementation instead of
 * copies: layer 3 keys by `UserId`, layer 1 by `(userId, requestId)` and layer 2 by `UserId`
 * (receiptanalysis-slice-report.md 6.10.62 point 1).
 *
 * The three mechanisms below each fixed a real concurrency bug (rounds 255-257) and are the reason
 * this is shared rather than duplicated: a monotonic sweep mark, "newest window wins", and a
 * saturating counter.
 */
internal class FixedWindowRateLimiter<K : Any>(
    private val clock: Clock,
    private val maxPerMinute: Int
) {

    private class Window(val startEpochSecond: Long, val count: Int)

    private val windows = ConcurrentHashMap<K, Window>()
    private val lastSweepEpochSecond = AtomicLong(Long.MIN_VALUE)

    init {
        // Excluding Int.MAX_VALUE keeps the saturation ceiling (maxPerMinute + 1) representable.
        require(maxPerMinute in 1 until Int.MAX_VALUE) {
            "maxPerMinute must be positive and below Int.MAX_VALUE, was $maxPerMinute"
        }
    }

    fun acquire(key: K): PollingRateLimitDecision {
        // Exactly one clock read per attempt: a second one could fall on the other side of a window
        // boundary and produce a retryAfterSeconds inconsistent with the denial it accompanies.
        val nowEpochSecond = clock.instant().epochSecond
        val windowStart = nowEpochSecond - Math.floorMod(nowEpochSecond, SECONDS_PER_WINDOW)

        sweepIfDue(windowStart)

        // compute() holds this key's bin lock for the whole remapping function, so concurrent
        // callers for the same key serialize and exactly maxPerMinute of them are admitted.
        // Different keys never contend.
        val updated = windows.compute(key) { _, existing ->
            when {
                existing == null -> Window(windowStart, 1)
                // A caller that read the clock just before a boundary can arrive here after one
                // that read it just after. Resetting on any mismatch would let that laggard wipe
                // the newer window's count and hand out a fresh allowance; instead the newer
                // window stands and the laggard is counted against it.
                existing.startEpochSecond > windowStart -> existing.incremented()
                existing.startEpochSecond < windowStart -> Window(windowStart, 1)
                else -> existing.incremented()
            }
        }!!

        return if (updated.count <= maxPerMinute) {
            PollingRateLimitDecision.Allowed
        } else {
            // Measured from the window the caller was actually counted against, not from the one it
            // captured: a laggard counted into the newer window would otherwise be told to retry
            // one second later, still inside the very window that limited it. Uses the window
            // compute() returned, so no second clock read is involved.
            val secondsLeft = updated.startEpochSecond + SECONDS_PER_WINDOW - nowEpochSecond
            PollingRateLimitDecision.Limited(secondsLeft.coerceAtMost(SECONDS_PER_WINDOW).toInt())
        }
    }

    /**
     * Amortized retention: at most one sweep per window, claimed by a single caller via CAS, drops
     * the entries of keys that stopped calling. Every other attempt stays O(1).
     *
     * The mark only ever moves forward, and only windows strictly older than it are removed. A
     * caller that read the clock just before a boundary therefore cannot drag the mark back and
     * delete the window another caller has already opened -- which would restart that window's
     * count and let it exceed [maxPerMinute].
     */
    private fun sweepIfDue(windowStart: Long) {
        while (true) {
            val previous = lastSweepEpochSecond.get()
            if (previous >= windowStart) return
            if (lastSweepEpochSecond.compareAndSet(previous, windowStart)) {
                windows.entries.removeIf { it.value.startEpochSecond < windowStart }
                return
            }
        }
    }

    /**
     * Saturating: once the count is one past [maxPerMinute] the window's verdict can no longer
     * change, so there is nothing left to count. Without this, `count + 1` would eventually wrap to
     * a negative Int under a sustained flood and satisfy `count <= maxPerMinute` again, handing the
     * flooder a fresh allowance.
     */
    private fun Window.incremented(): Window =
        if (count >= maxPerMinute + 1) this else Window(startEpochSecond, count + 1)

    /** Test-only: proves idle state is actually released rather than merely reset. */
    internal fun trackedKeyCount(): Int = windows.size

    /** Test-only: exposes the saturating counter so its ceiling can be asserted directly. */
    internal fun currentCountFor(key: K): Int = windows[key]?.count ?: 0
}
