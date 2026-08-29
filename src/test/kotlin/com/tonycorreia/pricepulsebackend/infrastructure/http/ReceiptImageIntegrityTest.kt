package com.tonycorreia.pricepulsebackend.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The case matrix of receiptanalysis-slice-report.md 6.10.62 point 4, including the row that is
 * deliberately **not** detected -- asserted as accepted, so the limitation stays visible in the
 * code rather than living only in prose.
 */
class ReceiptImageIntegrityTest {

    private fun scan(bytes: ByteArray, mime: String) = ReceiptImageIntegrity.inspect(bytes, mime)

    // ---------------------------------------------------------------------------------------
    // Happy paths
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a complete PNG is intact and reports its header dimensions`() {
        val scanned = assertIs<ImageScan.Scanned>(scan(ReceiptImageFixtures.png(640, 480), "image/png"))
        assertTrue(scanned.intact)
        assertEquals(ImageDimensions(640, 480), scanned.dimensions)
    }

    @Test
    fun `a complete JPEG is intact and reports its header dimensions`() {
        val scanned = assertIs<ImageScan.Scanned>(scan(ReceiptImageFixtures.jpeg(1024, 768), "image/jpeg"))
        assertTrue(scanned.intact)
        assertEquals(ImageDimensions(1024, 768), scanned.dimensions)
    }

    // ---------------------------------------------------------------------------------------
    // Declared format vs. real signature
    // ---------------------------------------------------------------------------------------

    @Test
    fun `PNG bytes declared as JPEG are not the declared format`() {
        assertIs<ImageScan.NotTheDeclaredFormat>(scan(ReceiptImageFixtures.png(), "image/jpeg"))
    }

    @Test
    fun `JPEG bytes declared as PNG are not the declared format`() {
        assertIs<ImageScan.NotTheDeclaredFormat>(scan(ReceiptImageFixtures.jpeg(), "image/png"))
    }

    @Test
    fun `random bytes are not the declared format in either direction`() {
        val random = ByteArray(64) { (it * 7).toByte() }
        assertIs<ImageScan.NotTheDeclaredFormat>(scan(random, "image/png"))
        assertIs<ImageScan.NotTheDeclaredFormat>(scan(random, "image/jpeg"))
    }

    @Test
    fun `an unsupported declared type is never scanned`() {
        assertIs<ImageScan.NotTheDeclaredFormat>(scan(ReceiptImageFixtures.png(), "image/gif"))
    }

    // ---------------------------------------------------------------------------------------
    // Truncation and corruption -- header readable, body not
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a PNG truncated after the header keeps its dimensions but is not intact`() {
        val scanned = assertIs<ImageScan.Scanned>(
            scan(ReceiptImageFixtures.pngTruncatedAfterHeader(200, 100), "image/png")
        )
        assertFalse(scanned.intact)
        // Dimensions survive so the contract's 4e -> 4f order can still be applied.
        assertEquals(ImageDimensions(200, 100), scanned.dimensions)
    }

    @Test
    fun `a JPEG without EOI keeps its dimensions but is not intact`() {
        val scanned = assertIs<ImageScan.Scanned>(
            scan(ReceiptImageFixtures.jpegTruncatedAfterHeader(320, 240), "image/jpeg")
        )
        assertFalse(scanned.intact)
        assertEquals(ImageDimensions(320, 240), scanned.dimensions)
    }

    @Test
    fun `a single byte changed inside a PNG chunk is detected by its CRC`() {
        val scanned = assertIs<ImageScan.Scanned>(
            scan(ReceiptImageFixtures.pngWithCorruptedChunkCrc(), "image/png")
        )
        assertFalse(scanned.intact)
    }

    @Test
    fun `bytes appended after IEND are detected`() {
        val scanned = assertIs<ImageScan.Scanned>(
            scan(ReceiptImageFixtures.pngWithTrailingGarbage(), "image/png")
        )
        assertFalse(scanned.intact)
    }

    @Test
    fun `a JPEG with two start-of-frame markers is not intact`() {
        val scanned = assertIs<ImageScan.Scanned>(
            scan(ReceiptImageFixtures.jpegWithTwoStartOfFrames(), "image/jpeg")
        )
        assertFalse(scanned.intact)
    }

    // ---------------------------------------------------------------------------------------
    // Zero dimensions -- each axis proved on its own, because the guard is a disjunction
    // ---------------------------------------------------------------------------------------

    /**
     * Both axes separately, never `0 x 0`: the production guard is `width == 0 || height == 0`, and
     * a single `0 x 0` case would pass just as well against a regression to `&&`. A weakened guard
     * would yield `Scanned(0 x 8, intact = true)`, which the maximum-dimension check does not stop
     * (`0 > 6000` is false), so the upload would be accepted and a credit spent on an image with no
     * pixels.
     */
    @Test
    fun `a PNG declaring a zero width or a zero height is not the declared format`() {
        for ((width, height) in listOf(0 to 8, 8 to 0)) {
            assertIs<ImageScan.NotTheDeclaredFormat>(
                scan(ReceiptImageFixtures.png(width, height), "image/png"),
                "PNG ${width}x${height} must never be scanned as a valid image"
            )
        }
    }

    @Test
    fun `a JPEG declaring a zero width or a zero height is not the declared format`() {
        for ((width, height) in listOf(0 to 8, 8 to 0)) {
            assertIs<ImageScan.NotTheDeclaredFormat>(
                scan(ReceiptImageFixtures.jpeg(width, height), "image/jpeg"),
                "JPEG ${width}x${height} must never be scanned as a valid image"
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // The limitation the product owner accepted, asserted rather than described
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a byte flipped inside JPEG entropy data is NOT detected -- accepted limitation of option A`() {
        // JPEG carries no checksum. Detecting this would require decoding the whole bitmap, which
        // is precisely the decompression-bomb vector the 6000x6000 guard exists to prevent.
        // Product owner decision, round 274: option A (structural validation).
        val scanned = assertIs<ImageScan.Scanned>(
            scan(ReceiptImageFixtures.jpegWithFlippedEntropyByte(), "image/jpeg")
        )
        assertTrue(
            scanned.intact,
            "structural validation cannot see entropy-data corruption; if this ever starts " +
                "failing, the contract of matrix row 19 changed and must be re-decided"
        )
    }
}
