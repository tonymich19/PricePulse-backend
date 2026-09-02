package com.tonycorreia.pricepulsebackend.infrastructure.http

import java.util.zip.CRC32

/** Width and height read from the header only -- never by decoding the bitmap. */
data class ImageDimensions(val width: Int, val height: Int)

/**
 * Split deliberately so the contract's 4e -> 4f order can be honoured: a file whose header is
 * readable but whose body is truncated still yields its dimensions, and an over-sized image is
 * therefore rejected as row 18 rather than row 19.
 */
sealed interface ImageScan {
    /** The bytes are not the declared format at all -- no header, so nothing to measure. Row 19. */
    data object NotTheDeclaredFormat : ImageScan

    /** The header parsed; [intact] is the result of the full structural pass. */
    data class Scanned(val dimensions: ImageDimensions, val intact: Boolean) : ImageScan
}

/**
 * Structural integrity of an accepted upload, in a single pass over bytes already in memory (at
 * most 5.242.880 of them). Never decodes a bitmap and never touches `javax.imageio`: decoding is
 * precisely the decompression-bomb vector the 6000x6000 guard exists to prevent.
 *
 * What each format can actually prove (receiptanalysis-slice-report.md 6.10.62 point 4, product
 * owner decision of round 274 -- option A):
 *
 * - **PNG: real content integrity.** Signature, chunk chain with a verified CRC-32 per chunk,
 *   `IHDR` first, `IEND` last, nothing after it.
 * - **JPEG: structural integrity only.** `SOI`, a consistent marker chain, exactly one `SOF`, and
 *   a final `EOI`. JPEG carries no checksum, so a bit flipped *inside* entropy-coded data that
 *   leaves the marker structure intact is **not detected** -- accepted and passed to the model,
 *   where it comes back as `UNREADABLE`/`PARTIAL`, which the domain already models as a normal
 *   semantic outcome.
 */
internal object ReceiptImageIntegrity {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun inspect(bytes: ByteArray, declaredMimeType: String): ImageScan = when (declaredMimeType) {
        "image/png" -> inspectPng(bytes)
        "image/jpeg" -> inspectJpeg(bytes)
        else -> ImageScan.NotTheDeclaredFormat
    }

    // ---------------------------------------------------------------------------------------
    // PNG -- signature + chunk chain + CRC-32 per chunk
    // ---------------------------------------------------------------------------------------

    private fun inspectPng(bytes: ByteArray): ImageScan {
        if (bytes.size < PNG_SIGNATURE.size) return ImageScan.NotTheDeclaredFormat
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return ImageScan.NotTheDeclaredFormat
        }

        // IHDR must be the first chunk and must itself be intact -- without it there is nothing
        // trustworthy to measure, so the file is simply not a readable PNG.
        val header = readPngHeader(bytes) ?: return ImageScan.NotTheDeclaredFormat

        var offset = PNG_SIGNATURE.size
        var sawIend = false
        while (offset < bytes.size) {
            if (offset + 8 > bytes.size) return ImageScan.Scanned(header, intact = false)
            val length = readUInt32(bytes, offset) ?: return ImageScan.Scanned(header, intact = false)
            if (length > Int.MAX_VALUE.toLong() - 12) return ImageScan.Scanned(header, intact = false)
            val dataStart = offset + 8
            val crcStart = dataStart + length.toInt()
            if (crcStart + 4 > bytes.size) return ImageScan.Scanned(header, intact = false)

            val crc = CRC32()
            crc.update(bytes, offset + 4, 4 + length.toInt()) // type + data
            val expected = readUInt32(bytes, crcStart)
            if (expected == null || crc.value != expected) {
                return ImageScan.Scanned(header, intact = false) // a byte inside the chunk changed
            }

            if (sawIend) return ImageScan.Scanned(header, intact = false) // trailing bytes
            if (String(bytes, offset + 4, 4, Charsets.US_ASCII) == "IEND") sawIend = true
            offset = crcStart + 4
        }

        return ImageScan.Scanned(header, intact = sawIend && offset == bytes.size)
    }

    /** Signature already checked: parses and CRC-verifies IHDR alone. */
    private fun readPngHeader(bytes: ByteArray): ImageDimensions? {
        val offset = PNG_SIGNATURE.size
        if (offset + 8 > bytes.size) return null
        if (readUInt32(bytes, offset) != 13L) return null
        if (String(bytes, offset + 4, 4, Charsets.US_ASCII) != "IHDR") return null
        val dataStart = offset + 8
        if (dataStart + 13 + 4 > bytes.size) return null

        val crc = CRC32()
        crc.update(bytes, offset + 4, 4 + 13)
        if (crc.value != readUInt32(bytes, dataStart + 13)) return null

        val width = readUInt32(bytes, dataStart) ?: return null
        val height = readUInt32(bytes, dataStart + 4) ?: return null
        if (width == 0L || height == 0L) return null
        if (width > Int.MAX_VALUE.toLong() || height > Int.MAX_VALUE.toLong()) return null
        return ImageDimensions(width.toInt(), height.toInt())
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long? {
        if (offset + 4 > bytes.size) return null
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    // ---------------------------------------------------------------------------------------
    // JPEG -- SOI + marker chain + exactly one SOF + EOI
    // ---------------------------------------------------------------------------------------

    private fun inspectJpeg(bytes: ByteArray): ImageScan {
        if (bytes.size < 4) return ImageScan.NotTheDeclaredFormat
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return ImageScan.NotTheDeclaredFormat // SOI

        var offset = 2
        var dimensions: ImageDimensions? = null

        // Once a SOF has been read the dimensions are trustworthy, so any later structural problem
        // is reported as "not intact" rather than as "not a JPEG" -- which keeps 4e ahead of 4f.
        fun broken(): ImageScan =
            dimensions?.let { ImageScan.Scanned(it, intact = false) } ?: ImageScan.NotTheDeclaredFormat

        while (offset < bytes.size) {
            if (bytes[offset] != 0xFF.toByte()) return broken() // a marker must start here
            // Fill bytes: any number of 0xFF may precede the marker code.
            while (offset < bytes.size && bytes[offset] == 0xFF.toByte()) offset++
            if (offset >= bytes.size) return broken()

            val marker = bytes[offset].toInt() and 0xFF
            offset++

            when {
                marker == 0xD9 -> { // EOI: nothing significant may follow
                    val measured = dimensions ?: return ImageScan.NotTheDeclaredFormat
                    return ImageScan.Scanned(measured, intact = offset == bytes.size)
                }

                // Standalone markers carry no length payload.
                marker == 0x01 || marker in 0xD0..0xD8 -> Unit

                else -> {
                    if (offset + 2 > bytes.size) return broken()
                    val length = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
                    if (length < 2) return broken()
                    val segmentEnd = offset + length
                    if (segmentEnd > bytes.size) return broken() // truncated mid-segment

                    if (isStartOfFrame(marker)) {
                        if (dimensions != null) return broken() // exactly one SOF
                        if (offset + 7 > bytes.size) return broken()
                        // SOF payload: length(2) precision(1) height(2) width(2)
                        val height = ((bytes[offset + 3].toInt() and 0xFF) shl 8) or (bytes[offset + 4].toInt() and 0xFF)
                        val width = ((bytes[offset + 5].toInt() and 0xFF) shl 8) or (bytes[offset + 6].toInt() and 0xFF)
                        if (width == 0 || height == 0) return broken()
                        dimensions = ImageDimensions(width, height)
                    }

                    offset = segmentEnd

                    if (marker == 0xDA) {
                        // Start of scan: entropy-coded data follows until the next real marker.
                        offset = skipEntropyCodedData(bytes, offset) ?: return broken()
                    }
                }
            }
        }

        return broken() // ran out of bytes without reaching EOI
    }

    private fun isStartOfFrame(marker: Int): Boolean =
        marker in 0xC0..0xC3 || marker in 0xC5..0xC7 || marker in 0xC9..0xCB || marker in 0xCD..0xCF

    /**
     * Walks entropy-coded data byte by byte: `FF 00` is byte stuffing and `FF D0..FF D7` are restart
     * markers, both of which continue the scan; any other `FF xx` is the next real marker.
     */
    private fun skipEntropyCodedData(bytes: ByteArray, from: Int): Int? {
        var offset = from
        while (offset < bytes.size) {
            if (bytes[offset] != 0xFF.toByte()) {
                offset++
                continue
            }
            if (offset + 1 >= bytes.size) return null
            val next = bytes[offset + 1].toInt() and 0xFF
            when {
                next == 0x00 -> offset += 2      // stuffed byte
                next in 0xD0..0xD7 -> offset += 2 // restart marker
                next == 0xFF -> offset++          // fill byte, re-examine
                else -> return offset             // next real marker starts here
            }
        }
        return null // no marker terminated the scan
    }
}
