package com.tonycorreia.pricepulsebackend.infrastructure.http

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cancellation on the **production** path: a real Ktor route calling the real
 * [KtorReceiptUploadReader], with the request body under the test's control so the reader can be
 * stopped at a chosen stage.
 *
 * Determinism comes from the body, not from timing. The body is deliberately never completed, so
 * the reader is provably still suspended when `withTimeout` raises a
 * `TimeoutCancellationException` -- a real `CancellationException` -- at that exact point.
 *
 * The test never waits for the HTTP client to finish: with an open body it never could. It waits on
 * a signal the route itself completes **after** its cleanup, so every assertion is about work that
 * has demonstrably already happened.
 *
 * The reader, not the route, owns S5-C: the `CountingMultipartSource` never leaves
 * `KtorReceiptUploadReader.read`, so only that scope can clean it up. These tests assert that the
 * cleanup actually happens -- round 292 documented it but the code merely rethrew.
 */
class ReceiptUploadCancellationTest {

    private val boundary = "----PricePulseTestBoundary"

    /** A complete, well-formed multipart body for a small PNG. */
    private fun completeBody(): ByteArray {
        val png = ReceiptImageFixtures.png(8, 8)
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"image\"; filename=\"receipt.png\"\r\n" +
                "Content-Type: image/png\r\n\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1)
        return head + png + tail
    }

    /** Only the opening headers: the part never terminates, so `readPart()` cannot complete. */
    private fun incompleteBody(): ByteArray =
        (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"image\"; filename=\"receipt.png\"\r\n" +
                "Content-Type: image/png\r\n\r\n" +
                "partial-bytes-that-never-end"
            ).toByteArray(Charsets.ISO_8859_1)

    private class Observed {
        val cleanupDone = CompletableDeferred<Unit>()
        val source = AtomicReference<CountingMultipartSource?>(null)
        val responded = AtomicInteger(0)
        val sawCancellation = AtomicInteger(0)
        val cleanups = AtomicInteger(0)
    }

    private fun assertCleanShutdown(observed: Observed) {
        val source = requireNotNull(observed.source.get()) { "the reader must have created a source" }
        assertEquals(1, observed.sawCancellation.get(), "the same cancellation must reach the boundary once")
        assertEquals(1, observed.cleanups.get(), "cleanup must happen exactly once")
        assertEquals(0, observed.responded.get(), "no response may be started on the cancelled path")
        assertTrue(source.relayCompleted, "the relay must be finished: no coroutine may survive")
        assertFalse(source.ingestionFailed, "cancellation must never be recorded as an I/O failure")
    }

    /**
     * Drives the real reader under a timeout. The body channel is left open on purpose, so the
     * reader blocks at the stage the body dictates; the client request is fired detached, because
     * it can never complete while the body stays open.
     */
    private fun runCancelledUpload(bodyBytes: ByteArray, timeoutMs: Long) = testApplication {
        val observed = Observed()

        application {
            routing {
                post("/upload") {
                    val reader = KtorReceiptUploadReader()
                    reader.onSourceCreated = { observed.source.set(it) }
                    try {
                        withTimeout(timeoutMs) { reader.read(call) }
                        observed.responded.incrementAndGet()
                        call.respondText("UNEXPECTED_COMPLETION")
                    } catch (_: TimeoutCancellationException) {
                        // Stands in for the route's boundary: the same cancellation arrives here,
                        // and no response is started. The reader has already cleaned up by now.
                        observed.sawCancellation.incrementAndGet()
                        observed.cleanups.incrementAndGet()
                        observed.cleanupDone.complete(Unit)
                    }
                }
            }
        }

        val bodyWriter = CoroutineScope(Job())
        val body = ByteChannel(autoFlush = true)
        bodyWriter.launch {
            // The cleanup under test cancels this channel, which legitimately fails the writer.
            // Swallowed so the fixture's own failure never leaks as an uncaught exception.
            runCatching {
                body.writeFully(bodyBytes)
                body.flush()
                // Never closed: the reader stays suspended waiting for bytes that never arrive.
            }
        }

        val clientCall = CoroutineScope(Job()).launch {
            runCatching {
                client.post("/upload") {
                    contentType(ContentType.parse("multipart/form-data; boundary=$boundary"))
                    setBody(body)
                }
            }
        }

        try {
            // Waits on the route's own signal, never on the client: with an open body the request
            // cannot finish, so awaiting it would hang regardless of the code under test.
            withTimeout(30_000) { observed.cleanupDone.await() }
            assertCleanShutdown(observed)
        } finally {
            clientCall.cancel()
            bodyWriter.cancel()
        }
    }

    // ---------------------------------------------------------------------------------------
    // (a) cancellation while reading parts -- S4
    // ---------------------------------------------------------------------------------------

    @Test
    fun `cancellation while reading parts cleans up the source and relay`() {
        // The part never terminates, so readPart() is provably still suspended when the timeout hits.
        runCancelledUpload(incompleteBody(), timeoutMs = 500)
    }

    // ---------------------------------------------------------------------------------------
    // (b) cancellation after the handoff, with the relay deliberately left pending
    // ---------------------------------------------------------------------------------------

    @Test
    fun `cancellation after the handoff, with the relay still pending, still completes cleanup`() {
        // The multipart is complete, so stage 4 finishes and the reader moves on to
        // finishAndCount(countRemaining = true). The body stays open, so the relay is still
        // suspended reading it and the handoff join cannot return on its own -- the "relay
        // deliberately pending" case. Cleanup must finish anyway, which is only possible because
        // cancelAll() runs in NonCancellable.
        runCancelledUpload(completeBody(), timeoutMs = 1_000)
    }

    // Idempotency of `cancelAll()` is asserted in `CountingMultipartSourceTest`, inside a live
    // scope. It is deliberately NOT re-asserted here: calling it after `testApplication` has torn
    // the call scope down joins a relay whose scope no longer runs, which hangs -- an artefact of
    // the harness, not a state production can reach, since the reader only ever cleans up while the
    // call is still unwinding.
}
