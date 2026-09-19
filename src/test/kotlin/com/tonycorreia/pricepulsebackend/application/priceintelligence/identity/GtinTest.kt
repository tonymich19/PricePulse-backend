package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class GtinTest {

    private fun valid(raw: String): Gtin = assertIs<GtinParseResult.Valid>(Gtin.parse(raw)).gtin
    private fun rejection(raw: String): GtinRejection = assertIs<GtinParseResult.Invalid>(Gtin.parse(raw)).rejection

    // Task 2 — design §5, PO-6.

    @Test
    fun `accepts gtin-13 and keeps it as 14 digits`() {
        // Real Atacadão EAN, POC B data/atacadao-observations.json.
        assertEquals("07896006711155", valid("7896006711155").value)
    }

    @Test
    fun `accepts gtin-8, gtin-12 and gtin-14`() {
        // Design §5.4 valid table; check digits computed and executed, not taken from prose.
        assertEquals("00000096385074", valid("96385074").value)       // GTIN-8, check 4
        assertEquals("00000012345670", valid("12345670").value)       // GTIN-8, check 0
        assertEquals("00036000291452", valid("036000291452").value)   // GTIN-12, check 2
        assertEquals("00036000291452", valid("00036000291452").value) // GTIN-14, check 2
        assertEquals("17896006711152", valid("17896006711152").value) // GTIN-14, indicator 1, check 2
    }

    @Test
    fun `leading zeros denote the same gtin at every length`() {
        val gtin12 = valid("036000291452")
        assertEquals(gtin12, valid("0036000291452"))
        assertEquals(gtin12, valid("00036000291452"))
        assertEquals(gtin12.hashCode(), valid("00036000291452").hashCode())
    }

    @Test
    fun `the gtin-14 indicator digit is significant`() {
        assertNotEquals(valid("17896006711152"), valid("07896006711155"))
    }

    @Test
    fun `accepts every real ean of the pocs`() {
        // POC B atacadao-observations.json; POC A evidence/atacadao.md.
        listOf("7896006711155", "7896006744115", "7898215151708", "7891910030347", "7893500020158", "7893500020110")
            .forEach { assertEquals("0$it", valid(it).value) }
    }

    // Task 2 — design §5.1, §5.3, §5.4, SR-3, SR-4, SR-8.

    @Test
    fun `rejects an invalid check digit at every length`() {
        // Design §5.4 invalid table: last digit differs from the expected check digit.
        listOf("96385075", "036000291453", "7896006711156", "17896006711153")
            .forEach { assertEquals(GtinRejection.INVALID_CHECK_DIGIT, rejection(it)) }
    }

    @Test
    fun `rejects all zeros although the check digit is valid`() {
        // Design §5.3: 0…0 passes mod 10.
        listOf("00000000", "000000000000", "0000000000000", "00000000000000")
            .forEach { assertEquals(GtinRejection.ALL_ZEROS, rejection(it)) }
    }

    @Test
    fun `rejects unsupported lengths including empty`() {
        listOf("", "1234567", "123456789", "12345678901", "123456789012345")
            .forEach { assertEquals(GtinRejection.UNSUPPORTED_LENGTH, rejection(it)) }
    }

    @Test
    fun `rejects non digits and never trims`() {
        listOf(" 7896006711155", "7896006711155 ", "789600671115a", "7896-006711155", "789600671115\u0665")
            .forEach { assertEquals(GtinRejection.NON_DIGIT, rejection(it)) }
    }

    @Test
    fun `reports the first failing check in declaration order`() {
        assertEquals(GtinRejection.NON_DIGIT, rejection("12a"))          // also a bad length
        assertEquals(GtinRejection.UNSUPPORTED_LENGTH, rejection("000")) // also all zeros
    }

    @Test
    fun `parse never throws`() {
        listOf("", " ", "\u00a0", "x".repeat(10_000), "9".repeat(10_000)).forEach { Gtin.parse(it) }
    }
}
