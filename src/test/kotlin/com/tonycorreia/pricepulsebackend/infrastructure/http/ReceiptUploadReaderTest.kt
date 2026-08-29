package com.tonycorreia.pricepulsebackend.infrastructure.http

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real multipart path: [KtorReceiptUploadReader] over [CountingMultipartSource], driven through
 * an actual Ktor route so the relay, the parser and the handoff all run for real.
 *
 * The reader is mounted on a bare test route rather than on the POST, so each assertion is about
 * stage 4 alone -- admission and the use case are covered by `StartReceiptAnalysisRouteTest`.
 */
class ReceiptUploadReaderTest {

    /** Reports the reader's verdict as plain text, plus the relay's terminal state. */
    private fun ApplicationTestBuilder.installReader(reader: ReceiptUploadReader = KtorReceiptUploadReader()) {
        application {
            routing {
                post("/upload") {
                    val verdict = when (val result = reader.read(call)) {
                        is ReceiptUploadResult.Prepared ->
                            "PREPARED:${result.image.mimeType}:${result.image.sizeBytes}"

                        is ReceiptUploadResult.Rejected -> "REJECTED:${result.rejection.name}"
                        ReceiptUploadResult.IngestionFailed -> "INGESTION_FAILED"
                        ReceiptUploadResult.IngestionFailedAfterOverflow -> "INGESTION_FAILED_AFTER_OVERFLOW"
                    }
                    call.respondText(verdict)
                }
            }
        }
    }

    private suspend fun ApplicationTestBuilder.upload(
        bytes: ByteArray,
        partName: String = KtorReceiptUploadReader.IMAGE_PART_NAME,
        mimeType: String = "image/png",
        extraParts: Boolean = false,
        declaredLength: Long? = null
    ): HttpResponse = client.post("/upload") {
        declaredLength?.let { header(HttpHeaders.ContentLength, it.toString()) }
        setBody(
            MultiPartFormDataContent(
                formData {
                    // A `filename` parameter is what makes the CIO parser classify the part as a
                    // FileItem rather than a FormItem; without it the reader would see a form field
                    // and reject the upload as malformed.
                    append(
                        partName,
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"receipt.bin\"")
                        }
                    )
                    if (extraParts) append("unexpected", "value")
                }
            )
        )
    }

    // ---------------------------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a well formed PNG upload is prepared with its real byte count`() = testApplication {
        installReader()
        val png = ReceiptImageFixtures.png(100, 50)

        val response = upload(png)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("PREPARED:image/png:${png.size}", response.bodyAsText())
    }

    @Test
    fun `a well formed JPEG upload is prepared`() = testApplication {
        installReader()
        val jpeg = ReceiptImageFixtures.jpeg(100, 50)

        assertEquals("PREPARED:image/jpeg:${jpeg.size}", upload(jpeg, mimeType = "image/jpeg").bodyAsText())
    }

    // ---------------------------------------------------------------------------------------
    // 4a -- structure
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an unrecognised part name is MALFORMED_UPLOAD`() = testApplication {
        installReader()

        assertEquals("REJECTED:MALFORMED_UPLOAD", upload(ReceiptImageFixtures.png(), partName = "photo").bodyAsText())
    }

    @Test
    fun `an extra part alongside the image is MALFORMED_UPLOAD`() = testApplication {
        installReader()

        assertEquals(
            "REJECTED:MALFORMED_UPLOAD",
            upload(ReceiptImageFixtures.png(), extraParts = true).bodyAsText()
        )
    }

    // ---------------------------------------------------------------------------------------
    // 4b -- declared MIME, rejected without reading the body
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a declared MIME outside the accepted set is UNSUPPORTED_MEDIA_TYPE`() = testApplication {
        installReader()

        assertEquals(
            "REJECTED:UNSUPPORTED_MEDIA_TYPE",
            upload(ReceiptImageFixtures.png(), mimeType = "image/gif").bodyAsText()
        )
    }

    @Test
    fun `an unsupported MIME wins even when the part is large`() = testApplication {
        installReader()
        // Comfortably larger than any buffer, so the body would be expensive to read if we did.
        val large = ByteArray(300_000) { 0x7A }

        assertEquals(
            "REJECTED:UNSUPPORTED_MEDIA_TYPE",
            upload(large, mimeType = "image/gif").bodyAsText()
        )
    }

    // ---------------------------------------------------------------------------------------
    // 4e / 4f -- dimensions before integrity
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an image above 6000x6000 is IMAGE_DIMENSIONS_TOO_LARGE`() = testApplication {
        installReader()

        assertEquals(
            "REJECTED:IMAGE_DIMENSIONS_TOO_LARGE",
            upload(ReceiptImageFixtures.png(6001, 10)).bodyAsText()
        )
    }

    @Test
    fun `dimensions win over integrity when the header is readable but the body is truncated`() =
        testApplication {
            installReader()

            // 4e precedes 4f in the contract's order, so this is row 18 and not row 19.
            assertEquals(
                "REJECTED:IMAGE_DIMENSIONS_TOO_LARGE",
                upload(ReceiptImageFixtures.pngTruncatedAfterHeader(9000, 9000)).bodyAsText()
            )
        }

    @Test
    fun `a truncated image within the dimension limit is MEDIA_TYPE_MISMATCH`() = testApplication {
        installReader()

        assertEquals(
            "REJECTED:MEDIA_TYPE_MISMATCH",
            upload(ReceiptImageFixtures.pngTruncatedAfterHeader(10, 10)).bodyAsText()
        )
    }

    @Test
    fun `bytes whose real signature contradicts the declared type are MEDIA_TYPE_MISMATCH`() =
        testApplication {
            installReader()

            assertEquals(
                "REJECTED:MEDIA_TYPE_MISMATCH",
                upload(ReceiptImageFixtures.jpeg(), mimeType = "image/png").bodyAsText()
            )
        }

    @Test
    fun `a JPEG with a flipped entropy byte is still accepted -- the documented limitation`() =
        testApplication {
            installReader()
            val corrupted = ReceiptImageFixtures.jpegWithFlippedEntropyByte()

            assertTrue(
                upload(corrupted, mimeType = "image/jpeg").bodyAsText().startsWith("PREPARED:"),
                "structural validation cannot see entropy-data corruption (product owner option A)"
            )
        }

    // ---------------------------------------------------------------------------------------
    // S0 -- Content-Length may reject, never accept
    // ---------------------------------------------------------------------------------------

    /**
     * S0's rejection boundary is covered directly, against [rejectsByDeclaredLength], because it
     * cannot be covered through HTTP: Ktor's client computes `Content-Length` from the body it is
     * about to send, so a *lying* header cannot be injected through it, and a genuinely over-sized
     * envelope would be answered by stages 4a-4c before 4d is ever consulted. That limitation is a
     * property of the test client and remains; what the tests below prove is the decision itself.
     * The complementary property -- that the header can never *authorise* acceptance -- stays with
     * the HTTP tests, which decide on real byte counts alone.
     */
    @Test
    fun `one byte over the envelope ceiling is rejected by the declared length`() {
        assertTrue(rejectsByDeclaredLength((MAX_ENVELOPE_BYTES + 1).toString()))
    }

    @Test
    fun `a declared length exactly at the envelope ceiling is not rejected`() {
        assertFalse(rejectsByDeclaredLength(MAX_ENVELOPE_BYTES.toString()))
    }

    @Test
    fun `a declared length below the envelope ceiling is not rejected`() {
        assertFalse(rejectsByDeclaredLength((MAX_ENVELOPE_BYTES - 1).toString()))
    }

    @Test
    fun `an absent Content-Length is not rejected`() {
        assertFalse(rejectsByDeclaredLength(null))
    }

    @Test
    fun `an unparseable Content-Length is not rejected`() {
        // Blank, non-numeric and beyond Long: none of these is a declared size the reader can
        // compare, so each falls through to the parser instead of rejecting.
        assertFalse(rejectsByDeclaredLength(""))
        assertFalse(rejectsByDeclaredLength("   "))
        assertFalse(rejectsByDeclaredLength("not-a-number"))
        assertFalse(rejectsByDeclaredLength("99999999999999999999"))
    }

    @Test
    fun `a negative Content-Length is not rejected`() {
        assertFalse(rejectsByDeclaredLength("-1"))
    }

    @Test
    fun `a declared Content-Length never authorises acceptance by itself`() = testApplication {
        installReader()

        // Declaring a small length alongside a body that is only accepted on its own merits: the
        // verdict comes from the bytes, never from the header.
        assertTrue(
            upload(ReceiptImageFixtures.png(), declaredLength = 1).bodyAsText().startsWith("PREPARED:")
        )
    }

    @Test
    fun `a truthful small Content-Length never authorises acceptance on its own`() = testApplication {
        installReader()

        // The body is a valid PNG, so it is accepted on its merits -- not because of the header.
        assertTrue(
            upload(ReceiptImageFixtures.png(), declaredLength = 10).bodyAsText().startsWith("PREPARED:")
        )
    }

    // ---------------------------------------------------------------------------------------
    // The image ceiling, proven by real bytes
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an image part above its ceiling is IMAGE_TOO_LARGE`() = testApplication {
        installReader()
        val oversized = ByteArray((MAX_IMAGE_PART_BYTES + 1).toInt()) { 0x00 }

        assertEquals("REJECTED:IMAGE_TOO_LARGE", upload(oversized).bodyAsText())
    }

    @Test
    fun `an image part exactly at its ceiling is not rejected for size`() = testApplication {
        installReader()
        // Exactly at the ceiling: the size check must not fire, so it fails later, on integrity.
        val atCeiling = ByteArray(MAX_IMAGE_PART_BYTES.toInt()) { 0x00 }

        assertEquals("REJECTED:MEDIA_TYPE_MISMATCH", upload(atCeiling).bodyAsText())
    }
}
