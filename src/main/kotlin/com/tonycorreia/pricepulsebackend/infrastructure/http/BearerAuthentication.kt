package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId
import com.tonycorreia.pricepulsebackend.infrastructure.firebase.FirebaseIdTokenVerifier
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

private const val BEARER_PREFIX = "Bearer "

/**
 * Pure and offline: the `Bearer` scheme is case-insensitive (RFC 6750 section 2.1), separated from
 * the token by exactly one space, and the token itself must be non-blank.
 *
 * Returns `null` for every malformed shape rather than throwing -- deliberately, so that no
 * exception message can ever come to hold the raw header.
 */
internal fun extractBearerToken(rawHeader: String?): String? {
    if (rawHeader == null || rawHeader.length <= BEARER_PREFIX.length) return null
    if (!rawHeader.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) return null
    val token = rawHeader.substring(BEARER_PREFIX.length)
    return token.ifBlank { null }
}

/**
 * The single authentication decision of the HTTP boundary: a request either resolves to a [UserId]
 * or it does not.
 *
 * `null` is returned **uniformly** for every failure mode -- header absent, blank, wrong scheme,
 * empty token, and a token the verifier rejects as invalid, expired or badly signed. The caller
 * therefore cannot tell them apart, and so cannot leak that distinction to the client: the contract
 * defines a single reasonless 401 (receiptanalysis-slice-report.md 6.10.16.A/6.10.60).
 *
 * Exceptions from [verifier] are not caught: [FirebaseIdTokenVerifier] returns `UserId?` and never
 * throws by contract, so catching here would mask a real defect in a future implementation.
 */
internal fun ApplicationCall.authenticatedUserId(verifier: FirebaseIdTokenVerifier): UserId? {
    val token = extractBearerToken(request.header(HttpHeaders.Authorization)) ?: return null
    return verifier.verify(token)
}
