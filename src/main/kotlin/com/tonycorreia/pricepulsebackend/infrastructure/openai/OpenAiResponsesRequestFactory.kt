package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisAttempt
import java.util.Base64

/**
 * Pure, offline builder of a `POST /responses` request body -- no HTTP client, no network, no
 * [com.tonycorreia.pricepulsebackend.application.receiptanalysis.ReceiptAnalysisProviderPort]
 * implementation. Shape verified live against the current official Responses/Structured Outputs
 * guides (receiptanalysis-slice-report.md 6.10.40): `input` is `{role, content}` (no `type`
 * wrapper), `input_image.image_url` is a plain data-URL string (never a nested object), and
 * `text.format` is flat (`type`/`name`/`schema`/`strict` as direct siblings, no nested
 * `json_schema` object). `store: true` is set explicitly -- this only preserves the already
 * chosen normal-storage/remote-correlation direction (receiptanalysis-slice-report.md 6.10.38,
 * Option A); it is not a permission to implement retrieval. `max_output_tokens` is a top-level
 * sibling of `model`/`input`/`text`/`store` (an integer, not nested); `reasoning` is a small
 * object with a single `effort` field -- both required by [OpenAiReceiptAnalysisRequestConfig],
 * carrying the real cost/latency guard decided in receiptanalysis-slice-report.md 6.10.53.
 */
class OpenAiResponsesRequestFactory(private val config: OpenAiReceiptAnalysisRequestConfig) {

    operator fun invoke(attempt: ReceiptAnalysisAttempt): OpenAiResponsesRequestBody {
        val schemaTree = MAPPER.readTree(
            OpenAiStructuredOutputsReceiptAnalysisSchema.fromCanonicalResource().serialize()
        )

        val dataUrl = "data:${attempt.image.mimeType};base64," +
            Base64.getEncoder().encodeToString(attempt.image.bytes())

        val inputText = MAPPER.createObjectNode().apply {
            put("type", "input_text")
            put("text", config.extractionInstructions)
        }
        val inputImage = MAPPER.createObjectNode().apply {
            put("type", "input_image")
            put("image_url", dataUrl)
            put("detail", config.imageDetail)
        }
        val content = MAPPER.createArrayNode().apply {
            add(inputText)
            add(inputImage)
        }
        val userMessage = MAPPER.createObjectNode().apply {
            put("role", "user")
            set<JsonNode>("content", content)
        }
        val input = MAPPER.createArrayNode().apply { add(userMessage) }

        val format = MAPPER.createObjectNode().apply {
            put("type", "json_schema")
            put("name", config.schemaName)
            set<JsonNode>("schema", schemaTree)
            put("strict", true)
        }
        val text = MAPPER.createObjectNode().apply { set<JsonNode>("format", format) }

        val reasoning = MAPPER.createObjectNode().apply {
            put("effort", config.reasoningEffort)
        }

        val root = MAPPER.createObjectNode().apply {
            put("model", config.model)
            set<JsonNode>("input", input)
            set<JsonNode>("text", text)
            put("store", true)
            put("max_output_tokens", config.maxOutputTokens)
            set<JsonNode>("reasoning", reasoning)
        }

        return OpenAiResponsesRequestBody(MAPPER.writeValueAsBytes(root))
    }

    companion object {
        private val MAPPER = ObjectMapper()
    }
}
