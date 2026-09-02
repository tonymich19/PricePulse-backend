package com.tonycorreia.pricepulsebackend.infrastructure.firebase

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId

/**
 * Injected wherever a future route needs to resolve the caller's identity from a Firebase ID
 * token -- `null` means the token was absent, invalid, expired, malformed, or the SDK itself
 * failed; [verify] never throws. Never a raw `userId` accepted from the request body -- the
 * verified `uid` is the only source, preserving the rule already decided in
 * receiptanalysis-slice-report.md 6.10.2 ("qualquer cliente poderia forjar esse valor").
 */
fun interface FirebaseIdTokenVerifier {
    fun verify(idToken: String): UserId?
}
