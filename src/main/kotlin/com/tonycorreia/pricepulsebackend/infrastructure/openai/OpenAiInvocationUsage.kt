package com.tonycorreia.pricepulsebackend.infrastructure.openai

/**
 * Only-infrastructure value object -- the 6 token counts from a real OpenAI response's `usage`
 * object (`input_tokens`/`output_tokens`/`total_tokens`/`input_tokens_details.cached_tokens`/
 * `input_tokens_details.cache_write_tokens`/`output_tokens_details.reasoning_tokens`), each
 * independently optional. This constructor only guards that any non-null value passed in is
 * non-negative -- a defensive invariant, not a parser: coercing an absent, non-integer, or
 * negative raw JSON value to `null` for that field alone (never discarding the whole event) is
 * the parsing caller's responsibility. See receiptanalysis-slice-report.md 6.10.55.
 */
class OpenAiInvocationUsage private constructor(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val cachedTokens: Long?,
    val cacheWriteTokens: Long?,
    val reasoningTokens: Long?
) {
    companion object {
        operator fun invoke(
            inputTokens: Long?,
            outputTokens: Long?,
            totalTokens: Long?,
            cachedTokens: Long?,
            cacheWriteTokens: Long?,
            reasoningTokens: Long?
        ): OpenAiInvocationUsage {
            listOf(inputTokens, outputTokens, totalTokens, cachedTokens, cacheWriteTokens, reasoningTokens)
                .forEach { require(it == null || it >= 0) { "token counts must be null or non-negative, was: $it" } }
            return OpenAiInvocationUsage(
                inputTokens, outputTokens, totalTokens, cachedTokens, cacheWriteTokens, reasoningTokens
            )
        }
    }
}
