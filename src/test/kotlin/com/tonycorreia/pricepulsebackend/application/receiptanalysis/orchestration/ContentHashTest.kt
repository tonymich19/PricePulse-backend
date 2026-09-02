package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Known-answer tests for the canonical encoding of 6.10.16.A. The value class already rejects
 * anything outside `^[0-9a-f]{64}$`, so the *shape* was never in question; what was unpinned is the
 * **identity** -- that these exact bytes map to this exact digest. That value is persisted and read
 * back (`PostgresReceiptAnalysisOperationStore`), so a change that kept the shape but altered the
 * mapping would break idempotency matching silently.
 *
 * Every expectation below is a published SHA-256 vector written as a literal, never a value this
 * implementation produced: a test that asks the code under test what the answer is would pass
 * against any algorithm at all.
 *
 * These tests do not -- and offline cannot -- prove that rows already stored in a production
 * database match what this function returns today; that would require the database, which is out of
 * scope. They pin the algorithm and the encoding against silent change.
 */
class ContentHashTest {

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun `the empty input hashes to the published SHA-256 vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHash.sha256Of(ByteArray(0)).value
        )
    }

    @Test
    fun `abc hashes to the published SHA-256 vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHash.sha256Of(ascii("abc")).value
        )
    }

    @Test
    fun `the multi-block vector hashes correctly and pins zero-padded hex`() {
        // This digest contains bytes below 0x10 (`06`, `03`), so an encoding that dropped the
        // leading zero would produce fewer than 64 characters and fail here.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            ContentHash.sha256Of(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")).value
        )
    }

    @Test
    fun `the encoding is canonical -- 64 characters, lowercase hex, no prefix`() {
        val value = ContentHash.sha256Of(ascii("abc")).value

        assertEquals(64, value.length)
        assertTrue(value.all { it in '0'..'9' || it in 'a'..'f' }, "not lowercase hex: $value")
    }

    @Test
    fun `the same bytes always produce the same digest`() {
        assertEquals(
            ContentHash.sha256Of(ascii("abc")).value,
            ContentHash.sha256Of(ascii("abc")).value
        )
    }

    @Test
    fun `a one-bit difference in the input produces a different digest`() {
        val original = ascii("abc")
        // 'c' is 0x63; flipping its lowest bit gives 0x62 ('b'), a single-bit change.
        val flipped = original.copyOf().also { it[2] = (it[2].toInt() xor 0x01).toByte() }

        assertNotEquals(
            ContentHash.sha256Of(original).value,
            ContentHash.sha256Of(flipped).value
        )
    }
}
