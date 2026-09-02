package com.tonycorreia.pricepulsebackend.infrastructure.http

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The counting source driven directly, without HTTP: the test owns the source channel, so it can
 * hold bytes back, fail a read, or cancel the caller mid-flight -- none of which the Ktor test
 * client can express through a normal request.
 *
 * Every test runs under a timeout, so a hang -- the failure mode that made the round-284 design
 * unimplementable -- fails loudly instead of passing unnoticed.
 */
class CountingMultipartSourceTest {

    private fun bodyOf(vararg chunks: ByteArray): ByteChannel = ByteChannel(autoFlush = true).also { channel ->
        CoroutineScope(Job()).launch {
            runCatching {
                chunks.forEach { channel.writeFully(it) }
                channel.close()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The strict read ceiling
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a body at exactly the envelope ceiling does not arm the overflow latch`() = runBlocking {
        withTimeout(30_000) {
            val body = bodyOf(ByteArray(MAX_ENVELOPE_BYTES.toInt()) { 0x41 })
            val source = CountingMultipartSource.start(this, body, coroutineContext)

            source.finishAndCount(countRemaining = true)

            assertFalse(source.envelopeOverflowed, "the ceiling is inclusive: exactly at it is not over it")
            assertEquals(MAX_ENVELOPE_BYTES, source.totalBytesRead)
            assertTrue(source.relayCompleted)
        }
    }

    @Test
    fun `a body above the ceiling arms the latch and stops at exactly one proof byte`() = runBlocking {
        withTimeout(30_000) {
            // Far more than the ceiling: the point is that we stop after the proof byte and never
            // consume the rest, so the count is exact rather than "somewhere past the limit".
            val body = bodyOf(ByteArray((MAX_ENVELOPE_BYTES + 500_000).toInt()) { 0x41 })
            val source = CountingMultipartSource.start(this, body, coroutineContext)

            source.finishAndCount(countRemaining = true)

            assertTrue(source.envelopeOverflowed)
            assertEquals(
                MAX_ENVELOPE_BYTES + 1,
                source.totalBytesRead,
                "the read must be clamped to ceiling+1, never over-consumed by a buffer's worth"
            )
        }
    }

    @Test
    fun `bytes arriving after the parser stopped are still counted, without a second reader`() =
        runBlocking {
            withTimeout(30_000) {
                // Nothing consumes the relay's output here, which is exactly the post-boundary
                // situation: the count must still come from the relay/route handoff.
                val body = bodyOf(
                    ByteArray(1024) { 0x41 },
                    ByteArray((MAX_ENVELOPE_BYTES + 10).toInt()) { 0x42 }
                )
                val source = CountingMultipartSource.start(this, body, coroutineContext)

                source.finishAndCount(countRemaining = true)

                assertTrue(source.envelopeOverflowed)
                assertTrue(source.relayCompleted)
            }
        }

    @Test
    fun `skipping the count leaves the latch alone and still terminates the relay`() = runBlocking {
        withTimeout(30_000) {
            val body = bodyOf(ByteArray((MAX_ENVELOPE_BYTES + 10).toInt()) { 0x41 })
            val source = CountingMultipartSource.start(this, body, coroutineContext)

            // An earlier rejection outranks 4d, so the caller asks not to count.
            source.finishAndCount(countRemaining = false)

            assertTrue(source.relayCompleted, "no coroutine may be left behind on the skip path")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Genuine ingestion failure vs. our own cancellation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a read failure before any handoff is recorded as a genuine ingestion failure`() = runBlocking {
        withTimeout(30_000) {
            val body = ByteChannel(autoFlush = true)
            CoroutineScope(Job()).launch {
                runCatching {
                body.writeFully(ByteArray(128) { 0x41 })
                body.flush()
                delay(50)
                body.cancel(java.io.IOException("client connection dropped"))
                }
            }
            val source = CountingMultipartSource.start(this, body, coroutineContext)

            source.finishAndCount(countRemaining = true)

            assertTrue(source.ingestionFailed, "an upstream I/O failure must be recorded, not swallowed")
            assertFalse(
                source.ingestionFailedAfterOverflowProven,
                "the ceiling was never proven, so this resolves as 500 and not 413"
            )
            assertTrue(source.relayCompleted)
        }
    }

    @Test
    fun `our own cancellation of the source is never recorded as a failure`() = runBlocking {
        withTimeout(30_000) {
            val body = ByteChannel(autoFlush = true) // never closed: the relay would wait forever
            CoroutineScope(Job()).launch { runCatching { body.writeFully(ByteArray(64) { 0x41 }) } }
            val source = CountingMultipartSource.start(this, body, coroutineContext)

            // The rejection path cancels the source itself; that must not look like an I/O failure,
            // otherwise a 400/415 already decided would be overwritten by a 500.
            source.finishAndCount(countRemaining = false)

            assertFalse(source.ingestionFailed)
            assertTrue(source.relayCompleted)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cancellation -- the boundary the review made mandatory
    // ---------------------------------------------------------------------------------------

    @Test
    fun `cancelling the caller during the post-handoff count propagates and leaves nothing running`() =
        runBlocking {
            withTimeout(30_000) {
                val entries = AtomicInteger(0)
                // Never closed and never fed further: the count blocks, so the cancellation lands
                // exactly inside the post-handoff read.
                val body = ByteChannel(autoFlush = true)
                CoroutineScope(Job()).launch { runCatching { body.writeFully(ByteArray(32) { 0x41 }) } }

                lateinit var source: CountingMultipartSource
                val caller = async {
                    source = CountingMultipartSource.start(this, body, coroutineContext)
                    try {
                        source.finishAndCount(countRemaining = true)
                    } catch (cancellation: CancellationException) {
                        // Stands in for the owner of the cleanup, which in production is
                        // KtorReceiptUploadReader.read: exactly one entry into the cancelled path,
                        // which cleans up and rethrows the same exception.
                        entries.incrementAndGet()
                        source.cancelAll()
                        throw cancellation
                    }
                }

                delay(100) // let the count block on the open channel
                caller.cancelAndJoin()

                assertEquals(1, entries.get(), "the cancelled path must be entered exactly once")
                assertTrue(source.relayCompleted, "no coroutine may be left hanging after cancellation")
                assertFalse(source.ingestionFailed, "cancellation is never recorded as an I/O failure")
            }
        }

    @Test
    fun `cancelAll is idempotent and safe after the drain already cancelled the source`() = runBlocking {
        withTimeout(30_000) {
            val body = bodyOf(ByteArray(256) { 0x41 })
            val source = CountingMultipartSource.start(this, body, coroutineContext)

            source.finishAndCount(countRemaining = true) // its finally already cancelled `raw`
            source.cancelAll()
            source.cancelAll() // and again: every operation is idempotent by design

            assertTrue(source.relayCompleted)
        }
    }

    @Test
    fun `a cancellation raised while reading is never converted into an ingestion failure`() =
        runBlocking {
            withTimeout(30_000) {
                val body = ByteChannel(autoFlush = true)
                CoroutineScope(Job()).launch { runCatching { body.writeFully(ByteArray(16) { 0x41 }) } }

                lateinit var source: CountingMultipartSource
                val caller = async {
                    source = CountingMultipartSource.start(this, body, coroutineContext)
                    source.finishAndCount(countRemaining = true)
                }
                delay(100)
                caller.cancelAndJoin()

                assertFalse(source.ingestionFailed)
                source.cancelAll()
                assertTrue(source.relayCompleted)
            }
        }

    @Test
    fun `cleanup completes even though the caller's context is already cancelled`() = runBlocking {
        withTimeout(30_000) {
            val body = ByteChannel(autoFlush = true)
            CoroutineScope(Job()).launch { runCatching { body.writeFully(ByteArray(16) { 0x41 }) } }

            // Set only after cancelAll() has run to completion inside an already-cancelled context.
            // Round 284's design could not do this: its cleanup sat behind suspension points that
            // throw immediately once cancelled, so it never ran at all.
            val cleanupFinished = AtomicInteger(0)

            lateinit var source: CountingMultipartSource
            val caller = async {
                source = CountingMultipartSource.start(this, body, coroutineContext)
                try {
                    source.finishAndCount(countRemaining = true)
                } catch (cancellation: CancellationException) {
                    source.cancelAll()
                    cleanupFinished.incrementAndGet()
                    throw cancellation
                }
            }
            delay(100)
            caller.cancelAndJoin()

            assertEquals(1, cleanupFinished.get(), "cleanup must run to completion under cancellation")
            assertTrue(source.relayCompleted)
        }
    }

}
