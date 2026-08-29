package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Derives an OpenAI Structured-Outputs-compatible serialization of the canonical V1 schema for a
 * future request -- never a second maintained schema artifact, always a fresh in-memory
 * transformation of the one canonical resource
 * (`contracts/receipt-analysis-result.v1.schema.json`, packaged at the classpath root). Offline
 * only: no HTTP client, no adapter, no network. See receiptanalysis-slice-report.md 6.10.34.
 *
 * `oneOf` and `anyOf` have different validation semantics -- converting one to the other is only
 * safe where the branches are mutually exclusive by construction, which the canonical schema's 4
 * current unions are (each branch has a distinct `status.const`). A generic "replace every oneOf
 * found" rule would silently reclassify a future canonical `oneOf` that deliberately allows
 * overlapping branches, changing the provider contract without review. [ALLOWLISTED_ONEOF_POINTERS]
 * is deliberately closed to exactly those 4 locations -- an unexpected `oneOf` anywhere else, or a
 * missing one at an allowlisted location, fails loudly before any provider request is ever
 * attempted, turning future V1 union evolution into an explicit review gate.
 */
class OpenAiStructuredOutputsReceiptAnalysisSchema private constructor(private val derivedTree: JsonNode) {

    /** Always a new byte array -- never the same instance across calls. */
    fun serialize(): ByteArray = MAPPER.writeValueAsBytes(derivedTree)

    companion object {
        private val MAPPER = ObjectMapper()

        /**
         * Exactly the 4 canonical `oneOf` this slice is authorized to convert -- reviewed and
         * fixed by the Codex slice authorization (receiptanalysis-slice-report.md 6.10.34). Never
         * grown automatically; changing this list is itself a reviewed code change.
         */
        internal val ALLOWLISTED_ONEOF_POINTERS = listOf(
            "/\$defs/textField",
            "/\$defs/instantField",
            "/\$defs/moneyField",
            "/\$defs/quantityField"
        )

        /**
         * A canonical pattern the provider refuses, paired with a reviewed equivalent. Structured
         * Outputs rejects regex lookaround outright, and the canonical quantity pattern uses a
         * negative lookahead to say "not zero".
         *
         * [canonicalPattern] is not documentation: it is checked against the contract before any
         * rewrite, so editing the canonical pattern without revisiting the replacement fails loudly
         * instead of silently shipping a different language to the provider. The equivalence itself
         * is asserted by test, never by this comment.
         */
        internal data class PatternRewrite(val canonicalPattern: String, val providerPattern: String)

        /** Keyed by pointer into the CANONICAL tree -- rewrites run before the oneOf/anyOf rename. */
        internal val PATTERN_REWRITES: Map<String, PatternRewrite> = mapOf(
            "/\$defs/quantityField/oneOf/0/properties/value" to PatternRewrite(
                canonicalPattern = "^(?!0(\\.0+)?\$)(0|[1-9][0-9]*)(\\.[0-9]+)?\$",
                // Same language without lookaround: a non-zero integer part, or a zero integer part
                // with at least one non-zero fractional digit.
                providerPattern = "^([1-9][0-9]*(\\.[0-9]+)?|0\\.[0-9]*[1-9][0-9]*)\$"
            )
        )

        fun fromCanonicalResource(): OpenAiStructuredOutputsReceiptAnalysisSchema {
            val resource = requireNotNull(
                OpenAiStructuredOutputsReceiptAnalysisSchema::class.java
                    .getResourceAsStream("/receipt-analysis-result.v1.schema.json")
            ) { "receipt-analysis-result.v1.schema.json is missing from the classpath" }
            val canonical = resource.use { MAPPER.readTree(it) }
            return OpenAiStructuredOutputsReceiptAnalysisSchema(derive(canonical))
        }

        /**
         * Pure, no I/O. Deep-copies [canonical] before mutating -- the caller's own reference is
         * never touched. Validates every guard (no unexpected `oneOf`, none missing, none already
         * converted) before mutating anything, so a failure never leaves a partially-converted
         * tree. Every other node -- `$defs`, `$ref`, array/branch ordering, `required`, patterns,
         * limits, `additionalProperties` -- is preserved exactly, since only the 4 allowlisted
         * `oneOf` keys are ever touched.
         */
        internal fun derive(canonical: JsonNode): JsonNode {
            val derived = canonical.deepCopy<JsonNode>()
            val allowlisted = ALLOWLISTED_ONEOF_POINTERS.toSet()
            val actualOneOfPointers = findAllOneOfPointers(derived).toSet()

            val unexpected = actualOneOfPointers - allowlisted
            require(unexpected.isEmpty()) {
                "canonical schema has oneOf outside the allowlisted locations: $unexpected -- " +
                    "refusing to convert automatically, this needs a reviewed allowlist update"
            }
            val missing = allowlisted - actualOneOfPointers
            require(missing.isEmpty()) {
                "canonical schema is missing oneOf at allowlisted location(s): $missing"
            }

            // Before the rename on purpose: every pointer this class documents is then expressed
            // against the canonical `/oneOf/` shape, so a reader can locate it in the contract file
            // without mentally replaying an earlier transformation.
            addTypeToConstNodes(derived, "")
            rewriteUnsupportedPatterns(derived)

            val targets = ALLOWLISTED_ONEOF_POINTERS.map { pointer ->
                val target = derived.at(pointer)
                require(target.isObject) { "allowlisted pointer $pointer does not resolve to a JSON object" }
                val objectNode = target as ObjectNode
                require(!objectNode.has("anyOf")) {
                    "unexpected anyOf already present at $pointer before conversion"
                }
                objectNode
            }

            targets.forEach { objectNode ->
                val oneOfValue = objectNode.remove("oneOf")
                objectNode.set<JsonNode>("anyOf", oneOfValue)
            }

            return derived
        }

        /**
         * OpenAI Structured Outputs requires a `type` on every schema node. The canonical schema
         * legitimately omits it wherever `const` already pins the value -- plain JSON Schema allows
         * that -- and the provider rejected the entire request for it: 13 nodes today, the
         * `schemaVersion` and the 12 `status` discriminators of the 4 unions.
         *
         * A general rule rather than an allowlist, unlike [ALLOWLISTED_ONEOF_POINTERS] and
         * [PATTERN_REWRITES]: the constant's own JSON type determines the answer, leaving nothing
         * to review. The case that *would* need review is a non-textual constant -- `number` versus
         * `integer` is a real choice -- and none has ever existed here, so it fails loudly instead
         * of being guessed.
         */
        /**
         * Applies every [PATTERN_REWRITES] entry, then proves no lookaround is left anywhere. An
         * unlisted one fails here rather than travelling to the provider, which would reject the
         * whole request -- costing a reservation and a round trip for a fault this class can see.
         */
        private fun rewriteUnsupportedPatterns(derived: JsonNode) {
            PATTERN_REWRITES.forEach { (pointer, rewrite) ->
                val target = derived.at(pointer)
                require(target.isObject) { "pattern rewrite pointer $pointer does not resolve to a JSON object" }
                val objectNode = target as ObjectNode
                val actual = objectNode.get("pattern")?.takeIf { it.isTextual }?.asText()
                require(actual == rewrite.canonicalPattern) {
                    "canonical pattern at $pointer is not the one this rewrite was reviewed against -- " +
                        "the replacement may no longer be equivalent, this needs a reviewed update"
                }
                objectNode.put("pattern", rewrite.providerPattern)
            }

            val remaining = findLookaroundPatternPointers(derived, "")
            require(remaining.isEmpty()) {
                "canonical schema uses regex lookaround at $remaining, which Structured Outputs rejects -- " +
                    "refusing to rewrite automatically, this needs a reviewed entry in PATTERN_REWRITES"
            }
        }

        private fun findLookaroundPatternPointers(node: JsonNode, path: String): List<String> {
            val found = mutableListOf<String>()
            when {
                node.isObject -> {
                    val pattern = node.get("pattern")?.takeIf { it.isTextual }?.asText()
                    if (pattern != null && (pattern.contains("(?=") || pattern.contains("(?!"))) {
                        found += path
                    }
                    node.properties().forEach { (name, child) ->
                        found += findLookaroundPatternPointers(child, "$path/${escapeJsonPointerSegment(name)}")
                    }
                }
                node.isArray -> node.forEachIndexed { index, child ->
                    found += findLookaroundPatternPointers(child, "$path/$index")
                }
            }
            return found
        }

        private fun addTypeToConstNodes(node: JsonNode, path: String) {
            when {
                node.isObject -> {
                    val objectNode = node as ObjectNode
                    val constant = objectNode.get("const")
                    if (constant != null && !objectNode.has("type")) {
                        require(constant.isTextual) {
                            "canonical schema has a non-textual const at $path -- refusing to infer its " +
                                "Structured Outputs type, this needs a reviewed decision"
                        }
                        objectNode.put("type", "string")
                    }
                    objectNode.properties().forEach { (name, child) ->
                        addTypeToConstNodes(child, "$path/${escapeJsonPointerSegment(name)}")
                    }
                }
                node.isArray -> node.forEachIndexed { index, child ->
                    addTypeToConstNodes(child, "$path/$index")
                }
            }
        }

        private fun findAllOneOfPointers(node: JsonNode, path: String = ""): List<String> {
            val found = mutableListOf<String>()
            when {
                node.isObject -> {
                    if (node.has("oneOf")) {
                        found += path
                    }
                    node.properties().forEach { (name, child) ->
                        found += findAllOneOfPointers(child, "$path/${escapeJsonPointerSegment(name)}")
                    }
                }
                node.isArray -> {
                    node.forEachIndexed { index, child ->
                        found += findAllOneOfPointers(child, "$path/$index")
                    }
                }
            }
            return found
        }

        private fun escapeJsonPointerSegment(segment: String): String =
            segment.replace("~", "~0").replace("/", "~1")
    }
}
