package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestId

/**
 * Only the bodies `GET /v1/receipt-analyses/{requestId}` does not already have. Everything the two
 * endpoints share -- the envelope, the six non-terminal/terminal states, the twelve
 * `FAILED_NO_PROVIDER` reasons, `UNAUTHORIZED`, `INVALID_REQUEST_ID`, `GONE`, `INTERNAL_ERROR` --
 * is produced by [ReceiptAnalysisStatusResponses] itself, so the two routes cannot drift apart
 * (receiptanalysis-slice-report.md 6.10.52.1).
 *
 * The one deliberate divergence is [rateLimited]: on the POST both limiter layers fire *after* the
 * `Idempotency-Key` has been validated, so the body carries the `requestId`; on the GET layer 3
 * fires before the path is validated, so it cannot. Same rule, different information available.
 */
internal object StartReceiptAnalysisResponses {

    private val MAPPER = ObjectMapper()

    /** Row 7 -- the key exists with different bytes. */
    fun requestIdConflict(requestId: RequestId): String = error(requestId, "REQUEST_ID_CONFLICT")

    /** Row 8 -- no grant had spare credit. */
    fun insufficientCredits(requestId: RequestId): String = error(requestId, "INSUFFICIENT_CREDITS")

    /** Row 13 -- unlike the GET, this one carries the validated `requestId`. */
    fun rateLimited(requestId: RequestId, retryAfterSeconds: Int): String =
        MAPPER.createObjectNode().apply {
            put("envelopeVersion", ReceiptAnalysisStatusResponses.ENVELOPE_VERSION)
            put("requestId", requestId.value)
            put("error", "RATE_LIMITED")
            put("retryAfter", retryAfterSeconds)
        }.let(MAPPER::writeValueAsString)

    /**
     * Rows 14-19 -- one body per [UploadRejection] constant, never a shared label.
     *
     * Written out literally, never `.name`: the wire vocabulary must survive a rename of the enum
     * without silently changing what clients receive. Exhaustive with no `else`, so a seventh
     * rejection becomes a compile error here instead of reaching a client as a runtime failure --
     * the same discipline [ReceiptAnalysisStatusResponses] already applies to the twelve reasons.
     */
    fun uploadRejected(requestId: RequestId, rejection: UploadRejection): String =
        error(
            requestId,
            when (rejection) {
                UploadRejection.MALFORMED_UPLOAD -> "MALFORMED_UPLOAD"
                UploadRejection.UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE"
                UploadRejection.IMAGE_TOO_LARGE -> "IMAGE_TOO_LARGE"
                UploadRejection.REQUEST_TOO_LARGE -> "REQUEST_TOO_LARGE"
                UploadRejection.IMAGE_DIMENSIONS_TOO_LARGE -> "IMAGE_DIMENSIONS_TOO_LARGE"
                UploadRejection.MEDIA_TYPE_MISMATCH -> "MEDIA_TYPE_MISMATCH"
            }
        )

    private fun error(requestId: RequestId, code: String): String =
        MAPPER.createObjectNode().apply {
            put("envelopeVersion", ReceiptAnalysisStatusResponses.ENVELOPE_VERSION)
            put("requestId", requestId.value)
            put("error", code)
        }.let(MAPPER::writeValueAsString)
}
