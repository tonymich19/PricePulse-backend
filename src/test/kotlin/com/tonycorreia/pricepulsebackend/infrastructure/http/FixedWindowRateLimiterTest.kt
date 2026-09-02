package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The extracted window algorithm, and the property that matters for admission: the two admission
 * layers count independently.
 *
 * The window boundary, monotonic sweep and saturating counter -- the three mechanisms that each
 * cost a real bug in rounds 255-257 -- remain covered by `InProcessPollingRateLimiterTest`, which
 * this extraction deliberately left untouched so it keeps working as the regression guard.
 */
class FixedWindowRateLimiterTest {

    private class FixedClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    private val userA = UserId("user-a")
    private val userB = UserId("user-b")

    @Test
    fun `admits exactly maxPerMinute in one window and denies the next`() {
        val limiter = FixedWindowRateLimiter<UserId>(FixedClock(Instant.parse("2026-08-27T10:00:00Z")), 3)

        repeat(3) { assertIs<PollingRateLimitDecision.Allowed>(limiter.acquire(userA)) }

        assertIs<PollingRateLimitDecision.Limited>(limiter.acquire(userA))
    }

    @Test
    fun `different keys never share an allowance`() {
        val limiter = FixedWindowRateLimiter<UserId>(FixedClock(Instant.parse("2026-08-27T10:00:00Z")), 2)

        repeat(2) { limiter.acquire(userA) }

        assertIs<PollingRateLimitDecision.Limited>(limiter.acquire(userA))
        assertIs<PollingRateLimitDecision.Allowed>(limiter.acquire(userB))
    }

    @Test
    fun `a new window restores the allowance and releases idle keys`() {
        val clock = FixedClock(Instant.parse("2026-08-27T10:00:00Z"))
        val limiter = FixedWindowRateLimiter<UserId>(clock, 1)

        assertIs<PollingRateLimitDecision.Allowed>(limiter.acquire(userA))
        assertIs<PollingRateLimitDecision.Limited>(limiter.acquire(userA))

        clock.advanceSeconds(60)

        assertIs<PollingRateLimitDecision.Allowed>(limiter.acquire(userA))
        assertEquals(1, limiter.trackedKeyCount())
    }

    @Test
    fun `the counter saturates one past the limit instead of wrapping`() {
        val limiter = FixedWindowRateLimiter<UserId>(FixedClock(Instant.parse("2026-08-27T10:00:00Z")), 2)

        repeat(50) { limiter.acquire(userA) }

        assertEquals(3, limiter.currentCountFor(userA))
    }

    // ---------------------------------------------------------------------------------------
    // The property the POST actually depends on
    // ---------------------------------------------------------------------------------------

    @Test
    fun `layer 1 and layer 2 counters are independent -- exhausting one leaves the other untouched`() {
        val limiter = InProcessAdmissionRateLimiter(FixedClock(Instant.parse("2026-08-27T10:00:00Z")), 2)
        val requestId = RequestId("req-1")

        repeat(2) { limiter.acquireForExisting(userA, requestId) }
        assertIs<PollingRateLimitDecision.Limited>(limiter.acquireForExisting(userA, requestId))

        // Layer 2 for the same user has not been touched.
        assertIs<PollingRateLimitDecision.Allowed>(limiter.acquireForNew(userA))
    }

    /**
     * The default the production wiring actually runs on: `Application.kt` builds this limiter with
     * the clock alone, so `maxPerMinute = 10` -- the number decided in round 204 -- governs both
     * admission layers. Constructed here with no limit argument on purpose; passing one would test
     * a value production never uses. This pins the existing default, it does not decide a new one.
     */
    @Test
    fun `layer 1 admits ten by default and limits the eleventh`() {
        val limiter = InProcessAdmissionRateLimiter(FixedClock(Instant.parse("2026-08-27T10:00:00Z")))
        val requestId = RequestId("req-1")

        repeat(10) { assertIs<PollingRateLimitDecision.Allowed>(limiter.acquireForExisting(userA, requestId)) }

        assertIs<PollingRateLimitDecision.Limited>(limiter.acquireForExisting(userA, requestId))
    }

    @Test
    fun `layer 2 admits ten by default and limits the eleventh`() {
        val limiter = InProcessAdmissionRateLimiter(FixedClock(Instant.parse("2026-08-27T10:00:00Z")))

        repeat(10) { assertIs<PollingRateLimitDecision.Allowed>(limiter.acquireForNew(userA)) }

        assertIs<PollingRateLimitDecision.Limited>(limiter.acquireForNew(userA))
    }

    @Test
    fun `layer 1 keys by the request too, so a different key has its own allowance`() {
        val limiter = InProcessAdmissionRateLimiter(FixedClock(Instant.parse("2026-08-27T10:00:00Z")), 1)

        assertIs<PollingRateLimitDecision.Allowed>(limiter.acquireForExisting(userA, RequestId("req-1")))
        assertIs<PollingRateLimitDecision.Limited>(limiter.acquireForExisting(userA, RequestId("req-1")))
        assertIs<PollingRateLimitDecision.Allowed>(limiter.acquireForExisting(userA, RequestId("req-2")))
    }

    @Test
    fun `layer 2 keys by user only`() {
        val limiter = InProcessAdmissionRateLimiter(FixedClock(Instant.parse("2026-08-27T10:00:00Z")), 1)

        assertIs<PollingRateLimitDecision.Allowed>(limiter.acquireForNew(userA))
        assertIs<PollingRateLimitDecision.Limited>(limiter.acquireForNew(userA))
        assertIs<PollingRateLimitDecision.Allowed>(limiter.acquireForNew(userB))
    }
}
