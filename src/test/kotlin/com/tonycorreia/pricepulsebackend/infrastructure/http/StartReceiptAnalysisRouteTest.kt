package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderInvocationFailureObserver
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import java.time.Instant
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttempt
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderOutcome
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderPort
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
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartReceiptAnalysisUseCase
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId
import com.tonycorreia.pricepulsebackend.infrastructure.firebase.FirebaseIdTokenVerifier
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract of `POST /v1/receipt-analyses` (receiptanalysis-slice-report.md 6.10.52.1/6.10.62).
 *
 * Entirely offline: the store is a fake, the provider port is never reached, the verifier is a
 * lambda, and the upload reader is replaced wherever the test is about admission rather than about
 * parsing bytes. No Firebase project, no database, no network, no OpenAI.
 */
class StartReceiptAnalysisRouteTest {

    private val userA = UserId("user-a")
    private val validKey = "req-123_ABC"

    // ------------------------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------------------------

    /**
     * Mirrors the real store closely enough for the route's contract: the advisory lookup answers
     * [lookup], but once [startOrGetExisting] has created a record a later reload *finds* it -- which
     * is what makes `claimInvocation` returning `AlreadyClaimedOrResolved` resolve normally instead
     * of looking like a postcondition violation.
     */
    private class FakeStore(
        private val lookup: RequestLookup = RequestLookup.NotFound,
        private val startOutcome: StartOutcome? = null
    ) : ReceiptAnalysisOperationStore {
        /** Reconciliation is out of this slice; no test here drives a resolver. */
        override suspend fun findReconcilable(limit: Int, notUpdatedSince: Instant): List<ReconciliationCandidate> =
            throw UnsupportedOperationException("out of this slice")

        val lookupCalls = AtomicInteger(0)
        val startCalls = AtomicInteger(0)

        @Volatile
        private var created: ReceiptAnalysisOperation? = null

        override suspend fun findByRequestId(userId: UserId, requestId: RequestId): RequestLookup {
            lookupCalls.incrementAndGet()
            created?.let { return RequestLookup.Found(it) }
            return lookup
        }

        override suspend fun startOrGetExisting(command: StartReceiptAnalysisOperationCommand): StartOutcome {
            startCalls.incrementAndGet()
            val outcome = startOutcome ?: throw UnsupportedOperationException("not expected in this test")
            if (outcome is StartOutcome.Accepted) created = outcome.operation
            return outcome
        }

        /**
         * Always already claimed, so the provider is never reachable from these tests: any provider
         * call would therefore be the route's doing, which is exactly what the assertion checks.
         */
        override suspend fun claimInvocation(operationId: ReceiptAnalysisOperationId): ClaimOutcome =
            ClaimOutcome.AlreadyClaimedOrResolved

        override suspend fun applyOutcome(
            operationId: ReceiptAnalysisOperationId,
            application: OutcomeApplication
        ): ApplyOutcomeResult = throw UnsupportedOperationException("not expected in this test")
    }

    private class CountingProviderPort : ReceiptAnalysisProviderPort {
        val calls = AtomicInteger(0)
        override suspend fun invoke(attempt: ReceiptAnalysisAttempt): ReceiptAnalysisProviderOutcome {
            calls.incrementAndGet()
            throw UnsupportedOperationException("the provider must not be reached in these tests")
        }
    }

    private class CountingAdmission(
        private val existing: PollingRateLimitDecision = PollingRateLimitDecision.Allowed,
        private val forNew: PollingRateLimitDecision = PollingRateLimitDecision.Allowed
    ) : AdmissionRateLimiter {
        val existingCalls = AtomicInteger(0)
        val newCalls = AtomicInteger(0)

        override fun acquireForExisting(userId: UserId, requestId: RequestId): PollingRateLimitDecision {
            existingCalls.incrementAndGet()
            return existing
        }

        override fun acquireForNew(userId: UserId): PollingRateLimitDecision {
            newCalls.incrementAndGet()
            return forNew
        }
    }

    private class CountingUploadReader(
        private val result: ReceiptUploadResult = ReceiptUploadResult.Prepared(
            PreparedReceiptImage(ReceiptImageFixtures.png(), "image/png")
        )
    ) : ReceiptUploadReader {
        val calls = AtomicInteger(0)
        override suspend fun read(call: io.ktor.server.application.ApplicationCall): ReceiptUploadResult {
            calls.incrementAndGet()
            return result
        }
    }

    private fun operation(
        lifecycle: ReceiptAnalysisOperationLifecycle,
        requestId: String = validKey
    ) = ReceiptAnalysisOperation(
        operationId = ReceiptAnalysisOperationId("op-1"),
        userId = userA,
        requestId = RequestId(requestId),
        contentHash = ContentHash("a".repeat(64)),
        sizeBytes = 1024,
        mimeType = "image/png",
        attemptId = ReceiptAnalysisAttemptId("attempt-1"),
        lifecycle = lifecycle
    )

    private fun ApplicationTestBuilder.installRoute(
        store: ReceiptAnalysisOperationStore = FakeStore(),
        providerPort: ReceiptAnalysisProviderPort = CountingProviderPort(),
        verifier: FirebaseIdTokenVerifier = FirebaseIdTokenVerifier { userA },
        admission: AdmissionRateLimiter = CountingAdmission(),
        uploadReader: ReceiptUploadReader = CountingUploadReader()
    ) {
        application {
            routing {
                startReceiptAnalysisRoute(
                    verifier,
                    store,
                    StartReceiptAnalysisUseCase(store, providerPort, ProviderInvocationFailureObserver { _, _ -> }),
                    admission,
                    uploadReader
                )
            }
        }
    }

    private suspend fun HttpResponse.assertJson(status: HttpStatusCode, body: String) {
        assertEquals(status, this.status)
        assertEquals(ContentType.Application.Json, contentType()?.withoutParameters())
        assertEquals(body, bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.postAnalysis(
        key: String? = validKey,
        token: String? = "Bearer valid-token"
    ): HttpResponse = client.post("/v1/receipt-analyses") {
        token?.let { header(HttpHeaders.Authorization, it) }
        key?.let { header("Idempotency-Key", it) }
        setBody("ignored -- the upload reader is faked in these tests")
    }

    // ------------------------------------------------------------------------------------
    // Row 11 -- authentication
    // ------------------------------------------------------------------------------------

    @Test
    fun `an unauthenticated request is 401 with the shared constant body and the Bearer challenge`() =
        testApplication {
            val store = FakeStore()
            val reader = CountingUploadReader()
            installRoute(store = store, verifier = FirebaseIdTokenVerifier { null }, uploadReader = reader)

            val response = postAnalysis()

            response.assertJson(
                HttpStatusCode.Unauthorized,
                """{"envelopeVersion":"v1","error":"UNAUTHORIZED"}"""
            )
            assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
            // Nothing downstream of step 1 was touched.
            assertEquals(0, store.lookupCalls.get())
            assertEquals(0, reader.calls.get())
        }

    @Test
    fun `every authentication failure mode produces a byte-identical body`() = testApplication {
        installRoute(verifier = FirebaseIdTokenVerifier { null })

        val bodies = listOf(
            postAnalysis(token = null),
            postAnalysis(token = "Bearer "),
            postAnalysis(token = "Basic abc"),
            postAnalysis(token = "bearer valid-token"),
            postAnalysis(token = "Bearer rejected")
        ).map { it.bodyAsText() }

        assertEquals(1, bodies.toSet().size, "all 401 bodies must be byte-identical: $bodies")
    }

    // ------------------------------------------------------------------------------------
    // Row 12 -- the idempotency key, validated before any limiter or store read
    // ------------------------------------------------------------------------------------

    @Test
    fun `an invalid Idempotency-Key is 400 and never reaches the limiter, the store or the reader`() =
        testApplication {
            val store = FakeStore()
            val admission = CountingAdmission()
            val reader = CountingUploadReader()
            installRoute(store = store, admission = admission, uploadReader = reader)

            val response = postAnalysis(key = "not a valid key!")

            response.assertJson(
                HttpStatusCode.BadRequest,
                """{"envelopeVersion":"v1","error":"INVALID_REQUEST_ID"}"""
            )
            assertEquals(0, store.lookupCalls.get())
            assertEquals(0, admission.existingCalls.get())
            assertEquals(0, admission.newCalls.get())
            assertEquals(0, reader.calls.get())
        }

    @Test
    fun `an absent Idempotency-Key is rejected exactly like a malformed one`() = testApplication {
        installRoute()

        postAnalysis(key = null).assertJson(
            HttpStatusCode.BadRequest,
            """{"envelopeVersion":"v1","error":"INVALID_REQUEST_ID"}"""
        )
    }

    // ------------------------------------------------------------------------------------
    // Row 13 -- both admission layers, and the divergence from the GET
    // ------------------------------------------------------------------------------------

    @Test
    fun `layer 1 applies when the advisory lookup found a record, and its 429 carries the requestId`() =
        testApplication {
            val admission = CountingAdmission(existing = PollingRateLimitDecision.Limited(42))
            val reader = CountingUploadReader()
            installRoute(
                store = FakeStore(lookup = RequestLookup.Found(operation(ReceiptAnalysisOperationLifecycle.Received))),
                admission = admission,
                uploadReader = reader
            )

            val response = postAnalysis()

            response.assertJson(
                HttpStatusCode.TooManyRequests,
                """{"envelopeVersion":"v1","requestId":"$validKey","error":"RATE_LIMITED","retryAfter":42}"""
            )
            assertEquals("42", response.headers[HttpHeaders.RetryAfter])
            assertEquals(1, admission.existingCalls.get())
            assertEquals(0, admission.newCalls.get())
            // Layer 1 fires before the body is read at all.
            assertEquals(0, reader.calls.get())
        }

    @Test
    fun `layer 2 applies only when the lookup found nothing, and fires after the upload is read`() =
        testApplication {
            val admission = CountingAdmission(forNew = PollingRateLimitDecision.Limited(7))
            val reader = CountingUploadReader()
            val store = FakeStore(lookup = RequestLookup.NotFound)
            installRoute(store = store, admission = admission, uploadReader = reader)

            val response = postAnalysis()

            response.assertJson(
                HttpStatusCode.TooManyRequests,
                """{"envelopeVersion":"v1","requestId":"$validKey","error":"RATE_LIMITED","retryAfter":7}"""
            )
            assertEquals(0, admission.existingCalls.get())
            assertEquals(1, admission.newCalls.get())
            assertEquals(1, reader.calls.get())
            // Zero reservation: the use case was never invoked.
            assertEquals(0, store.startCalls.get())
        }

    // ------------------------------------------------------------------------------------
    // Rows 14-19 -- one body per upload rejection, exact status and literal
    // ------------------------------------------------------------------------------------

    @Test
    fun `each upload rejection maps to its own status and literal`() = testApplication {
        val expected = mapOf(
            UploadRejection.MALFORMED_UPLOAD to (HttpStatusCode.BadRequest to "MALFORMED_UPLOAD"),
            UploadRejection.UNSUPPORTED_MEDIA_TYPE to (HttpStatusCode.UnsupportedMediaType to "UNSUPPORTED_MEDIA_TYPE"),
            UploadRejection.IMAGE_TOO_LARGE to (HttpStatusCode.PayloadTooLarge to "IMAGE_TOO_LARGE"),
            UploadRejection.REQUEST_TOO_LARGE to (HttpStatusCode.PayloadTooLarge to "REQUEST_TOO_LARGE"),
            UploadRejection.IMAGE_DIMENSIONS_TOO_LARGE to
                (HttpStatusCode.PayloadTooLarge to "IMAGE_DIMENSIONS_TOO_LARGE"),
            UploadRejection.MEDIA_TYPE_MISMATCH to (HttpStatusCode.UnsupportedMediaType to "MEDIA_TYPE_MISMATCH")
        )
        assertEquals(
            UploadRejection.entries.size,
            expected.size,
            "a new UploadRejection constant must gain its own row here"
        )

        expected.forEach { (rejection, statusAndLiteral) ->
            val (status, literal) = statusAndLiteral
            val store = FakeStore()
            testApplication {
                installRoute(
                    store = store,
                    uploadReader = CountingUploadReader(ReceiptUploadResult.Rejected(rejection))
                )

                postAnalysis().assertJson(
                    status,
                    """{"envelopeVersion":"v1","requestId":"$validKey","error":"$literal"}"""
                )
            }
            // A rejected upload never reserves anything.
            assertEquals(0, store.startCalls.get(), "rejection $rejection must not reach the store")
        }
    }

    // ------------------------------------------------------------------------------------
    // Ingestion failure -- 500, or 413 when the ceiling was already proven
    // ------------------------------------------------------------------------------------

    @Test
    fun `a genuine ingestion failure is a controlled 500 that never exposes the cause`() = testApplication {
        installRoute(uploadReader = CountingUploadReader(ReceiptUploadResult.IngestionFailed))

        postAnalysis().assertJson(
            HttpStatusCode.InternalServerError,
            """{"envelopeVersion":"v1","requestId":"$validKey","error":"INTERNAL_ERROR"}"""
        )
    }

    @Test
    fun `an ingestion failure after the ceiling was proven is 413, never 500`() = testApplication {
        installRoute(uploadReader = CountingUploadReader(ReceiptUploadResult.IngestionFailedAfterOverflow))

        postAnalysis().assertJson(
            HttpStatusCode.PayloadTooLarge,
            """{"envelopeVersion":"v1","requestId":"$validKey","error":"REQUEST_TOO_LARGE"}"""
        )
    }

    // ------------------------------------------------------------------------------------
    // Rows 1-10 -- the use case's five results, invoked exactly once
    // ------------------------------------------------------------------------------------

    @Test
    fun `a new operation is 202 RECEIVED and the use case is invoked exactly once`() = testApplication {
        val store = FakeStore(
            startOutcome = StartOutcome.Accepted(operation(ReceiptAnalysisOperationLifecycle.Received), isNew = true)
        )
        val provider = CountingProviderPort()
        installRoute(store = store, providerPort = provider)

        postAnalysis().assertJson(
            HttpStatusCode.Accepted,
            """{"envelopeVersion":"v1","requestId":"$validKey","status":"RECEIVED"}"""
        )
        assertEquals(1, store.startCalls.get(), "the use case must be invoked exactly once")
        // claimInvocation returns AlreadyClaimedOrResolved in this fake, so the provider is never
        // reached -- proving the route itself never calls it.
        assertEquals(0, provider.calls.get())
    }

    // ------------------------------------------------------------------------------------
    // The POST's own status selection: 202 while the work is open, 200 once it has resolved
    // (StartReceiptAnalysisRoute.statusFor, decided by the owner in 262). The GET cannot stand in
    // for this -- it answers 200 for every found operation, whatever the lifecycle. These three
    // replay the same Idempotency-Key on an operation that already exists (isNew = false); the
    // envelope is the one the GET already covers, so what is pinned here is the status code.
    // ------------------------------------------------------------------------------------

    @Test
    fun `a replayed key on a claimed operation is 202 PROCESSING`() = testApplication {
        installRoute(
            store = FakeStore(
                startOutcome = StartOutcome.Accepted(
                    operation(ReceiptAnalysisOperationLifecycle.InvocationClaimed),
                    isNew = false
                )
            )
        )

        postAnalysis().assertJson(
            HttpStatusCode.Accepted,
            """{"envelopeVersion":"v1","requestId":"$validKey","status":"PROCESSING"}"""
        )
    }

    @Test
    fun `a replayed key on a reconciling operation is 202 RECONCILING`() = testApplication {
        installRoute(
            store = FakeStore(
                startOutcome = StartOutcome.Accepted(
                    operation(ReceiptAnalysisOperationLifecycle.Reconciling),
                    isNew = false
                )
            )
        )

        postAnalysis().assertJson(
            HttpStatusCode.Accepted,
            """{"envelopeVersion":"v1","requestId":"$validKey","status":"RECONCILING"}"""
        )
    }

    @Test
    fun `a replayed key on a resolved operation is 200, not 202`() = testApplication {
        installRoute(
            store = FakeStore(
                startOutcome = StartOutcome.Accepted(
                    operation(
                        ReceiptAnalysisOperationLifecycle.Terminal(ReceiptAnalysisOperationResult.Failed)
                    ),
                    isNew = false
                )
            )
        )

        postAnalysis().assertJson(
            HttpStatusCode.OK,
            """{"envelopeVersion":"v1","requestId":"$validKey","status":"FAILED"}"""
        )
    }

    @Test
    fun `a hash conflict is 409 with its own literal`() = testApplication {
        installRoute(store = FakeStore(startOutcome = StartOutcome.HashConflict))

        postAnalysis().assertJson(
            HttpStatusCode.Conflict,
            """{"envelopeVersion":"v1","requestId":"$validKey","error":"REQUEST_ID_CONFLICT"}"""
        )
    }

    @Test
    fun `insufficient credits is 402 with its own literal`() = testApplication {
        installRoute(store = FakeStore(startOutcome = StartOutcome.InsufficientCredits))

        postAnalysis().assertJson(
            HttpStatusCode.PaymentRequired,
            """{"envelopeVersion":"v1","requestId":"$validKey","error":"INSUFFICIENT_CREDITS"}"""
        )
    }

    @Test
    fun `a tombstoned operation is 410, reusing the literal the GET already emits`() = testApplication {
        installRoute(store = FakeStore(startOutcome = StartOutcome.Tombstoned))

        postAnalysis().assertJson(
            HttpStatusCode.Gone,
            """{"envelopeVersion":"v1","requestId":"$validKey","error":"GONE"}"""
        )
    }

    @Test
    fun `an unexpected store exception is a controlled 500 that never leaks the driver message`() =
        testApplication {
            val syntheticUrl = "jdbc:postgresql://secret-host:5432/db?user=admin&password=hunter2"
            installRoute(
                store = object : ReceiptAnalysisOperationStore by FakeStore() {
                    override suspend fun startOrGetExisting(
                        command: StartReceiptAnalysisOperationCommand
                    ): StartOutcome = throw IllegalStateException("connection refused for $syntheticUrl")
                }
            )

            val response = postAnalysis()

            response.assertJson(
                HttpStatusCode.InternalServerError,
                """{"envelopeVersion":"v1","requestId":"$validKey","error":"INTERNAL_ERROR"}"""
            )
            assertTrue("secret-host" !in response.bodyAsText())
            assertTrue("hunter2" !in response.bodyAsText())
        }

    // ------------------------------------------------------------------------------------
    // The advisory lookup is advisory: its failure never fails the request
    // ------------------------------------------------------------------------------------

    @Test
    fun `a failing advisory lookup falls back to the new-analysis branch instead of failing`() =
        testApplication {
            val admission = CountingAdmission()
            val delegate = FakeStore(
                startOutcome = StartOutcome.Accepted(
                    operation(ReceiptAnalysisOperationLifecycle.Received),
                    isNew = true
                )
            )
            installRoute(
                // Only the *advisory* read fails; the use case's own reload still works, which is
                // what makes this a test of the route's fallback rather than of a broken store.
                store = object : ReceiptAnalysisOperationStore by delegate {
                    private val calls = AtomicInteger(0)
                    override suspend fun findByRequestId(userId: UserId, requestId: RequestId): RequestLookup =
                        if (calls.getAndIncrement() == 0) {
                            throw IllegalStateException("advisory read failed")
                        } else {
                            delegate.findByRequestId(userId, requestId)
                        }
                },
                admission = admission
            )

            postAnalysis().assertJson(
                HttpStatusCode.Accepted,
                """{"envelopeVersion":"v1","requestId":"$validKey","status":"RECEIVED"}"""
            )
            // Treated as "probably new": layer 2, not layer 1.
            assertEquals(0, admission.existingCalls.get())
            assertEquals(1, admission.newCalls.get())
        }
}
