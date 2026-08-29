package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationLifecycle
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationStore
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestId
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestLookup
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartReceiptAnalysisCommand
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartReceiptAnalysisResult
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.StartReceiptAnalysisUseCase
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId
import com.tonycorreia.pricepulsebackend.infrastructure.firebase.FirebaseIdTokenVerifier
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.coroutines.cancellation.CancellationException

/** The header that carries the idempotency key; it maps directly onto [RequestId]. */
private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/**
 * `POST /v1/receipt-analyses` -- the first endpoint that makes the analysis flow usable.
 *
 * The route stays on the HTTP boundary: after admission it builds a [StartReceiptAnalysisCommand]
 * and invokes [StartReceiptAnalysisUseCase] **exactly once**. The sequence
 * `startOrGetExisting -> claimInvocation -> provider -> applyOutcome` belongs to the use case and is
 * never executed, observed or reordered here. The route also never computes a content hash: it
 * builds only a `PreparedReceiptImage`, and the store derives hash, size and MIME from those bytes.
 *
 * Admission order is the one fixed in receiptanalysis-slice-report.md 6.10.7/6.10.62:
 * authenticate -> validate the key -> advisory lookup + layer 1 -> read and validate the upload ->
 * layer 2 -> invoke the use case.
 */
fun Route.startReceiptAnalysisRoute(
    verifier: FirebaseIdTokenVerifier,
    store: ReceiptAnalysisOperationStore,
    useCase: StartReceiptAnalysisUseCase,
    admission: AdmissionRateLimiter,
    uploadReader: ReceiptUploadReader
) {
    post("/v1/receipt-analyses") {
        // ---------------------------------------------------------------------------------
        // Cancellation has TWO responsibilities, split by who can actually discharge them:
        //
        //   * KtorReceiptUploadReader.read owns the cleanup (S5-C). It creates the
        //     CountingMultipartSource, which never leaves that function, so it is the only
        //     scope able to cancel the source and join the relay -- and it does, covering part
        //     reading, the handoff join and the post-handoff count.
        //   * This catch owns only the response: it guarantees that no new response is started.
        //     It cannot clean up the upload, and deliberately does not claim to.
        //
        // No stage absorbs or transforms cancellation: every catch of Exception is preceded by a
        // catch of CancellationException that rethrows.
        // ---------------------------------------------------------------------------------
        try {
            // Step 1 -- authenticate. An unauthenticated caller never reaches a limiter or the store.
            val userId = call.authenticatedUserId(verifier)
            if (userId == null) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                call.respondJson(HttpStatusCode.Unauthorized, ReceiptAnalysisStatusResponses.unauthorized())
                return@post
            }

            // Step 2 -- validate the key. Mandatory here: step 3 and layer 1 are both indexed by
            // (userId, requestId), so neither may run before the key is known to be well formed.
            val requestId = parseRequestId(call.request.headers[IDEMPOTENCY_KEY_HEADER])
            if (requestId == null) {
                call.respondJson(HttpStatusCode.BadRequest, ReceiptAnalysisStatusResponses.invalidRequestId())
                return@post
            }

            // Step 3 -- ADVISORY lookup. It only chooses which limiter layer applies; it is never
            // the authority on idempotency, conflict, reservation or credit. A stale answer is
            // harmless: the use case's atomic call is the only authority.
            val probablyExisting = try {
                store.findByRequestId(userId, requestId) != RequestLookup.NotFound
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The advisory read failing is not a reason to fail the request; treat it as
                // "probably new" and let the use case decide. The store's own errors surface below.
                false
            }

            val admissionDecision = if (probablyExisting) {
                admission.acquireForExisting(userId, requestId) // layer 1
            } else {
                PollingRateLimitDecision.Allowed
            }
            if (admissionDecision is PollingRateLimitDecision.Limited) {
                return@post call.respondRateLimited(requestId, admissionDecision.retryAfterSeconds)
            }

            // Steps 4 and 5 -- read and validate the upload, then build ONLY a PreparedReceiptImage.
            // Identical in both branches, because the use case requires one either way.
            when (val upload = uploadReader.read(call)) {
                is ReceiptUploadResult.Rejected -> return@post call.respondUploadRejected(requestId, upload.rejection)

                ReceiptUploadResult.IngestionFailedAfterOverflow ->
                    // The ceiling was already proven when ingestion failed: the client did exceed
                    // it, and answering 500 would hide that.
                    return@post call.respondUploadRejected(requestId, UploadRejection.REQUEST_TOO_LARGE)

                ReceiptUploadResult.IngestionFailed ->
                    return@post call.respondJson(
                        HttpStatusCode.InternalServerError,
                        ReceiptAnalysisStatusResponses.internalError(requestId)
                    )

                is ReceiptUploadResult.Prepared -> {
                    // Step 6 -- layer 2, only for a probable new analysis.
                    if (!probablyExisting) {
                        val newDecision = admission.acquireForNew(userId)
                        if (newDecision is PollingRateLimitDecision.Limited) {
                            return@post call.respondRateLimited(requestId, newDecision.retryAfterSeconds)
                        }
                    }

                    // Step 7 -- ONE invocation of the use case. Its internals stay its own.
                    val result = try {
                        useCase(StartReceiptAnalysisCommand(userId, requestId, upload.image))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // The driver's own exception can carry the host, user or a fragment of the
                        // connection URL; it is discarded here, never read, logged, chained or
                        // answered with -- the same discipline the GET already applies.
                        return@post call.respondJson(
                            HttpStatusCode.InternalServerError,
                            ReceiptAnalysisStatusResponses.internalError(requestId)
                        )
                    }
                    call.respondToResult(requestId, result)
                }
            }
        } catch (cancellation: CancellationException) {
            // The route's half of the cancelled path, and the only thing it can actually promise:
            // no response is started. Whatever already reached the client is irreversible.
            //
            // It deliberately does NOT claim to clean up the upload. The CountingMultipartSource
            // never leaves KtorReceiptUploadReader.read, so that function owns S5-C and performs
            // the cancellation cleanup itself; claiming it here would be a promise this scope has
            // no reference to keep -- which is exactly what round 292 got wrong.
            throw cancellation
        }
    }
}

private suspend fun ApplicationCall.respondToResult(
    requestId: RequestId,
    result: StartReceiptAnalysisResult
) = when (result) {
    // Rows 1-6: the same envelope the GET already produces, from the same builder.
    is StartReceiptAnalysisResult.Resolved ->
        respondJson(statusFor(result), ReceiptAnalysisStatusResponses.found(result.operation))

    StartReceiptAnalysisResult.HashConflict ->
        respondJson(HttpStatusCode.Conflict, StartReceiptAnalysisResponses.requestIdConflict(requestId))

    StartReceiptAnalysisResult.InsufficientCredits ->
        respondJson(HttpStatusCode.PaymentRequired, StartReceiptAnalysisResponses.insufficientCredits(requestId))

    StartReceiptAnalysisResult.Tombstoned ->
        respondJson(HttpStatusCode.Gone, ReceiptAnalysisStatusResponses.gone(requestId))

    StartReceiptAnalysisResult.OperationUnexpectedlyMissing ->
        respondJson(HttpStatusCode.InternalServerError, ReceiptAnalysisStatusResponses.internalError(requestId))
}

/** `202` while the work is still open, `200` once it has resolved -- decided by the owner in 262. */
private fun statusFor(resolved: StartReceiptAnalysisResult.Resolved): HttpStatusCode =
    when (resolved.operation.lifecycle) {
        is ReceiptAnalysisOperationLifecycle.Received,
        is ReceiptAnalysisOperationLifecycle.InvocationClaimed,
        is ReceiptAnalysisOperationLifecycle.Reconciling -> HttpStatusCode.Accepted

        is ReceiptAnalysisOperationLifecycle.Terminal -> HttpStatusCode.OK
    }

private suspend fun ApplicationCall.respondRateLimited(requestId: RequestId, retryAfterSeconds: Int) {
    // The captured decision feeds both the header and the body -- never a second query.
    response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
    respondJson(
        HttpStatusCode.TooManyRequests,
        StartReceiptAnalysisResponses.rateLimited(requestId, retryAfterSeconds)
    )
}

private suspend fun ApplicationCall.respondUploadRejected(requestId: RequestId, rejection: UploadRejection) {
    val status = when (rejection) {
        UploadRejection.MALFORMED_UPLOAD -> HttpStatusCode.BadRequest
        UploadRejection.UNSUPPORTED_MEDIA_TYPE -> HttpStatusCode.UnsupportedMediaType
        UploadRejection.IMAGE_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
        UploadRejection.REQUEST_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
        UploadRejection.IMAGE_DIMENSIONS_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
        UploadRejection.MEDIA_TYPE_MISMATCH -> HttpStatusCode.UnsupportedMediaType
    }
    respondJson(status, StartReceiptAnalysisResponses.uploadRejected(requestId, rejection))
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) {
    respondText(body, ContentType.Application.Json, status)
}
