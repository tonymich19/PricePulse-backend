package com.tonycorreia.pricepulsebackend.application.receiptanalysis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion

/**
 * Opaque wrapper around a document already validated against the canonical
 * receipt-analysis-result.v1 schema -- no typed accessor per field, so this class can never
 * become a second, Kotlin-side definition of the schema's fields (the schema stays the only
 * source of truth). Never logs the wrapped content.
 *
 * The internal tree is never exposed: [from] deep-copies any [JsonNode] handed in by a caller
 * before storing it, and [serialize] always returns a fresh [ByteArray] -- mutating either the
 * input node afterwards or a previously returned byte array can never affect this instance.
 */
class ValidatedReceiptAnalysisResultV1 private constructor(private val validatedJson: JsonNode) {

    /** Always a new byte array -- never the same instance across calls. */
    fun serialize(): ByteArray = MAPPER.writeValueAsBytes(validatedJson)

    companion object {
        private val MAPPER = ObjectMapper()
        private val SCHEMA: JsonSchema = loadCanonicalSchema()

        private fun loadCanonicalSchema(): JsonSchema {
            val resource = requireNotNull(
                ValidatedReceiptAnalysisResultV1::class.java
                    .getResourceAsStream("/receipt-analysis-result.v1.schema.json")
            ) { "receipt-analysis-result.v1.schema.json is missing from the classpath" }
            return resource.use {
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(MAPPER.readTree(it))
            }
        }

        /**
         * [bytes] come from an unreliable external source (a provider response) -- malformed
         * JSON or a schema violation is expected input, never an exceptional condition, so this
         * returns `null` instead of throwing.
         */
        fun from(bytes: ByteArray): ValidatedReceiptAnalysisResultV1? {
            val node = runCatching { MAPPER.readTree(bytes) }.getOrNull() ?: return null
            return fromValidated(node)
        }

        /** Deep-copies [node] before validating -- the caller's own reference is never retained. */
        fun from(node: JsonNode): ValidatedReceiptAnalysisResultV1? = fromValidated(node.deepCopy())

        private fun fromValidated(node: JsonNode): ValidatedReceiptAnalysisResultV1? {
            val errors = SCHEMA.validate(node)
            return if (errors.isEmpty()) ValidatedReceiptAnalysisResultV1(node) else null
        }
    }
}
