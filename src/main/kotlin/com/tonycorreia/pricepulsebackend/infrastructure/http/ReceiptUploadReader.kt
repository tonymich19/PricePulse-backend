package com.tonycorreia.pricepulsebackend.infrastructure.http

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import io.ktor.http.content.PartData
import io.ktor.utils.io.readRemaining
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import kotlinx.io.readByteArray
import kotlin.coroutines.cancellation.CancellationException

/** One constant per matrix row 14-19 -- the reader never invents a status of its own. */
enum class UploadRejection {
    /** Row 14 -- malformed envelope, a second image part, or an unrecognised part. */
    MALFORMED_UPLOAD,

    /** Row 15 -- declared MIME outside {image/jpeg, image/png}. */
    UNSUPPORTED_MEDIA_TYPE,

    /** Row 16 -- image part above 5.242.880 bytes. */
    IMAGE_TOO_LARGE,

    /** Row 17 -- whole multipart envelope above 5.505.024 bytes. */
    REQUEST_TOO_LARGE,

    /** Row 18 -- header dimensions above 6000x6000. */
    IMAGE_DIMENSIONS_TOO_LARGE,

    /** Row 19 -- real signature diverges from the declared MIME, or the file is not intact. */
    MEDIA_TYPE_MISMATCH
}

sealed interface ReceiptUploadResult {
    /** The route builds nothing else: the store derives hash, size and MIME from these bytes. */
    data class Prepared(val image: PreparedReceiptImage) : ReceiptUploadResult

    data class Rejected(val rejection: UploadRejection) : ReceiptUploadResult

    /** Ingestion itself failed (I/O), as opposed to the client sending something invalid. */
    data object IngestionFailed : ReceiptUploadResult

    /** Ingestion failed after the envelope ceiling was already proven -- row 17 wins over row 20. */
    data object IngestionFailedAfterOverflow : ReceiptUploadResult
}

/**
 * A `fun interface` with a single suspending operation, so the `ApplicationTest` fake can be a
 * lambda. Contrast [AdmissionRateLimiter], which has two operations and therefore takes an explicit
 * object fake.
 */
fun interface ReceiptUploadReader {
    suspend fun read(call: ApplicationCall): ReceiptUploadResult
}

private const val MAX_IMAGE_DIMENSION = 6000
private val ACCEPTED_MIME_TYPES = setOf("image/jpeg", "image/png")

/**
 * S0's whole decision, and nothing else. It is a pure function of the raw header so its boundary can
 * be tested directly: Ktor's test client recomputes `Content-Length`, so an HTTP test can never place
 * a forged value on either side of the ceiling.
 *
 * A header the reader cannot parse is not a rejection. Absent, blank, malformed and negative values
 * all fall through to the parser, which then proves the real size by counting bytes -- the declared
 * length may only reject, never authorise.
 */
internal fun rejectsByDeclaredLength(rawHeader: String?): Boolean {
    val declaredLength = rawHeader?.toLongOrNull() ?: return false
    return declaredLength > MAX_ENVELOPE_BYTES
}

/**
 * Stage 4 of the admission flow (receiptanalysis-slice-report.md 6.10.62 point 3), in the contract's
 * own order: 4a structure, 4b declared MIME, 4c image size, 4d envelope size, 4e dimensions,
 * 4f integrity.
 *
 * `reject(...)` ends the part loop immediately: the body of a rejected part is never read, and no
 * further part is requested once a rejection is recorded. Each [PartData] is disposed in the
 * `finally` of its own iteration, so at most one is ever open.
 */
class KtorReceiptUploadReader : ReceiptUploadReader {

    /**
     * Test-only seam: lets a test observe the source this reader created, so cancellation tests can
     * assert on the *production* path instead of driving [CountingMultipartSource] themselves.
     */
    internal var onSourceCreated: ((CountingMultipartSource) -> Unit)? = null

    override suspend fun read(call: ApplicationCall): ReceiptUploadResult {
        // S0 -- the only thing the declared length may do is reject early. It never authorises
        // acceptance, and it never reaches the parser.
        if (rejectsByDeclaredLength(call.request.headers["Content-Length"])) {
            return ReceiptUploadResult.Rejected(UploadRejection.REQUEST_TOO_LARGE)
        }

        val source = CountingMultipartSource.start(call, call.receiveChannel(), call.coroutineContext)
        onSourceCreated?.invoke(source)

        // S5-C IS OWNED HERE. This is the only scope that holds the source for the whole read --
        // part reading, the handoff join and the post-handoff count -- so it is the only place that
        // *can* clean it up. The route cannot: the source never leaves this function.
        // Round 292 documented the route as the owner and left this path only rethrowing, which
        // meant a cancellation during any of the three stages leaked the relay and the source.
        return try {
            readStages(call, source)
        } catch (cancellation: CancellationException) {
            source.cancelAll() // NonCancellable inside; cancels both channels before joining
            throw cancellation // the same exception, and no response is ever started
        }
    }

    private suspend fun readStages(call: ApplicationCall, source: CountingMultipartSource): ReceiptUploadResult {
        val contentType = call.request.contentType().toString()

        var firstRejection: UploadRejection? = null
        var imageBytes: ByteArray? = null
        var imageMimeType: String? = null
        var parserFailed = false
        var overflowAtParserFailure = false

        fun reject(rejection: UploadRejection) {
            if (firstRejection == null) firstRejection = rejection
        }

        try {
            val parts = source.parts(contentType)
            loop@ while (true) {
                val part = parts.readPart() ?: break
                try {
                    when {
                        part !is PartData.FileItem || part.name != IMAGE_PART_NAME ->
                            reject(UploadRejection.MALFORMED_UPLOAD) // 4a -- body never read

                        imageBytes != null ->
                            reject(UploadRejection.MALFORMED_UPLOAD) // 4a -- a second image part

                        else -> {
                            val declaredMime = part.contentType?.withoutParameters()?.toString()
                            if (declaredMime !in ACCEPTED_MIME_TYPES) {
                                reject(UploadRejection.UNSUPPORTED_MEDIA_TYPE) // 4b -- body never read
                            } else {
                                // 4c -- one byte past the ceiling is the proof of excess; the rest of
                                // the part is deliberately left unread. `provider()` is the Ktor 3
                                // accessor: `streamProvider()` is deprecated and throws.
                                val bytes = part.provider()
                                    .readRemaining(MAX_IMAGE_PART_BYTES + 1)
                                    .readByteArray()
                                if (bytes.size > MAX_IMAGE_PART_BYTES) {
                                    reject(UploadRejection.IMAGE_TOO_LARGE)
                                } else {
                                    imageBytes = bytes
                                    imageMimeType = declaredMime
                                }
                            }
                        }
                    }
                } finally {
                    part.dispose() // always: on break, on reject and on failure
                }
                if (firstRejection != null) break@loop // ends stage 4; no further readPart()
            }
        } catch (cancellation: CancellationException) {
            // Out to this reader's own catch in `read`, which owns the cleanup. Rethrowing before
            // the catch of Exception below is what stops cancellation being mistaken for a parser
            // failure.
            throw cancellation
        } catch (_: Exception) {
            parserFailed = true
            overflowAtParserFailure = source.envelopeOverflowed
        }

        // Only the accepting path still needs to prove 4d; any earlier rejection outranks it, so
        // counting further would be work that cannot change the answer.
        val stillNeeds4d = firstRejection == null && !parserFailed
        source.finishAndCount(countRemaining = stillNeeds4d)

        return decide(
            firstRejection = firstRejection,
            parserFailed = parserFailed,
            overflowAtParserFailure = overflowAtParserFailure,
            source = source,
            imageBytes = imageBytes,
            imageMimeType = imageMimeType
        )
    }

    /**
     * The contract's own 4a->4f order, never a comparison of independent flags: an earlier stage
     * that rejected is never overwritten by the envelope latch, which the relay may arm at any
     * moment. That is what makes the outcome independent of the relay's scheduling.
     */
    private fun decide(
        firstRejection: UploadRejection?,
        parserFailed: Boolean,
        overflowAtParserFailure: Boolean,
        source: CountingMultipartSource,
        imageBytes: ByteArray?,
        imageMimeType: String?
    ): ReceiptUploadResult {
        // 1 -- 4a/4b/4c, whichever failed first.
        firstRejection?.let { return ReceiptUploadResult.Rejected(it) }

        // 2 -- the parser broke because *we* cut an over-sized body.
        if (parserFailed && overflowAtParserFailure) {
            return ReceiptUploadResult.Rejected(UploadRejection.REQUEST_TOO_LARGE)
        }

        // 3 -- genuine ingestion failure, split by whether 4d was already proven.
        if (source.ingestionFailed) {
            return if (source.ingestionFailedAfterOverflowProven) {
                ReceiptUploadResult.IngestionFailedAfterOverflow
            } else {
                ReceiptUploadResult.IngestionFailed
            }
        }

        // 4 -- genuine framing failure by the client.
        if (parserFailed) return ReceiptUploadResult.Rejected(UploadRejection.MALFORMED_UPLOAD)

        // 5 -- 4d, consulted at its own position.
        if (source.envelopeOverflowed) {
            return ReceiptUploadResult.Rejected(UploadRejection.REQUEST_TOO_LARGE)
        }

        val bytes = imageBytes ?: return ReceiptUploadResult.Rejected(UploadRejection.MALFORMED_UPLOAD)
        val mimeType = imageMimeType ?: return ReceiptUploadResult.Rejected(UploadRejection.MALFORMED_UPLOAD)

        // 4e and 4f share one pass, but keep their contract order: a file whose header is readable
        // is measured first, so an over-sized image is row 18 even when its body is also broken.
        return when (val scan = ReceiptImageIntegrity.inspect(bytes, mimeType)) {
            is ImageScan.NotTheDeclaredFormat ->
                ReceiptUploadResult.Rejected(UploadRejection.MEDIA_TYPE_MISMATCH)

            is ImageScan.Scanned -> when {
                scan.dimensions.width > MAX_IMAGE_DIMENSION ||
                    scan.dimensions.height > MAX_IMAGE_DIMENSION ->
                    ReceiptUploadResult.Rejected(UploadRejection.IMAGE_DIMENSIONS_TOO_LARGE) // 4e

                !scan.intact ->
                    ReceiptUploadResult.Rejected(UploadRejection.MEDIA_TYPE_MISMATCH) // 4f

                else -> ReceiptUploadResult.Prepared(PreparedReceiptImage(bytes, mimeType))
            }
        }
    }

    companion object {
        /** The body carries exactly one part; anything else is row 14. */
        const val IMAGE_PART_NAME = "image"
    }
}
