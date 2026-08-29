package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId
import java.time.Clock

private const val SECONDS_PER_WINDOW = 60

/**
 * The whole outcome of one polling attempt, including the wait hint. [Limited] carries
 * [retryAfterSeconds] itself so the body and the header of a 429 can never disagree: there is no
 * second query to ask "how long?", which could be answered from a different clock sample than the
 * one that denied the request.
 */
sealed interface PollingRateLimitDecision {
    data object Allowed : PollingRateLimitDecision

    data class Limited(val retryAfterSeconds: Int) : PollingRateLimitDecision {
        init {
            require(retryAfterSeconds in 1..SECONDS_PER_WINDOW) {
                "retryAfterSeconds must be within the window, was $retryAfterSeconds"
            }
        }
    }
}

fun interface PollingRateLimiter {
    /** One atomic decision per attempt -- never a separate "may I?" and "how long?" pair. */
    fun acquire(userId: UserId): PollingRateLimitDecision
}

/**
 * Fixed one-minute window per user, layer 3 of the abuse contract
 * (receiptanalysis-slice-report.md 6.10.7; 30/min decided in round 204).
 *
 * Per-instance by design: with more than one instance deployed the effective limit is a multiple of
 * [maxPerMinute]. A shared limiter is a separate slice.
 *
 * The window algorithm itself now lives in [FixedWindowRateLimiter], so layers 1 and 2 of the POST
 * reuse it instead of holding a second copy of the monotonic sweep, "newest window wins" and
 * saturating counter -- the three mechanisms that each cost a real bug in rounds 255-257. This
 * class keeps its public surface and behaviour unchanged; only the storage moved.
 */
class InProcessPollingRateLimiter(
    clock: Clock,
    maxPerMinute: Int = 30
) : PollingRateLimiter {

    private val delegate = FixedWindowRateLimiter<UserId>(clock, maxPerMinute)

    override fun acquire(userId: UserId): PollingRateLimitDecision = delegate.acquire(userId)

    /** Test-only: proves idle state is actually released rather than merely reset. */
    internal fun trackedUserCount(): Int = delegate.trackedKeyCount()

    /** Test-only: exposes the saturating counter so its ceiling can be asserted directly. */
    internal fun currentCountFor(userId: UserId): Int = delegate.currentCountFor(userId)
}
