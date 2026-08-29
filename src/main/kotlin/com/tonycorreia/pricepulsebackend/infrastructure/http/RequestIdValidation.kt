package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.RequestId

private const val MAX_REQUEST_ID_LENGTH = 128
private val REQUEST_ID_CHARSET = Regex("^[A-Za-z0-9_-]+$")

/**
 * Edge validation, exactly the contract closed in receiptanalysis-slice-report.md 6.10.52.3:
 * non-blank, at most [MAX_REQUEST_ID_LENGTH] characters, and only `[A-Za-z0-9_-]`.
 *
 * Length and charset are an HTTP-boundary responsibility, never a domain rule: [RequestId] keeps
 * its single invariant (non-blank) and stays provider- and transport-neutral.
 *
 * Returns `null` instead of throwing, so no exception message can carry the rejected value -- the
 * caller answers 400 with a fixed body that never echoes it back.
 */
internal fun parseRequestId(raw: String?): RequestId? {
    if (raw.isNullOrEmpty() || raw.length > MAX_REQUEST_ID_LENGTH) return null
    if (!REQUEST_ID_CHARSET.matches(raw)) return null
    return RequestId(raw)
}
