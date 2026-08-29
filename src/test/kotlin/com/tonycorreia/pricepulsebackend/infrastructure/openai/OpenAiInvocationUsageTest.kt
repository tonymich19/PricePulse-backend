package com.tonycorreia.pricepulsebackend.infrastructure.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Entirely offline -- no network. Proves the construction-time invariant of
 * receiptanalysis-slice-report.md 6.10.55: any non-null token count must be non-negative.
 * Coercion of an absent/non-integer/negative raw JSON value to null per-field is the parsing
 * layer's responsibility (proved in OpenAiReceiptAnalysisProviderAdapterTest), never this type's
 * own job.
 */
class OpenAiInvocationUsageTest {

    private fun usage(
        inputTokens: Long? = 10,
        outputTokens: Long? = 20,
        totalTokens: Long? = 30,
        cachedTokens: Long? = 1,
        cacheWriteTokens: Long? = 2,
        reasoningTokens: Long? = 3
    ) = OpenAiInvocationUsage(inputTokens, outputTokens, totalTokens, cachedTokens, cacheWriteTokens, reasoningTokens)

    @Test
    fun `accepts and exposes all six fields when present`() {
        val result = usage()

        assertEquals(10L, result.inputTokens)
        assertEquals(20L, result.outputTokens)
        assertEquals(30L, result.totalTokens)
        assertEquals(1L, result.cachedTokens)
        assertEquals(2L, result.cacheWriteTokens)
        assertEquals(3L, result.reasoningTokens)
    }

    @Test
    fun `accepts all six fields null simultaneously`() {
        val result = usage(null, null, null, null, null, null)

        assertEquals(null, result.inputTokens)
        assertEquals(null, result.outputTokens)
        assertEquals(null, result.totalTokens)
        assertEquals(null, result.cachedTokens)
        assertEquals(null, result.cacheWriteTokens)
        assertEquals(null, result.reasoningTokens)
    }

    @Test
    fun `accepts a mix of present and null fields`() {
        val result = usage(inputTokens = 5, outputTokens = null, cachedTokens = null)

        assertEquals(5L, result.inputTokens)
        assertEquals(null, result.outputTokens)
        assertEquals(30L, result.totalTokens)
        assertEquals(null, result.cachedTokens)
    }

    @Test
    fun `accepts zero as a valid non-negative count`() {
        assertEquals(0L, usage(inputTokens = 0).inputTokens)
    }

    @Test
    fun `rejects a negative inputTokens`() {
        assertFailsWith<IllegalArgumentException> { usage(inputTokens = -1) }
    }

    @Test
    fun `rejects a negative outputTokens`() {
        assertFailsWith<IllegalArgumentException> { usage(outputTokens = -1) }
    }

    @Test
    fun `rejects a negative totalTokens`() {
        assertFailsWith<IllegalArgumentException> { usage(totalTokens = -1) }
    }

    @Test
    fun `rejects a negative cachedTokens`() {
        assertFailsWith<IllegalArgumentException> { usage(cachedTokens = -1) }
    }

    @Test
    fun `rejects a negative cacheWriteTokens`() {
        assertFailsWith<IllegalArgumentException> { usage(cacheWriteTokens = -1) }
    }

    @Test
    fun `rejects a negative reasoningTokens`() {
        assertFailsWith<IllegalArgumentException> { usage(reasoningTokens = -1) }
    }
}
