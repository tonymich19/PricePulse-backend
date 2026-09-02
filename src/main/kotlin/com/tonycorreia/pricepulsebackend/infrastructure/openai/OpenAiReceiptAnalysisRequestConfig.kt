package com.tonycorreia.pricepulsebackend.infrastructure.openai

/**
 * Backend-owned configuration for [OpenAiResponsesRequestFactory] -- no production defaults for
 * any field, so a real adapter can never be wired without an explicit, reviewed choice for each
 * one. [imageDetail] is deliberately a non-blank opaque string, not a closed enum: the official
 * `detail` values (`low`/`high`/`original`/`auto`) are documented as model-dependent ("supported
 * values depend on the model"), and [model] itself is injected here, not chosen by this slice --
 * encoding a fixed enum would presume a support matrix this slice has no authority to assert.
 *
 * [schemaName] must match the official Structured Outputs `text.format.name` constraint --
 * letters, digits, underscores, or dashes only, 1-64 characters -- so an otherwise-valid-looking
 * value (spaces, punctuation, Unicode letters, or an over-length name) can never silently build a
 * request the provider would reject; only blank was checked before this correction.
 *
 * [maxOutputTokens] is the Responses API's single cap on visible output tokens **and** reasoning
 * tokens combined -- required, strictly positive, so a real call can never go out with an
 * unbounded cost/latency ceiling (receiptanalysis-slice-report.md 6.10.53). [reasoningEffort] must
 * be one of the 6 values the API accepts -- required, never defaulted here, so the model's own
 * `medium` default is never relied upon silently.
 */
class OpenAiReceiptAnalysisRequestConfig private constructor(
    val model: String,
    val extractionInstructions: String,
    val schemaName: String,
    val imageDetail: String,
    val maxOutputTokens: Int,
    val reasoningEffort: String
) {
    companion object {
        private val SCHEMA_NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val ALLOWED_REASONING_EFFORTS = setOf("none", "low", "medium", "high", "xhigh", "max")

        operator fun invoke(
            model: String,
            extractionInstructions: String,
            schemaName: String,
            imageDetail: String,
            maxOutputTokens: Int,
            reasoningEffort: String
        ): OpenAiReceiptAnalysisRequestConfig {
            require(model.isNotBlank()) { "model must not be blank" }
            require(extractionInstructions.isNotBlank()) { "extractionInstructions must not be blank" }
            require(SCHEMA_NAME_PATTERN.matches(schemaName)) {
                "schemaName must match ${SCHEMA_NAME_PATTERN.pattern} (letters, digits, underscores, " +
                    "dashes only, 1-64 characters), was: $schemaName"
            }
            require(imageDetail.isNotBlank()) { "imageDetail must not be blank" }
            require(maxOutputTokens > 0) { "maxOutputTokens must be positive, was: $maxOutputTokens" }
            require(reasoningEffort in ALLOWED_REASONING_EFFORTS) {
                "reasoningEffort must be one of $ALLOWED_REASONING_EFFORTS, was: $reasoningEffort"
            }
            return OpenAiReceiptAnalysisRequestConfig(
                model, extractionInstructions, schemaName, imageDetail, maxOutputTokens, reasoningEffort
            )
        }
    }
}
