package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.FailedNoProviderReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderCallFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderRejectionReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ProviderTerminalFailureReason
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperation
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationLifecycle
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.ReceiptAnalysisOperationResult
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestId

/**
 * Every body of `GET /v1/receipt-analyses/{requestId}` is built here -- all twelve of them,
 * including [internalError], so no path can invent an envelope of its own
 * (receiptanalysis-slice-report.md 6.10.60).
 *
 * `requestId` appears if and only if it has passed edge validation. That is the rule, not a list of
 * rows: [unauthorized], [rateLimited] and [invalidRequestId] answer before or during validation and
 * so carry none, while every later body carries the validated value.
 */
internal object ReceiptAnalysisStatusResponses {

    const val ENVELOPE_VERSION = "v1"

    private val MAPPER = ObjectMapper()

    /**
     * A compile-time constant, never assembled by the mapper: byte-for-byte identity across all six
     * authentication failure modes is then structural rather than incidental, and there is no
     * variable field that could reintroduce a difference.
     */
    const val UNAUTHORIZED_BODY = """{"envelopeVersion":"v1","error":"UNAUTHORIZED"}"""

    const val INVALID_REQUEST_ID_BODY = """{"envelopeVersion":"v1","error":"INVALID_REQUEST_ID"}"""

    fun unauthorized(): String = UNAUTHORIZED_BODY

    fun invalidRequestId(): String = INVALID_REQUEST_ID_BODY

    fun rateLimited(retryAfterSeconds: Int): String =
        MAPPER.createObjectNode().apply {
            put("envelopeVersion", ENVELOPE_VERSION)
            put("error", "RATE_LIMITED")
            put("retryAfter", retryAfterSeconds)
        }.let(MAPPER::writeValueAsString)

    fun notFound(requestId: RequestId): String = errorWithRequestId(requestId, "NOT_FOUND")

    fun gone(requestId: RequestId): String = errorWithRequestId(requestId, "GONE")

    /** The controlled answer to an unexpected store failure -- never the driver's own words. */
    fun internalError(requestId: RequestId): String = errorWithRequestId(requestId, "INTERNAL_ERROR")

    fun found(operation: ReceiptAnalysisOperation): String {
        val node = MAPPER.createObjectNode()
        node.put("envelopeVersion", ENVELOPE_VERSION)
        node.put("requestId", operation.requestId.value)
        when (val lifecycle = operation.lifecycle) {
            is ReceiptAnalysisOperationLifecycle.Received -> node.put("status", "RECEIVED")
            is ReceiptAnalysisOperationLifecycle.InvocationClaimed -> node.put("status", "PROCESSING")
            is ReceiptAnalysisOperationLifecycle.Reconciling -> node.put("status", "RECONCILING")
            is ReceiptAnalysisOperationLifecycle.Terminal -> when (val result = lifecycle.result) {
                is ReceiptAnalysisOperationResult.Succeeded -> {
                    node.put("status", "SUCCEEDED")
                    node.set<com.fasterxml.jackson.databind.JsonNode>(
                        "document",
                        MAPPER.readTree(result.document.serialize())
                    )
                }
                // FAILED carries no reason in V1: the domain has exactly one cause for it, so a
                // constant field would tell the client nothing (6.10.60, decision of round 244).
                is ReceiptAnalysisOperationResult.Failed -> node.put("status", "FAILED")
                is ReceiptAnalysisOperationResult.FailedNoProvider -> {
                    node.put("status", "FAILED_NO_PROVIDER")
                    node.put("reason", wireReason(result.reason))
                }
            }
        }
        return MAPPER.writeValueAsString(node)
    }

    private fun errorWithRequestId(requestId: RequestId, error: String): String =
        MAPPER.createObjectNode().apply {
            put("envelopeVersion", ENVELOPE_VERSION)
            put("requestId", requestId.value)
            put("error", error)
        }.let(MAPPER::writeValueAsString)

    /**
     * Written out literally, never `.name` or `ordinal`: the wire vocabulary must survive a rename
     * or reordering of the domain enums without silently changing what clients receive.
     *
     * Deliberately exhaustive with no `else`: a thirteenth reason added to any of the three enums
     * becomes a compile error here, instead of reaching a client as a runtime failure.
     */
    private fun wireReason(reason: FailedNoProviderReason): String = when (reason) {
        ProviderCallFailureReason.REQUEST_CONSTRUCTION_FAILED -> "REQUEST_CONSTRUCTION_FAILED"
        ProviderCallFailureReason.PROVIDER_CREDENTIAL_UNAVAILABLE -> "PROVIDER_CREDENTIAL_UNAVAILABLE"
        ProviderRejectionReason.MALFORMED_REQUEST -> "MALFORMED_REQUEST"
        ProviderRejectionReason.AUTHENTICATION_REJECTED -> "AUTHENTICATION_REJECTED"
        ProviderRejectionReason.ACCESS_FORBIDDEN -> "ACCESS_FORBIDDEN"
        ProviderTerminalFailureReason.RETRY_EXHAUSTED -> "RETRY_EXHAUSTED"
        ProviderTerminalFailureReason.BILLING_OR_QUOTA_EXHAUSTED -> "BILLING_OR_QUOTA_EXHAUSTED"
        ProviderTerminalFailureReason.UNRECOGNIZED_RESPONSE -> "UNRECOGNIZED_RESPONSE"
        ProviderTerminalFailureReason.RESPONSE_FAILED -> "RESPONSE_FAILED"
        ProviderTerminalFailureReason.RESPONSE_CANCELLED -> "RESPONSE_CANCELLED"
        ProviderTerminalFailureReason.RATE_LIMITED -> "RATE_LIMITED"
        ProviderTerminalFailureReason.TRANSIENT_PROVIDER_FAILURE -> "TRANSIENT_PROVIDER_FAILURE"
    }
}
