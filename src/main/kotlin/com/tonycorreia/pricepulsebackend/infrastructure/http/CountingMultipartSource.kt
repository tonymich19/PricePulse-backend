package com.tonycorreia.pricepulsebackend.infrastructure.http

import io.ktor.http.cio.CIOMultipartDataBase
import io.ktor.http.content.MultiPartData
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.CountedByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.WriterJob
import io.ktor.utils.io.counted
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope

/** Byte ceilings of the upload contract (receiptanalysis-slice-report.md 6.10.52.3.b, round 205). */
internal const val MAX_IMAGE_PART_BYTES = 5_242_880L
internal const val MAX_ENVELOPE_BYTES = 5_505_024L

/** One past the envelope ceiling: reading this many bytes is itself the proof of excess. */
private const val ENVELOPE_PROOF_BYTES = MAX_ENVELOPE_BYTES + 1

private const val RELAY_BUFFER_BYTES = 8192

/**
 * Counts the **raw** request body -- preamble, boundaries, part headers and bodies alike -- beneath
 * the multipart parser, which only ever sees part bodies and therefore cannot prove the envelope
 * ceiling (receiptanalysis-slice-report.md 6.10.62 point 3).
 *
 * Ownership of [raw] is exclusive and moves exactly once: the relay owns it from construction until
 * [finishAndCount] returns, and the caller owns it after. [WriterJob.job]'s completion is the
 * barrier, so the two are never concurrent readers.
 *
 * Failure classification is **per operation**, never per flag: a failure of `raw.readAvailable` is
 * genuine whether or not a handoff was requested, and is only "ours" when we cancelled `raw`
 * ourselves; a failure of `channel.writeFully` is "ours" only once the handoff was requested.
 * `CancellationException` is never recorded and always propagates.
 */
internal class CountingMultipartSource private constructor(
    private val raw: CountedByteReadChannel,
    private val coroutineContext: CoroutineContext
) {

    private class IngestionFailure(val cause: Exception, val overflowProven: Boolean)

    private val envelopeOverflow = AtomicBoolean(false)
    private val handoffRequested = AtomicBoolean(false)
    private val rawCancelRequested = AtomicBoolean(false)
    private val ingestionFailure = AtomicReference<IngestionFailure?>(null)

    private lateinit var relay: WriterJob

    /** Monotonic: once armed nothing disarms it, and concurrent arms are idempotent. */
    private fun markEnvelopeOverflow() {
        envelopeOverflow.compareAndSet(false, true)
    }

    val envelopeOverflowed: Boolean get() = envelopeOverflow.get()

    /**
     * The snapshot of the overflow latch is captured atomically with the cause: it is what decides,
     * in the route's ordered decision, whether the failure happened before or after 4d was proven.
     */
    private fun recordIngestionFailure(cause: Exception) {
        ingestionFailure.compareAndSet(null, IngestionFailure(cause, envelopeOverflowed))
    }

    val ingestionFailed: Boolean get() = ingestionFailure.get() != null

    val ingestionFailedAfterOverflowProven: Boolean
        get() = ingestionFailure.get()?.overflowProven == true

    val totalBytesRead: Long get() = raw.totalBytesRead

    /**
     * Feeds the parser from a channel the relay writes, never from [raw] directly, so every byte the
     * parser consumes has already been counted and clamped.
     *
     * [CIOMultipartDataBase] is the only way to build a [MultiPartData] over a channel we own --
     * `call.receiveMultipart()` reads the request body directly and would bypass the counting
     * entirely. It is annotated `@InternalAPI`, so this opt-in is deliberate and is recorded as a
     * known risk in receiptanalysis-slice-report.md: a Ktor upgrade may change or remove it, and the
     * `CountingMultipartSourceTest` suite is what would catch that.
     *
     * `contentLength` is deliberately `null`: the declared header must never reach the parser, since
     * acceptance is proven only by bytes actually read.
     *
     * `formFieldLimit` is a per-part content ceiling, not a count of allowed fields, and it is set
     * deliberately *above* our own envelope ceiling: the relay has already clamped the stream, so
     * this parser guard can never fire before our own checks and steal their verdict. Anything the
     * client sends that is genuinely too large is caught by [MAX_IMAGE_PART_BYTES] or
     * [MAX_ENVELOPE_BYTES], each with its own matrix row.
     */
    @OptIn(InternalAPI::class)
    fun parts(contentType: String): MultiPartData =
        CIOMultipartDataBase(coroutineContext, relay.channel, contentType, null, ENVELOPE_PROOF_BYTES)

    private fun startRelay(scope: CoroutineScope) {
        relay = scope.writer(coroutineContext) {
            val buffer = ByteArray(RELAY_BUFFER_BYTES)
            var forwarded = 0L
            try {
                while (true) {
                    if (handoffRequested.get()) break // clean exit: the caller asked for ownership

                    // True read ceiling: clamped to what is left up to the proof byte, so the
                    // source is never over-consumed by up to a buffer's worth.
                    val remaining = ENVELOPE_PROOF_BYTES - raw.totalBytesRead
                    if (remaining <= 0L) {
                        markEnvelopeOverflow()
                        break
                    }

                    val read = try {
                        raw.readAvailable(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        // A read failure is genuine unless we cancelled `raw` ourselves.
                        if (!rawCancelRequested.get()) failIngestion(failure)
                        return@writer
                    }
                    if (read == -1) break // EOF

                    if (raw.totalBytesRead > MAX_ENVELOPE_BYTES) {
                        // The proof byte was read: the body is larger than the ceiling. It is never
                        // forwarded downstream.
                        markEnvelopeOverflow()
                        val room = (MAX_ENVELOPE_BYTES - forwarded).toInt()
                        if (room > 0) writeOrFail(buffer, minOf(read, room)) ?: return@writer
                        break
                    }

                    writeOrFail(buffer, read) ?: return@writer
                    forwarded += read
                }
                if (!handoffRequested.get()) channel.flushAndClose()
            } catch (cancellation: CancellationException) {
                throw cancellation // never swallowed, with or without handoff
            } catch (failure: Exception) {
                if (!handoffRequested.get() && !rawCancelRequested.get()) failIngestion(failure)
            }
        }
    }

    /** Returns `null` when the write failed and the relay must stop; [Unit] when it succeeded. */
    private suspend fun io.ktor.utils.io.WriterScope.writeOrFail(buffer: ByteArray, count: Int): Unit? =
        try {
            channel.writeFully(buffer, 0, count)
            Unit
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A write failure is "ours" only once the handoff was requested.
            if (!handoffRequested.get()) failIngestion(failure)
            null
        }

    /**
     * Records the failure and cancels the parser's channel with it. Without the cancel the writer
     * would complete *without a cause*, and Ktor only cancels the `ByteChannel` on completion when
     * the completion carries one -- leaving `readPart()` able to suspend forever.
     */
    private fun io.ktor.utils.io.WriterScope.failIngestion(cause: Exception) {
        recordIngestionFailure(cause)
        channel.cancel(cause)
    }

    /**
     * Takes ownership of [raw] back from the relay and, when [countRemaining] is true, proves the
     * envelope ceiling by counting whatever follows the final boundary.
     *
     * Callers that already have a verdict outranking 4d pass `false`: nothing the count could find
     * would change the answer, so waiting on the client would be pure cost.
     */
    suspend fun finishAndCount(countRemaining: Boolean) {
        handoffRequested.set(true)
        if (!countRemaining) {
            // Nothing left to prove, so the source is cancelled *before* the join: otherwise a relay
            // suspended in `readAvailable` would keep the join waiting on bytes we no longer want.
            requestRawCancel()
            raw.cancel(null)
        }
        relay.channel.cancel(null)
        relay.job.join() // barrier: from here `raw` has no other reader

        if (!countRemaining) return

        try {
            val buffer = ByteArray(RELAY_BUFFER_BYTES)
            while (raw.totalBytesRead < ENVELOPE_PROOF_BYTES) {
                val remaining = ENVELOPE_PROOF_BYTES - raw.totalBytesRead
                val read = raw.readAvailable(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == -1) break
            }
            if (raw.totalBytesRead > MAX_ENVELOPE_BYTES) markEnvelopeOverflow()
        } catch (cancellation: CancellationException) {
            // Out to the caller that owns this source -- KtorReceiptUploadReader.read -- which
            // performs the cleanup. Rethrowing before the catch of Exception below is what keeps
            // cancellation from being recorded as an ingestion failure.
            throw cancellation
        } catch (failure: Exception) {
            recordIngestionFailure(failure) // same state: ownership moved, the failure is the same
        } finally {
            requestRawCancel()
            raw.cancel(null)
        }
    }

    /**
     * Cleanup for the cancelled path, and the only place that performs it.
     *
     * The whole body runs in [NonCancellable] because every caller reaches it from an **already
     * cancelled** context: without it, `join()` would throw immediately and the very guarantee this
     * function exists to provide -- that no reader, writer or relay survives -- would be skipped.
     * That was the defect of round 292.
     *
     * Order matters: both channels are cancelled **before** the `join()`, so the relay cannot still
     * be waiting on client bytes when we wait for it. Every operation is idempotent, so this
     * composes with the `finally` of [finishAndCount] without duplicating or inverting it.
     *
     * Never records an [ingestionFailure]: cancellation is not an I/O failure.
     */
    suspend fun cancelAll() {
        withContext(NonCancellable) {
            handoffRequested.set(true)
            requestRawCancel()
            raw.cancel(null)
            relay.channel.cancel(null)
            if (!relay.job.isCompleted) relay.job.join()
        }
    }

    private fun requestRawCancel() {
        rawCancelRequested.set(true)
    }

    /** Test-only: proves the relay left no coroutine behind. */
    internal val relayCompleted: Boolean get() = relay.job.isCompleted

    companion object {
        fun start(scope: CoroutineScope, body: ByteReadChannel, coroutineContext: CoroutineContext):
            CountingMultipartSource =
            CountingMultipartSource(body.counted(), coroutineContext).apply { startRelay(scope) }
    }
}
