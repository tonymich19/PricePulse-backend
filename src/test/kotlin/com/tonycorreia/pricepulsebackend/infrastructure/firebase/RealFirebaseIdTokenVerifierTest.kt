package com.tonycorreia.pricepulsebackend.infrastructure.firebase

import com.google.firebase.FirebaseApp
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins **only** the defensive branch where no default `FirebaseApp` exists in the process
 * (receiptanalysis-slice-report.md 6.10.76).
 *
 * `verify` obtains `FirebaseAuth.getInstance()` **before** the token reaches `verifyIdToken`, and
 * `getInstance()` delegates first to `FirebaseApp.getInstance()`. With no app initialized, that
 * call throws and the token is never used -- so this test proves that the configuration failure
 * collapses to `null` instead of propagating to `authenticatedUserId`, which deliberately does not
 * catch verifier exceptions.
 *
 * It does **not** prove the verifier's broad failure contract. Token-verification failures --
 * malformed signature, expiry, wrong project, or any other error raised by an initialized SDK --
 * are **not** covered here: a regression that caught only the configuration exception would still
 * pass this test. That limitation is recorded, not worked around; proving more would require either
 * a production seam (rejected, see 6.10.69) or a real initialized `FirebaseApp`.
 */
class RealFirebaseIdTokenVerifierTest {

    @Test
    fun `verify returns null instead of throwing when no FirebaseApp is initialized`() {
        assertTrue(
            FirebaseApp.getApps().isEmpty(),
            "precondition: this test only means what it claims while no FirebaseApp exists; if a " +
                "future suite initializes one, this assertion fails instead of silently exercising " +
                "a different branch"
        )

        assertNull(RealFirebaseIdTokenVerifier().verify("not-a-jwt"))
    }
}
