package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId
import java.time.Clock

/** Layer 1's counting key: the pair the store itself is keyed by. */
data class RequestKey(val userId: UserId, val requestId: RequestId)

/**
 * Layers 1 and 2 of the abuse contract for `POST /v1/receipt-analyses`
 * (receiptanalysis-slice-report.md 6.10.7; 10/min each, decided in round 204).
 *
 * A normal interface, not a `fun interface`: it has two operations, so SAM conversion does not
 * apply. Layer 1 is consulted for a probable duplicate, layer 2 for a probable new analysis; the
 * two counters are independent, so exhausting one never consumes the other.
 */
interface AdmissionRateLimiter {
    /** Layer 1 -- keyed by `(userId, requestId)`, applied when the advisory lookup found a record. */
    fun acquireForExisting(userId: UserId, requestId: RequestId): PollingRateLimitDecision

    /** Layer 2 -- keyed by `userId`, applied when the advisory lookup found nothing. */
    fun acquireForNew(userId: UserId): PollingRateLimitDecision
}

/**
 * Two independent [FixedWindowRateLimiter] instances -- never one shared map with a composite key,
 * which would let one layer's traffic evict or contend with the other's.
 *
 * Per-instance by design, exactly like [InProcessPollingRateLimiter]: with more than one instance
 * deployed the effective limit is a multiple of [maxPerMinute]. A shared limiter is a separate
 * slice.
 */
class InProcessAdmissionRateLimiter(
    clock: Clock,
    maxPerMinute: Int = 10
) : AdmissionRateLimiter {

    private val layer1 = FixedWindowRateLimiter<RequestKey>(clock, maxPerMinute)
    private val layer2 = FixedWindowRateLimiter<UserId>(clock, maxPerMinute)

    override fun acquireForExisting(userId: UserId, requestId: RequestId): PollingRateLimitDecision =
        layer1.acquire(RequestKey(userId, requestId))

    override fun acquireForNew(userId: UserId): PollingRateLimitDecision = layer2.acquire(userId)
}
