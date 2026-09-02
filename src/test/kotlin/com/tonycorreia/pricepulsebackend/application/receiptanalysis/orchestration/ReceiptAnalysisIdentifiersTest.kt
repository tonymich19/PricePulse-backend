package com.tonycorreia.pricepulsebackend.application.receiptanalysis.orchestration

import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The non-blank guard of the four identifier value classes, which nothing else in the suite
 * exercises directly (receiptanalysis-slice-report.md 6.10.75).
 *
 * Each test asserts `""` **and** `"   "`, never only `""`: whitespace alone is exactly what a guard
 * weakened from `isNotBlank()` to `isNotEmpty()` would let through, so the empty case by itself
 * would not prove the guard that production actually has.
 *
 * These identifiers are built from an authenticated Firebase UID, from a parsed request header, and
 * from persisted operation rows. As in `ContentHashTest`, an offline test proves the constructor's
 * behaviour today -- it does **not** prove anything about rows already written to a database.
 */
class ReceiptAnalysisIdentifiersTest {

    @Test
    fun `RequestId rejects blank values and preserves a non-blank one`() {
        assertFailsWith<IllegalArgumentException> { RequestId("") }
        assertFailsWith<IllegalArgumentException> { RequestId("   ") }
        assertEquals("req-1", RequestId("req-1").value)
    }

    @Test
    fun `UserId rejects blank values and preserves a non-blank one`() {
        assertFailsWith<IllegalArgumentException> { UserId("") }
        assertFailsWith<IllegalArgumentException> { UserId("   ") }
        assertEquals("user-a", UserId("user-a").value)
    }

    @Test
    fun `ReceiptAnalysisOperationId rejects blank values and preserves a non-blank one`() {
        assertFailsWith<IllegalArgumentException> { ReceiptAnalysisOperationId("") }
        assertFailsWith<IllegalArgumentException> { ReceiptAnalysisOperationId("   ") }
        assertEquals("op-1", ReceiptAnalysisOperationId("op-1").value)
    }

    @Test
    fun `ReceiptAnalysisAttemptId rejects blank values and preserves a non-blank one`() {
        assertFailsWith<IllegalArgumentException> { ReceiptAnalysisAttemptId("") }
        assertFailsWith<IllegalArgumentException> { ReceiptAnalysisAttemptId("   ") }
        assertEquals("attempt-1", ReceiptAnalysisAttemptId("attempt-1").value)
    }
}
