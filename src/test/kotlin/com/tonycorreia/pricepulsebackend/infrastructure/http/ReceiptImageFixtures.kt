package com.tonycorreia.pricepulsebackend.infrastructure.http

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Minimal, hand-built PNG and JPEG byte arrays. Deliberately synthesised rather than checked in as
 * binaries: every test can then state exactly which byte it corrupts, and the corruption is visible
 * in the test itself instead of hidden in a fixture file.
 */
object ReceiptImageFixtures {

    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** A structurally complete PNG: signature, IHDR with valid CRC, IEND. */
    fun png(width: Int = 8, height: Int = 8): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        out.write(chunk("IHDR", ihdrData(width, height)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    /** A PNG truncated in the middle of its IEND chunk -- header intact, body not. */
    fun pngTruncatedAfterHeader(width: Int = 8, height: Int = 8): ByteArray {
        val complete = png(width, height)
        return complete.copyOf(complete.size - 6)
    }

    /** A PNG whose IEND chunk carries a deliberately wrong CRC. */
    fun pngWithCorruptedChunkCrc(): ByteArray {
        val bytes = png()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        return bytes
    }

    fun pngWithTrailingGarbage(): ByteArray = png() + byteArrayOf(0x00, 0x01, 0x02)

    private fun ihdrData(width: Int, height: Int): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(uint32(width))
        data.write(uint32(height))
        data.write(8)  // bit depth
        data.write(6)  // colour type: RGBA
        data.write(0)  // compression
        data.write(0)  // filter
        data.write(0)  // interlace
        return data.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(uint32(data.size))
        val typeAndData = type.toByteArray(Charsets.US_ASCII) + data
        out.write(typeAndData)
        val crc = CRC32().apply { update(typeAndData) }
        out.write(uint32(crc.value.toInt()))
        return out.toByteArray()
    }

    private fun uint32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    /** A structurally complete JPEG: SOI, one SOF0, SOS with entropy data, EOI. */
    fun jpeg(width: Int = 8, height: Int = 8): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        out.write(sof0(width, height))
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00)) // SOS
        out.write(byteArrayOf(0x11, 0x22, 0x33, 0xFF.toByte(), 0x00, 0x44)) // entropy, with one stuffed byte
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) // EOI
        return out.toByteArray()
    }

    /** Same header, but the stream stops before EOI. */
    fun jpegTruncatedAfterHeader(width: Int = 8, height: Int = 8): ByteArray {
        val complete = jpeg(width, height)
        return complete.copyOf(complete.size - 2)
    }

    /**
     * A byte flipped *inside* the entropy-coded data, leaving the marker chain and EOI intact.
     * This is the case the structural scan deliberately does NOT detect (product owner option A).
     */
    fun jpegWithFlippedEntropyByte(): ByteArray {
        val bytes = jpeg()
        val entropyStart = bytes.size - 8
        bytes[entropyStart] = (bytes[entropyStart].toInt() xor 0x5A).toByte()
        return bytes
    }

    fun jpegWithTwoStartOfFrames(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(sof0(8, 8))
        out.write(sof0(16, 16))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun sof0(width: Int, height: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xC0.toByte(),
        0x00, 0x11, // length = 17
        0x08,       // sample precision
        (height ushr 8).toByte(), height.toByte(),
        (width ushr 8).toByte(), width.toByte(),
        0x03,       // three components
        0x01, 0x11, 0x00,
        0x02, 0x11, 0x01,
        0x03, 0x11, 0x01
    )
}
