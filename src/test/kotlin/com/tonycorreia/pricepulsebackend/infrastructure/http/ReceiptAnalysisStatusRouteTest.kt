package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.FailedNoProviderReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderRejectionReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ValidatedReceiptAnalysisResultV1
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ApplyOutcomeResult
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ClaimOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ContentHash
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.OutcomeApplication
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperation
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationLifecycle
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationResult
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationStore
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReconciliationCandidate
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestLookup
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartReceiptAnalysisOperationCommand
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId
import com.tonycorreia.pricepulsebackend.infrastructure.firebase.FirebaseIdTokenVerifier
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole contract of `GET /v1/receipt-analyses/{requestId}` (receiptanalysis-slice-report.md
 * 6.10.60): all twelve response paths, the six authentication failure modes, the twelve
 * FAILED_NO_PROVIDER reasons, cross-user isolation, and the limiter's atomicity, window and
 * retention.
 *
 * Entirely offline -- no Firebase project, no database, no network. The store is a fake and the
 * verifier is a lambda.
 */
class ReceiptAnalysisStatusRouteTest {

    private val mapper = ObjectMapper()
    private val userA = UserId("user-a")
    private val userB = UserId("user-b")
    private val validRequestId = "req-123_ABC"

    // ------------------------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------------------------

    /** Answers [lookup] for any read, or raises [failure] when set. Nothing else is reachable. */
    private class FakeStore(
        private val lookup: RequestLookup = RequestLookup.NotFound,
        private val failure: (() -> Nothing)? = null,
        private val expectedUserId: UserId? = null
    ) : ReceiptAnalysisOperationStore {
        /** Reconciliation is out of this slice; no test here drives a resolver. */
        override suspend fun findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate> =
            throw UnsupportedOperationException("out of this slice")

        override suspend fun findByRequestId(userId: UserId, requestId: RequestId): RequestLookup {
            failure?.invoke()
            // Mirrors the real port: the key includes the user, so another user's id never matches.
            if (expectedUserId != null && userId != expectedUserId) return RequestLookup.NotFound
            return lookup
        }

        override suspend fun startOrGetExisting(command: StartReceiptAnalysisOperationCommand): StartOutcome =
            throw UnsupportedOperationException("out of this slice")

        override suspend fun claimInvocation(operationId: ReceiptAnalysisOperationId): ClaimOutcome =
            throw UnsupportedOperationException("out of this slice")

        override suspend fun applyOutcome(
            operationId: ReceiptAnalysisOperationId,
            application: OutcomeApplication
        ): ApplyOutcomeResult = throw UnsupportedOperationException("out of this slice")
    }

    private class FixedClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    private fun operation(
        lifecycle: ReceiptAnalysisOperationLifecycle,
        requestId: String = validRequestId,
        userId: UserId = userA
    ) = ReceiptAnalysisOperation(
        operationId = ReceiptAnalysisOperationId("op-1"),
        userId = userId,
        requestId = RequestId(requestId),
        contentHash = ContentHash("a".repeat(64)),
        sizeBytes = 1024,
        mimeType = "image/jpeg",
        attemptId = ReceiptAnalysisAttemptId("attempt-1"),
        lifecycle = lifecycle
    )

    private fun sampleDocument(): ValidatedReceiptAnalysisResultV1 {
        val fixture = File(System.getProperty("user.dir"), "contracts/fixtures/valid/complete-with-items.json")
        return requireNotNull(ValidatedReceiptAnalysisResultV1.from(fixture.readBytes()))
    }

    private fun ApplicationTestBuilder.installRoute(
        store: ReceiptAnalysisOperationStore,
        verifier: FirebaseIdTokenVerifier = FirebaseIdTokenVerifier { userA },
        limiter: PollingRateLimiter = PollingRateLimiter { PollingRateLimitDecision.Allowed }
    ) {
        application {
            routing {
                receiptAnalysisStatusRoute(verifier, store, limiter)
            }
        }
    }

    private suspend fun HttpResponse.assertJson(status: HttpStatusCode, body: String) {
        assertEquals(status, this.status)
        assertEquals(ContentType.Application.Json, contentType()?.withoutParameters())
        assertEquals(body, bodyAsText())
    }

    private fun path(requestId: String = validRequestId) = "/v1/receipt-analyses/$requestId"

    // ------------------------------------------------------------------------------------
    // Row 1 -- 401, byte-for-byte identical across all six modes
    // ------------------------------------------------------------------------------------

    @Test
    fun `all six authentication failure modes produce a byte-identical 401 with the Bearer challenge`() =
        testApplication {
            // Mode 6 (a token the verifier rejects) needs a verifier that returns null; the other
            // five never reach it, so one verifier covers all six.
            installRoute(FakeStore(), verifier = FirebaseIdTokenVerifier { null })

            val headers: List<String?> = listOf(
                null,               // absent
                "",                 // empty
                "   ",              // blank
                "Basic abc123",     // wrong scheme
                "Bearer",           // scheme only, no token
                "Bearer rejected"   // well-formed, verifier rejects it
            )

            val bodies = headers.map { header ->
                val response = client.get(path()) {
                    if (header != null) header(HttpHeaders.Authorization, header)
                }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
                assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
                response.bodyAsText()
            }

            // Identical to each other and to the compile-time constant: no mode is distinguishable.
            assertEquals(1, bodies.toSet().size)
            assertEquals(ReceiptAnalysisStatusResponses.UNAUTHORIZED_BODY, bodies.first())
            // The 401 never carries a requestId: it has not been validated at that point.
            assertTrue(!bodies.first().contains("requestId"))
        }

    @Test
    fun `a well-formed token accepted by the verifier is not answered with 401`() = testApplication {
        installRoute(FakeStore(lookup = RequestLookup.NotFound))

        val response = client.get(path()) { header(HttpHeaders.Authorization, "Bearer good-token") }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `the Bearer scheme is matched case-insensitively, per RFC 6750`() = testApplication {
        installRoute(FakeStore(lookup = RequestLookup.NotFound))

        val response = client.get(path()) { header(HttpHeaders.Authorization, "bearer good-token") }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ------------------------------------------------------------------------------------
    // Row 2 -- 429
    // ------------------------------------------------------------------------------------

    @Test
    fun `a limited request answers 429 with Retry-After equal to the body value, and never reads the store`() =
        testApplication {
            val storeThatMustNotBeCalled = FakeStore(
                failure = { throw AssertionError("the store must never be reached once limited") }
            )
            installRoute(
                storeThatMustNotBeCalled,
                limiter = PollingRateLimiter { PollingRateLimitDecision.Limited(42) }
            )

            val response = client.get(path()) { header(HttpHeaders.Authorization, "Bearer good") }

            response.assertJson(
                HttpStatusCode.TooManyRequests,
                """{"envelopeVersion":"v1","error":"RATE_LIMITED","retryAfter":42}"""
            )
            assertEquals("42", response.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `an unauthenticated request is rejected before the limiter is ever consulted`() = testApplication {
        installRoute(
            FakeStore(),
            verifier = FirebaseIdTokenVerifier { null },
            limiter = PollingRateLimiter { throw AssertionError("limiter reached before authentication") }
        )

        val response = client.get(path())

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ------------------------------------------------------------------------------------
    // Row 3 -- 400
    // ------------------------------------------------------------------------------------

    @Test
    fun `an invalid requestId answers 400 with a fixed body that never echoes the rejected value`() =
        testApplication {
            installRoute(FakeStore(failure = { throw AssertionError("store reached with an invalid requestId") }))

            val rejected = "a".repeat(129)
            val response = client.get(path(rejected)) { header(HttpHeaders.Authorization, "Bearer good") }

            response.assertJson(HttpStatusCode.BadRequest, ReceiptAnalysisStatusResponses.INVALID_REQUEST_ID_BODY)
            assertTrue(!response.bodyAsText().contains(rejected))
        }

    @Test
    fun `a requestId outside the charset answers 400`() = testApplication {
        installRoute(FakeStore(failure = { throw AssertionError("store reached with an invalid requestId") }))

        val response = client.get("/v1/receipt-analyses/has%20space") {
            header(HttpHeaders.Authorization, "Bearer good")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ------------------------------------------------------------------------------------
    // Rows 4 and 5 -- 404 and 410, never conflated
    // ------------------------------------------------------------------------------------

    @Test
    fun `NotFound answers 404 and Tombstoned answers 410, with distinct bodies`() = testApplication {
        installRoute(FakeStore(lookup = RequestLookup.NotFound))
        val notFound = client.get(path()) { header(HttpHeaders.Authorization, "Bearer good") }
        notFound.assertJson(
            HttpStatusCode.NotFound,
            """{"envelopeVersion":"v1","requestId":"$validRequestId","error":"NOT_FOUND"}"""
        )
    }

    @Test
    fun `Tombstoned answers 410 GONE, never 404`() = testApplication {
        installRoute(FakeStore(lookup = RequestLookup.Tombstoned))

        val response = client.get(path()) { header(HttpHeaders.Authorization, "Bearer good") }

        response.assertJson(
            HttpStatusCode.Gone,
            """{"envelopeVersion":"v1","requestId":"$validRequestId","error":"GONE"}"""
        )
    }

    // ------------------------------------------------------------------------------------
    // Rows 6-8 -- the non-terminal states
    // ------------------------------------------------------------------------------------

    @Test
    fun `the three non-terminal lifecycles map to RECEIVED, PROCESSING and RECONCILING`() {
        val expected = listOf(
            ReceiptAnalysisOperationLifecycle.Received to "RECEIVED",
            ReceiptAnalysisOperationLifecycle.InvocationClaimed to "PROCESSING",
            ReceiptAnalysisOperationLifecycle.Reconciling to "RECONCILING"
        )
        expected.forEach { (lifecycle, wireStatus) ->
            testApplication {
                installRoute(FakeStore(lookup = RequestLookup.Found(operation(lifecycle))))

                val response = client.get(path()) { header(HttpHeaders.Authorization, "Bearer good") }

                response.assertJson(
                    HttpStatusCode.OK,
                    """{"envelopeVersion":"v1","requestId":"$validRequestId","status":"$wireStatus"}"""
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Rows 9-11 -- the terminal states
    // ------------------------------------------------------------------------------------

    @Test
    fun `SUCCEEDED carries the validated document`() = testApplication {
        val document = sampleDocument()
        val lifecycle = ReceiptAnalysisOperationLifecycle.Terminal(
            ReceiptAnalysisOperationResult.Succeeded(document)
        )
        installRoute(FakeStore(lookup = RequestLookup.Found(operation(lifecycle))))

        val response = client.get(path()) { header(HttpHeaders.Authorization, "Bearer good") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = mapper.readTree(response.bodyAsText())
        assertEquals("v1", body.get("envelopeVersion").asText())
        assertEquals(validRequestId, body.get("requestId").asText())
        assertEquals("SUCCEEDED", body.get("status").asText())
        assertEquals(mapper.readTree(document.serialize()), body.get("document"))
    }

    @Test
    fun `FAILED carries no reason in V1`() = testApplication {
        val lifecycle = ReceiptAnalysisOperationLifecycle.Terminal(ReceiptAnalysisOperationResult.Failed)
        installRoute(FakeStore(lookup = RequestLookup.Found(operation(lifecycle))))

        val response = client.get(path()) { header(HttpHeaders.Authorization, "Bearer good") }

        response.assertJson(
            HttpStatusCode.OK,
            """{"envelopeVersion":"v1","requestId":"$validRequestId","status":"FAILED"}"""
        )
        assertNull(mapper.readTree(response.bodyAsText()).get("reason"))
    }

    @Test
    fun `each of the twelve FAILED_NO_PROVIDER reasons maps to its literal wire value`() {
        val expected: List<Pair<FailedNoProviderReason, String>> = listOf(
            ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED to "REQUEST_CONSTRUCTION_FAILED",
            ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE to "PROVIDER_CREDENTIAL_UNAVAILABLE",
            ProviderRejectionReason.MALFORMED_REQUEST to "MALFORMED_REQUEST",
            ProviderRejectionReason.AUTHENTICATION_REJECTED to "AUTHENTICATION_REJECTED",
            ProviderRejectionReason.ACCESS_FORBIDDEN to "ACCESS_FORBIDDEN",
            ProviderTerminalFailureReason.RETRY_EXHAUSTED to "RETRY_EXHAUSTED",
            ProviderTerminalFailureReason.BILLING_OR_QUOTA_EXHAUSTED to "BILLING_OR_QUOTA_EXHAUSTED",
            ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE to "UNRECOGNIZED_RESPONSE",
            ProviderTerminalFailureReason.RESPONSE_FAILED to "RESPONSE_FAILED",
            ProviderTerminalFailureReason.RESPONSE_CANCELLED to "RESPONSE_CANCELLED",
            ProviderTerminalFailureReason.RATE_LIMITED to "RATE_LIMITED",
            ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE to "TRANSIENT_PROVIDER_FAILURE"
        )
        // Guards the count itself: a reason added to any of the three enums fails here as well as
        // at the exhaustive `when` in the production mapper.
        val declared = ProviderCallFailureReason.entries.size +
            ProviderRejectionReason.entries.size +
            ProviderTerminalFailureReason.entries.size
        assertEquals(declared, expected.size)

        expected.forEach { (reason, wire) ->
            testApplication {
                val lifecycle = ReceiptAnalysisOperationLifecycle.Terminal(
                    ReceiptAnalysisOperationResult.FailedNoProvider(reason)
                )
                installRoute(FakeStore(lookup = RequestLookup.Found(operation(lifecycle))))

                val response = client.get(path()) { header(HttpHeaders.Authorization, "Bearer good") }

                response.assertJson(
                    HttpStatusCode.OK,
                    """{"envelopeVersion":"v1","requestId":"$validRequestId",""" +
                        """"status":"FAILED_NO_PROVIDER","reason":"$wire"}"""
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Row 12 -- controlled store failure
    // ------------------------------------------------------------------------------------

    @Test
    fun `an unexpected store failure answers 500 without leaking the connection string or token`() =
        testApplication {
            val syntheticUrl = "jdbc:postgresql://fixture-user:fixture-password@fixture-host.invalid:5432/db"
            val syntheticToken = "eyJhbGciOiJSUzI1NiJ9.fixture-token-payload.fixture-signature"
            installRoute(
                FakeStore(failure = { throw SQLException("connection failed for $syntheticUrl while $syntheticToken") })
            )

            val response = client.get(path()) { header(HttpHeaders.Authorization, "Bearer $syntheticToken") }

            response.assertJson(
                HttpStatusCode.InternalServerError,
                """{"envelopeVersion":"v1","requestId":"$validRequestId","error":"INTERNAL_ERROR"}"""
            )
            val body = response.bodyAsText()
            assertTrue(!body.contains(syntheticUrl))
            assertTrue(!body.contains("fixture-password"))
            assertTrue(!body.contains(syntheticToken))
        }

    @Test
    fun `a cancellation from the store propagates instead of becoming a 500`() = testApplication {
        installRoute(FakeStore(failure = { throw kotlin.coroutines.cancellation.CancellationException("cancelled") }))

        val failure = runCatching {
            client.get(path()) { header(HttpHeaders.Authorization, "Bearer good") }
        }

        // Either the exception surfaces to the caller, or the server reports a failure -- what must
        // never happen is a clean 500 INTERNAL_ERROR, which would swallow the cancellation.
        val cleanInternalError = failure.getOrNull()?.let {
            it.status == HttpStatusCode.InternalServerError &&
                it.bodyAsText().contains("INTERNAL_ERROR")
        } ?: false
        assertTrue(!cleanInternalError)
    }

    // ------------------------------------------------------------------------------------
    // Cross-user isolation
    // ------------------------------------------------------------------------------------

    @Test
    fun `another user's requestId is answered byte-for-byte like one that never existed`() {
        var neverExisted = ""
        var otherUsers = ""

        testApplication {
            installRoute(FakeStore(lookup = RequestLookup.NotFound), verifier = FirebaseIdTokenVerifier { userB })
            val response = client.get(path()) { header(HttpHeaders.Authorization, "Bearer b") }
            assertEquals(HttpStatusCode.NotFound, response.status)
            neverExisted = response.bodyAsText()
        }

        testApplication {
            // The record exists, but belongs to user A; user B asks for it.
            installRoute(
                FakeStore(
                    lookup = RequestLookup.Found(operation(ReceiptAnalysisOperationLifecycle.Received)),
                    expectedUserId = userA
                ),
                verifier = FirebaseIdTokenVerifier { userB }
            )
            val response = client.get(path()) { header(HttpHeaders.Authorization, "Bearer b") }
            assertEquals(HttpStatusCode.NotFound, response.status)
            otherUsers = response.bodyAsText()
        }

        assertEquals(neverExisted, otherUsers)
    }

    // ------------------------------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------------------------------

    @Test
    fun `extractBearerToken accepts only a well-formed Bearer header`() {
        assertEquals("abc", extractBearerToken("Bearer abc"))
        assertEquals("abc", extractBearerToken("bearer abc"))
        assertEquals("abc", extractBearerToken("BEARER abc"))
        assertNull(extractBearerToken(null))
        assertNull(extractBearerToken(""))
        assertNull(extractBearerToken("   "))
        assertNull(extractBearerToken("Bearer"))
        assertNull(extractBearerToken("Bearer "))
        assertNull(extractBearerToken("Bearer    "))
        assertNull(extractBearerToken("Basic abc"))
        assertNull(extractBearerToken("abc"))
    }

    @Test
    fun `parseRequestId enforces the edge contract of non-blank, at most 128, and the charset`() {
        assertEquals(RequestId("abc"), parseRequestId("abc"))
        assertEquals(RequestId("A-1_z"), parseRequestId("A-1_z"))
        assertNotNull(parseRequestId("a".repeat(128)))
        assertNull(parseRequestId("a".repeat(129)))
        assertNull(parseRequestId(null))
        assertNull(parseRequestId(""))
        assertNull(parseRequestId("   "))
        assertNull(parseRequestId("has space"))
        assertNull(parseRequestId("has/slash"))
        assertNull(parseRequestId("acentuação"))
    }

    // ------------------------------------------------------------------------------------
    // Limiter -- atomicity, window, retention
    // ------------------------------------------------------------------------------------

    @Test
    fun `exactly thirty concurrent attempts are admitted, and the rest are limited`() {
        val limiter = InProcessPollingRateLimiter(FixedClock(Instant.parse("2026-08-26T10:00:00Z")))
        val threads = 100
        val results = java.util.Collections.synchronizedList(mutableListOf<PollingRateLimitDecision>())
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(threads)

        repeat(threads) {
            Thread {
                start.await()
                results.add(limiter.acquire(userA))
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await()

        assertEquals(30, results.count { it is PollingRateLimitDecision.Allowed })
        assertEquals(70, results.count { it is PollingRateLimitDecision.Limited })
    }

    @Test
    fun `the thirty-first sequential attempt in a window is limited`() {
        val limiter = InProcessPollingRateLimiter(FixedClock(Instant.parse("2026-08-26T10:00:00Z")))

        repeat(30) { assertTrue(limiter.acquire(userA) is PollingRateLimitDecision.Allowed) }

        assertTrue(limiter.acquire(userA) is PollingRateLimitDecision.Limited)
    }

    @Test
    fun `the next window admits the same user again`() {
        val clock = FixedClock(Instant.parse("2026-08-26T10:00:30Z"))
        val limiter = InProcessPollingRateLimiter(clock)
        repeat(30) { limiter.acquire(userA) }
        assertTrue(limiter.acquire(userA) is PollingRateLimitDecision.Limited)

        clock.advanceSeconds(30)

        assertTrue(limiter.acquire(userA) is PollingRateLimitDecision.Allowed)
    }

    @Test
    fun `retryAfterSeconds is the ceiling of the time left, always within one and sixty`() {
        val clock = FixedClock(Instant.parse("2026-08-26T10:00:00Z"))
        val limiter = InProcessPollingRateLimiter(clock)
        repeat(30) { limiter.acquire(userA) }

        // At the very start of the window the whole minute remains.
        assertEquals(60, (limiter.acquire(userA) as PollingRateLimitDecision.Limited).retryAfterSeconds)

        clock.advanceSeconds(59)
        // At the last second of the same window, one second remains.
        assertEquals(1, (limiter.acquire(userA) as PollingRateLimitDecision.Limited).retryAfterSeconds)
    }

    @Test
    fun `idle user state is released, not merely reset`() {
        val clock = FixedClock(Instant.parse("2026-08-26T10:00:00Z"))
        val limiter = InProcessPollingRateLimiter(clock)
        limiter.acquire(userA)
        assertEquals(1, limiter.trackedUserCount())

        clock.advanceSeconds(120)
        // Another user's attempt in a later window claims the sweep and drops user A's entry.
        limiter.acquire(userB)

        assertEquals(1, limiter.trackedUserCount())
    }

    /**
     * A clock that parks its very first reader until the test releases it, so the boundary
     * interleaving can be forced deterministically instead of raced for.
     */
    private class GatedBoundaryClock(
        private val beforeBoundary: Instant,
        private val afterBoundary: Instant,
        private val firstReadReached: java.util.concurrent.CountDownLatch,
        private val releaseFirstReader: java.util.concurrent.CountDownLatch
    ) : Clock() {
        private val firstReader = java.util.concurrent.atomic.AtomicBoolean(false)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant =
            if (firstReader.compareAndSet(false, true)) {
                firstReadReached.countDown()
                releaseFirstReader.await()
                beforeBoundary
            } else {
                afterBoundary
            }
    }

    /**
     * Regression for the boundary defect found in the 255 review round: a caller that captured the
     * last second of a window could resume after a caller in the next window, drag the sweep mark
     * backwards and delete the newer window's entry -- restarting its count and letting more than
     * thirty through.
     */
    @Test
    fun `a laggard from the previous window cannot restart the new window's count`() {
        val beforeBoundary = Instant.parse("2026-08-26T10:00:59Z")
        val afterBoundary = Instant.parse("2026-08-26T10:01:00Z")
        val firstReadReached = java.util.concurrent.CountDownLatch(1)
        val releaseFirstReader = java.util.concurrent.CountDownLatch(1)
        val limiter = InProcessPollingRateLimiter(
            GatedBoundaryClock(beforeBoundary, afterBoundary, firstReadReached, releaseFirstReader)
        )

        // The laggard reads 10:00:59 and parks inside the clock, before sweeping or counting.
        val laggard = Thread { limiter.acquire(userA) }
        laggard.start()
        firstReadReached.await()

        // Meanwhile a caller in the new window sweeps and opens 10:01:00 with a count of one.
        assertTrue(limiter.acquire(userA) is PollingRateLimitDecision.Allowed)

        // The laggard now resumes and completes its own acquire.
        releaseFirstReader.countDown()
        laggard.join()

        // The new window has already spent at least one of its thirty. Draining the rest must
        // therefore run out: if the laggard had wiped it, thirty more would be admitted here.
        val admitted = (1..30).count { limiter.acquire(userA) is PollingRateLimitDecision.Allowed }
        assertTrue(admitted < 30, "the new window's count was restarted: $admitted more were admitted")
        assertTrue(limiter.acquire(userA) is PollingRateLimitDecision.Limited)
    }

    /**
     * Regression for the `Retry-After` defect found in the 257 review round, introduced by the
     * laggard-preservation fix itself: the wait was still measured from the window the caller
     * captured, so a laggard counted into the newer window was told to retry one second later --
     * inside the very window that had just limited it. It must be measured from the window it was
     * actually counted against.
     */
    @Test
    fun `a laggard limited by the new window is told to wait for that window, not its own`() {
        val beforeBoundary = Instant.parse("2026-08-26T10:00:59Z")
        val afterBoundary = Instant.parse("2026-08-26T10:01:00Z")
        val firstReadReached = java.util.concurrent.CountDownLatch(1)
        val releaseFirstReader = java.util.concurrent.CountDownLatch(1)
        val limiter = InProcessPollingRateLimiter(
            GatedBoundaryClock(beforeBoundary, afterBoundary, firstReadReached, releaseFirstReader)
        )

        // The laggard reads 10:00:59 and parks inside the clock before counting.
        val decision = java.util.concurrent.atomic.AtomicReference<PollingRateLimitDecision>()
        val laggard = Thread { decision.set(limiter.acquire(userA)) }
        laggard.start()
        firstReadReached.await()

        // The new window is filled to its limit while the laggard is parked.
        repeat(30) { assertTrue(limiter.acquire(userA) is PollingRateLimitDecision.Allowed) }

        releaseFirstReader.countDown()
        laggard.join()

        // Counted as the 31st of 10:01:00, so the wait must span that whole window -- never the
        // single second left of 10:00:00, which would put the retry back inside 10:01:00.
        val limited = decision.get()
        assertTrue(limited is PollingRateLimitDecision.Limited, "expected the laggard to be limited")
        assertEquals(60, (limited as PollingRateLimitDecision.Limited).retryAfterSeconds)
    }

    /**
     * Regression for the overflow found in the 255 review round: an unsaturated `count + 1` would
     * wrap to a negative Int under a sustained flood and satisfy `count <= maxPerMinute` again.
     * Proven at the ceiling itself rather than by making billions of calls.
     */
    @Test
    fun `the counter saturates one past the limit instead of growing without bound`() {
        val limiter = InProcessPollingRateLimiter(FixedClock(Instant.parse("2026-08-26T10:00:00Z")), maxPerMinute = 2)

        repeat(50) { limiter.acquire(userA) }

        // Two admitted, the third denied, and every later attempt pinned at that same ceiling --
        // so the value can never approach Int.MAX_VALUE, whatever the traffic.
        assertEquals(3, limiter.currentCountFor(userA))
        assertTrue(limiter.acquire(userA) is PollingRateLimitDecision.Limited)
        assertEquals(3, limiter.currentCountFor(userA))
    }

    @Test
    fun `maxPerMinute must leave room for the saturation ceiling`() {
        assertFailsWith<IllegalArgumentException> {
            InProcessPollingRateLimiter(FixedClock(Instant.parse("2026-08-26T10:00:00Z")), maxPerMinute = Int.MAX_VALUE)
        }
        assertFailsWith<IllegalArgumentException> {
            InProcessPollingRateLimiter(FixedClock(Instant.parse("2026-08-26T10:00:00Z")), maxPerMinute = 0)
        }
    }

    @Test
    fun `distinct users do not consume each other's allowance`() {
        val limiter = InProcessPollingRateLimiter(FixedClock(Instant.parse("2026-08-26T10:00:00Z")))
        repeat(30) { limiter.acquire(userA) }
        assertTrue(limiter.acquire(userA) is PollingRateLimitDecision.Limited)

        assertTrue(limiter.acquire(userB) is PollingRateLimitDecision.Allowed)
    }
}
