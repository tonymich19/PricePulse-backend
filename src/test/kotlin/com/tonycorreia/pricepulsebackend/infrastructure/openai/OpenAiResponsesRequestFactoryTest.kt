package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.PreparedReceiptImage
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttempt
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttemptId
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Entirely offline -- no network, no OpenAI call. Proves the built request tree matches the
 * currently verified official Responses/Structured Outputs shape (receiptanalysis-slice-report.md
 * 6.10.40), and that the returned body never discloses the image bytes or instructions.
 */
class OpenAiResponsesRequestFactoryTest {

    private val mapper = ObjectMapper()

    private val config = OpenAiReceiptAnalysisRequestConfig(
        model = "gpt-5.6",
        extractionInstructions = "Extract merchant, total, purchasedAt and items.",
        schemaName = "receipt_analysis",
        imageDetail = "auto",
        maxOutputTokens = 4000,
        reasoningEffort = "low"
    )

    private val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
    private val attempt = ReceiptAnalysisAttempt(
        attemptId = ReceiptAnalysisAttemptId("attempt-1"),
        image = PreparedReceiptImage(imageBytes, "image/png")
    )

    private fun buildTree(): JsonNode {
        val body = OpenAiResponsesRequestFactory(config)(attempt)
        return mapper.readTree(body.bytes())
    }

    @Test
    fun `model is exactly the injected model`() {
        val tree = buildTree()
        assertEquals("gpt-5.6", tree.get("model").asText())
    }

    @Test
    fun `input has exactly one user message with input_text then input_image`() {
        val tree = buildTree()
        val input = tree.get("input")
        assertTrue(input.isArray)
        assertEquals(1, input.size())

        val message = input.get(0)
        assertEquals("user", message.get("role").asText())
        assertFalse(message.has("type"), "input message must be {role, content} only, no type wrapper")

        val content = message.get("content")
        assertEquals(2, content.size())
        assertEquals("input_text", content.get(0).get("type").asText())
        assertEquals("input_image", content.get(1).get("type").asText())
    }

    @Test
    fun `input_text carries exactly the injected extraction instructions`() {
        val tree = buildTree()
        val inputText = tree.get("input").get(0).get("content").get(0)
        assertEquals("Extract merchant, total, purchasedAt and items.", inputText.get("text").asText())
    }

    @Test
    fun `input_image is a plain data URL string built from the real mime type and a base64 copy of the real bytes`() {
        val tree = buildTree()
        val inputImage = tree.get("input").get(0).get("content").get(1)

        val expectedDataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageBytes)
        assertTrue(inputImage.get("image_url").isTextual, "image_url must be a plain string, never a nested object")
        assertEquals(expectedDataUrl, inputImage.get("image_url").asText())
    }

    @Test
    fun `input_image detail is exactly the injected configuration value`() {
        val tree = buildTree()
        val inputImage = tree.get("input").get(0).get("content").get(1)
        assertEquals("auto", inputImage.get("detail").asText())
    }

    @Test
    fun `store is explicitly true`() {
        val tree = buildTree()
        assertTrue(tree.get("store").asBoolean())
    }

    @Test
    fun `max_output_tokens is a top-level integer sibling of model, input, text and store`() {
        val tree = buildTree()
        assertTrue(tree.get("max_output_tokens").isIntegralNumber, "max_output_tokens must be an integer, not a nested object or string")
        assertEquals(4000, tree.get("max_output_tokens").asInt())
    }

    @Test
    fun `reasoning is a flat object with only the effort field, matching the injected configuration`() {
        val tree = buildTree()
        val reasoning = tree.get("reasoning")
        assertEquals("low", reasoning.get("effort").asText())
        assertEquals(1, reasoning.size(), "reasoning must carry only effort, no other fields")
    }

    @Test
    fun `text format is flat json_schema with the injected schema name and strict true`() {
        val tree = buildTree()
        val format = tree.get("text").get("format")

        assertEquals("json_schema", format.get("type").asText())
        assertEquals("receipt_analysis", format.get("name").asText())
        assertTrue(format.get("strict").asBoolean())
        assertFalse(format.has("json_schema"), "format must be flat -- no nested json_schema object")
    }

    @Test
    fun `embedded schema is exactly the existing derived Structured Outputs tree, never an independently copied schema`() {
        val tree = buildTree()
        val embeddedSchema = tree.get("text").get("format").get("schema")

        val expected = mapper.readTree(
            OpenAiStructuredOutputsReceiptAnalysisSchema.fromCanonicalResource().serialize()
        )
        assertEquals(expected, embeddedSchema)
        assertEquals(0, countOccurrencesOfField(embeddedSchema, "oneOf"), "embedded schema must carry the anyOf transformation, never leftover oneOf")
    }

    @Test
    fun `a fresh attempt image byte array does not change an already-built request body`() {
        val mutableBytes = byteArrayOf(9, 9, 9)
        val mutableAttempt = ReceiptAnalysisAttempt(
            attemptId = ReceiptAnalysisAttemptId("attempt-2"),
            image = PreparedReceiptImage(mutableBytes, "image/jpeg")
        )
        val body = OpenAiResponsesRequestFactory(config)(mutableAttempt)
        val firstBytes = body.bytes()

        mutableBytes[0] = 0

        val secondBytes = body.bytes()
        assertEquals(firstBytes.toList(), secondBytes.toList())
    }

    @Test
    fun `repeated byte access returns independent copies -- mutating one never affects the body or a later read`() {
        val body = OpenAiResponsesRequestFactory(config)(attempt)
        val firstRead = body.bytes()
        val secondReadBeforeMutation = body.bytes()

        firstRead[0] = 0

        val thirdRead = body.bytes()
        assertEquals(secondReadBeforeMutation.toList(), thirdRead.toList())
        assertFalse(firstRead.toList() == thirdRead.toList())
    }

    @Test
    fun `toString never discloses the embedded image or instructions`() {
        val body = OpenAiResponsesRequestFactory(config)(attempt)
        val text = body.toString()

        assertFalse(text.contains(Base64.getEncoder().encodeToString(imageBytes)))
        assertFalse(text.contains(config.extractionInstructions))
        assertEquals("OpenAiResponsesRequestBody(<redacted>)", text)
    }

    private fun countOccurrencesOfField(node: JsonNode, fieldName: String): Int {
        var count = 0
        if (node.isObject && node.has(fieldName)) count++
        node.forEach { child -> count += countOccurrencesOfField(child, fieldName) }
        return count
    }
}
