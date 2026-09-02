package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the prompt against the canonical contract.
 *
 * The prompt names the schema's literals in prose, and prose cannot `$ref` a JSON schema, so the
 * two drift silently. They already had: rule 2 offered three confidence levels while
 * `confidence` has always allowed four, which is how `UNKNOWN` came to be permitted by the schema
 * and never once requested of the model. That divergence shipped, reached production, and was only
 * found by scoring a receipt field by field.
 *
 * **The expected literals are read from `contracts/`, never restated here.** A test that hardcoded
 * them would drift with the prompt instead of catching it. The file is read the way
 * `ReceiptAnalysisResultSchemaTest` reads it -- from `user.dir` -- rather than from the classpath,
 * so the assertion is about the contract on disk.
 */
class ReceiptExtractionInstructionsTest {

    private val projectRoot = File(System.getProperty("user.dir"))
    private val contract = ObjectMapper()
        .readTree(File(projectRoot, "contracts/receipt-analysis-result.v1.schema.json"))

    private val text = ReceiptExtractionInstructions.TEXT

    /**
     * The prompt is wrapped to the file's line width, so a sentence that reads as one phrase is
     * split by a newline and the indentation that follows it. Phrase assertions run against this
     * flattened form: rewrapping a paragraph must not fail a test about what the paragraph says.
     * The assertions that are genuinely about line structure -- the per-level definitions -- keep
     * using [text].
     */
    private val flattened = text.replace(Regex("\\s+"), " ")

    private fun enumAt(vararg path: String): List<String> {
        var node = contract
        path.forEach { segment ->
            node = requireNotNull(node.get(segment)) { "no node at ${path.joinToString("/")}" }
        }
        val values = requireNotNull(node.get("enum")) { "no enum at ${path.joinToString("/")}" }
        return values.map { it.asText() }
    }

    private fun literalsNamedIn(candidates: List<String>): Set<String> =
        candidates.filter { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }.toSet()

    @Test
    fun `names every confidence level the contract allows`() {
        val allowed = enumAt("\$defs", "confidence")
        assertEquals(4, allowed.size, "the contract's confidence enum changed shape: $allowed")
        assertEquals(
            allowed.toSet(),
            literalsNamedIn(allowed),
            "the prompt must name every confidence level the schema permits -- a level the schema " +
                "allows but the prompt never mentions is a level the model will never return"
        )
    }

    @Test
    fun `names every documentStatus the contract allows`() {
        val allowed = enumAt("properties", "documentStatus")
        assertEquals(
            allowed.toSet(),
            literalsNamedIn(allowed),
            "the prompt must name every documentStatus the schema permits"
        )
    }

    @Test
    fun `defines each confidence level instead of only listing it`() {
        enumAt("\$defs", "confidence").forEach { level ->
            assertTrue(
                Regex("^\\s*-\\s*$level:", RegexOption.MULTILINE).containsMatchIn(text),
                "$level is named but never defined -- an unanchored level is what made the signal " +
                    "collapse into HIGH in the first place (ADR-007)"
            )
        }
    }

    @Test
    fun `states that HIGH is exceptional rather than the default`() {
        assertTrue(
            flattened.contains("HIGH é o nível excepcional, não o padrão"),
            "without this, HIGH reads as the neutral choice and the signal stops discriminating"
        )
    }

    @Test
    fun `keeps the extraction rules the pilot measures`() {
        // Rules 1 and 3-6 govern extraction, which Milestone 3 measures against the ceilings fixed
        // in slice 3.1. Editing them mid-pilot makes a bad number unattributable, so their presence
        // is asserted here rather than left to review.
        listOf(
            "purchasedAt deve ser convertido para UTC",
            "unidades menores",
            "quantity deve ser um valor decimal estritamente positivo",
            "Nunca invente valores"
        ).forEach { rule ->
            assertTrue(flattened.contains(rule), "extraction rule missing from the prompt: $rule")
        }
    }

    @Test
    fun `is accepted by the provider request configuration`() {
        val config = OpenAiReceiptAnalysisRequestConfig(
            model = "gpt-5",
            extractionInstructions = text,
            schemaName = "receipt_analysis_result_v1",
            imageDetail = "original",
            maxOutputTokens = 4000,
            reasoningEffort = "low"
        )

        assertEquals(text, config.extractionInstructions)
    }
}
