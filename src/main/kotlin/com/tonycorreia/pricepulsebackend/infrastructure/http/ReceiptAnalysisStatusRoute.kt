package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationStore
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestLookup
import com.tonycorreia.pricepulsebackend.infrastructure.firebase.FirebaseIdTokenVerifier
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.coroutines.cancellation.CancellationException

/**
 * `GET /v1/receipt-analyses/{requestId}` -- the first protected endpoint.
 *
 * Reads only: the single store call is [ReceiptAnalysisOperationStore.findByRequestId]. No
 * reservation, debit, claim or provider call exists on this path.
 *
 * Order is the one fixed by receiptanalysis-slice-report.md 6.10.7: authenticate, then apply layer
 * 3, then validate the `requestId`, and only then read. An unauthenticated caller therefore never
 * reaches the store nor the per-user limiter.
 */
fun Route.receiptAnalysisStatusRoute(
    verifier: FirebaseIdTokenVerifier,
    store: ReceiptAnalysisOperationStore,
    rateLimiter: PollingRateLimiter
) {
    get("/v1/receipt-analyses/{requestId}") {
        val userId = call.authenticatedUserId(verifier)
        if (userId == null) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respondJson(HttpStatusCode.Unauthorized, ReceiptAnalysisStatusResponses.unauthorized())
            return@get
        }

        when (val decision = rateLimiter.acquire(userId)) {
            is PollingRateLimitDecision.Allowed -> Unit
            is PollingRateLimitDecision.Limited -> {
                // The captured decision feeds both the header and the body -- never a second query.
                call.response.header(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
                call.respondJson(
                    HttpStatusCode.TooManyRequests,
                    ReceiptAnalysisStatusResponses.rateLimited(decision.retryAfterSeconds)
                )
                return@get
            }
        }

        val requestId = parseRequestId(call.parameters["requestId"])
        if (requestId == null) {
            call.respondJson(HttpStatusCode.BadRequest, ReceiptAnalysisStatusResponses.invalidRequestId())
            return@get
        }

        // Only the store call is guarded. PostgresReceiptAnalysisOperationStore.withConnection
        // rolls back and rethrows the driver's own exception, whose message can carry the host,
        // user or a fragment of the connection URL; it is discarded here, never read, logged,
        // chained or answered with. Cancellation is rethrown before any of that, so a cancelled
        // request never turns into a 500.
        val lookup = try {
            store.findByRequestId(userId, requestId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            call.respondJson(
                HttpStatusCode.InternalServerError,
                ReceiptAnalysisStatusResponses.internalError(requestId)
            )
            return@get
        }

        when (lookup) {
            // A requestId belonging to another user is indistinguishable from one that never
            // existed: the port is keyed by (userId, requestId) and the userId always comes from
            // the verified token, never from the path.
            is RequestLookup.NotFound ->
                call.respondJson(HttpStatusCode.NotFound, ReceiptAnalysisStatusResponses.notFound(requestId))
            is RequestLookup.Tombstoned ->
                call.respondJson(HttpStatusCode.Gone, ReceiptAnalysisStatusResponses.gone(requestId))
            is RequestLookup.Found ->
                call.respondJson(HttpStatusCode.OK, ReceiptAnalysisStatusResponses.found(lookup.operation))
        }
    }
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) {
    respondText(body, ContentType.Application.Json, status)
}
