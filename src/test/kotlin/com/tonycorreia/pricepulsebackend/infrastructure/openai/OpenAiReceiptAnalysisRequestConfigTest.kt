package com.tonycorreia.pricepulsebackend.infrastructure.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiReceiptAnalysisRequestConfigTest {

    private fun validConfig(
        model: String = "gpt-5.6",
        extractionInstructions: String = "Extract the receipt fields.",
        schemaName: String = "receipt_analysis",
        imageDetail: String = "auto",
        maxOutputTokens: Int = 4000,
        reasoningEffort: String = "low"
    ) = OpenAiReceiptAnalysisRequestConfig(
        model, extractionInstructions, schemaName, imageDetail, maxOutputTokens, reasoningEffort
    )

    @Test
    fun `accepts and exposes all six fields, with no production defaults`() {
        val config = validConfig()

        assertEquals("gpt-5.6", config.model)
        assertEquals("Extract the receipt fields.", config.extractionInstructions)
        assertEquals("receipt_analysis", config.schemaName)
        assertEquals("auto", config.imageDetail)
        assertEquals(4000, config.maxOutputTokens)
        assertEquals("low", config.reasoningEffort)
    }

    @Test
    fun `rejects a zero or negative maxOutputTokens`() {
        assertFailsWith<IllegalArgumentException> { validConfig(maxOutputTokens = 0) }
        assertFailsWith<IllegalArgumentException> { validConfig(maxOutputTokens = -1) }
    }

    @Test
    fun `accepts all six allowed reasoningEffort values`() {
        listOf("none", "low", "medium", "high", "xhigh", "max").forEach { effort ->
            assertEquals(effort, validConfig(reasoningEffort = effort).reasoningEffort)
        }
    }

    @Test
    fun `rejects a reasoningEffort outside the six allowed values`() {
        assertFailsWith<IllegalArgumentException> { validConfig(reasoningEffort = "") }
        assertFailsWith<IllegalArgumentException> { validConfig(reasoningEffort = "MEDIUM") }
        assertFailsWith<IllegalArgumentException> { validConfig(reasoningEffort = "extreme") }
        assertFailsWith<IllegalArgumentException> { validConfig(reasoningEffort = "low ") }
    }

    @Test
    fun `rejects a blank model`() {
        assertFailsWith<IllegalArgumentException> { validConfig(model = "") }
        assertFailsWith<IllegalArgumentException> { validConfig(model = "   ") }
    }

    @Test
    fun `rejects blank extraction instructions`() {
        assertFailsWith<IllegalArgumentException> { validConfig(extractionInstructions = "") }
        assertFailsWith<IllegalArgumentException> { validConfig(extractionInstructions = "   ") }
    }

    @Test
    fun `rejects a blank schema name`() {
        assertFailsWith<IllegalArgumentException> { validConfig(schemaName = "") }
        assertFailsWith<IllegalArgumentException> { validConfig(schemaName = "   ") }
    }

    @Test
    fun `accepts schema names made only of letters, digits, underscores and dashes, including a 64-character name`() {
        assertEquals("receipt_analysis", validConfig(schemaName = "receipt_analysis").schemaName)
        assertEquals("Receipt-Analysis-2", validConfig(schemaName = "Receipt-Analysis-2").schemaName)
        assertEquals("a", validConfig(schemaName = "a").schemaName)

        val sixtyFourChars = "a".repeat(64)
        assertEquals(64, sixtyFourChars.length)
        assertEquals(sixtyFourChars, validConfig(schemaName = sixtyFourChars).schemaName)
    }

    @Test
    fun `rejects a schema name with a space`() {
        assertFailsWith<IllegalArgumentException> { validConfig(schemaName = "receipt analysis") }
    }

    @Test
    fun `rejects a schema name with punctuation outside underscore and dash`() {
        assertFailsWith<IllegalArgumentException> { validConfig(schemaName = "receipt.analysis") }
        assertFailsWith<IllegalArgumentException> { validConfig(schemaName = "receipt/analysis") }
        assertFailsWith<IllegalArgumentException> { validConfig(schemaName = "receipt!analysis") }
    }

    @Test
    fun `rejects a schema name with a Unicode letter`() {
        assertFailsWith<IllegalArgumentException> { validConfig(schemaName = "reçeipt_analysis") }
        assertFailsWith<IllegalArgumentException> { validConfig(schemaName = "レシート") }
    }

    @Test
    fun `rejects a 65-character schema name`() {
        val sixtyFiveChars = "a".repeat(65)
        assertEquals(65, sixtyFiveChars.length)
        assertFailsWith<IllegalArgumentException> { validConfig(schemaName = sixtyFiveChars) }
    }

    @Test
    fun `rejects a blank image detail, and never assumes a default value on its own`() {
        assertFailsWith<IllegalArgumentException> { validConfig(imageDetail = "") }
        assertFailsWith<IllegalArgumentException> { validConfig(imageDetail = "   ") }
    }

    @Test
    fun `image detail is an opaque string, not a closed enum -- any non-blank value is accepted here`() {
        val config = validConfig(imageDetail = "original")
        assertEquals("original", config.imageDetail)
    }
}
