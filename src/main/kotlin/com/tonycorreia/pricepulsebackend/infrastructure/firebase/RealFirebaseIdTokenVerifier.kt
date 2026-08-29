package com.tonycorreia.pricepulsebackend.infrastructure.firebase

import com.google.firebase.auth.FirebaseAuth
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration.UserId

/**
 * The only production implementation of [FirebaseIdTokenVerifier] -- delegates to the real
 * Firebase Admin SDK (`FirebaseApp` must already be initialized once in `main()` before this is
 * constructed). Every failure (malformed/expired/invalid-signature/wrong-project token, or any
 * unexpected SDK error) collapses uniformly to `null` -- never the raw token, never the SDK's own
 * exception message (which could echo part of the JWT), ever logged or rethrown. Revocation is
 * never checked here -- out of scope, see receiptanalysis-slice-report.md 6.10.52.2.
 */
class RealFirebaseIdTokenVerifier : FirebaseIdTokenVerifier {
    override fun verify(idToken: String): UserId? = try {
        val decoded = FirebaseAuth.getInstance().verifyIdToken(idToken)
        UserId(decoded.uid)
    } catch (unexpected: Exception) {
        null
    }
}
