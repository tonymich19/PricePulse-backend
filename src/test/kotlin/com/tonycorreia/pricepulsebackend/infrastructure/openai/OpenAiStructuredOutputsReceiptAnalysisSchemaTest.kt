package com.tonycorreia.pricepulsebackend.infrastructure.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Offline only -- no network, no OpenAI call. Proves the derivation is a faithful, guarded
 * transformation of the canonical V1 schema, never that OpenAI accepts the result (that requires
 * a controlled real integration test, out of scope for this slice).
 */
class OpenAiStructuredOutputsReceiptAnalysisSchemaTest {

    private val mapper = ObjectMapper()
    private val projectRoot = File(System.getProperty("user.dir"))
    private val canonical: JsonNode =
        mapper.readTree(File(projectRoot, "contracts/receipt-analysis-result.v1.schema.json"))

    @Test
    fun `derived tree has anyOf at exactly the four allowlisted locations and no oneOf anywhere`() {
        val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical)

        OpenAiStructuredOutputsReceiptAnalysisSchema.ALLOWLISTED_ONEOF_POINTERS.forEach { pointer ->
            val target = derived.at(pointer)
            assertTrue(target.has("anyOf"), "expected anyOf at $pointer")
            assertTrue(!target.has("oneOf"), "expected no oneOf left at $pointer")
        }
        assertEquals(0, countOccurrencesOfField(derived, "oneOf"), "no oneOf should remain anywhere in the derived tree")
        assertEquals(4, countOccurrencesOfField(derived, "anyOf"), "exactly 4 anyOf expected in the derived tree")
    }

    /**
     * Successor to an earlier test that asserted the derivation changed nothing but the four
     * `oneOf` keys. That contract ended when Structured Outputs compatibility required two more
     * transformations; this states the new one and is strictly stronger: it undoes every change
     * the derivation is authorised to make, so anything else that moved fails here.
     */
    @Test
    fun `the derived tree differs from the canonical only in the documented transformations`() {
        val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical).deepCopy<ObjectNode>()

        undoAnyOfRename(derived)
        undoConstTypes(derived)
        undoPatternRewrites(derived)

        assertEquals(canonical, derived, "derive changed something it was never authorised to change")
    }

    @Test
    fun `every const node in the derived tree declares a type, as Structured Outputs requires`() {
        val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical)

        assertEquals(
            emptyList(),
            constNodesWithoutType(derived),
            "Structured Outputs rejects the whole request for a single schema node without a type"
        )
        assertEquals(
            13,
            constNodesWithoutType(canonical).size,
            "the canonical contract still omits them -- the derivation works on a copy"
        )
    }

    @Test
    fun `a non-textual const fails loudly instead of being guessed`() {
        val tampered = canonical.deepCopy<ObjectNode>()
        (tampered.at("/properties/schemaVersion") as ObjectNode).put("const", 7)

        val failure = assertFailsWith<IllegalArgumentException> {
            OpenAiStructuredOutputsReceiptAnalysisSchema.derive(tampered)
        }

        assertTrue(failure.message!!.contains("/properties/schemaVersion"), "the failure must name the offending node")
    }

    @Test
    fun `an unexpected canonical oneOf outside the allowlist fails loudly, before any conversion`() {
        val tampered = canonical.deepCopy<ObjectNode>()
        val documentStatusNode = tampered.get("properties").get("documentStatus") as ObjectNode
        documentStatusNode.remove("enum")
        val oneOf = mapper.createArrayNode()
        oneOf.add(mapper.createObjectNode().put("const", "COMPLETE"))
        oneOf.add(mapper.createObjectNode().put("const", "PARTIAL"))
        documentStatusNode.set<JsonNode>("oneOf", oneOf)

        assertFailsWith<IllegalArgumentException> {
            OpenAiStructuredOutputsReceiptAnalysisSchema.derive(tampered)
        }
    }

    @Test
    fun `a missing canonical oneOf at an allowlisted location fails loudly, before any conversion`() {
        val tampered = canonical.deepCopy<ObjectNode>()
        val textField = tampered.get("\$defs").get("textField") as ObjectNode
        val oneOfValue = textField.remove("oneOf")
        textField.set<JsonNode>("anyOf", oneOfValue)

        assertFailsWith<IllegalArgumentException> {
            OpenAiStructuredOutputsReceiptAnalysisSchema.derive(tampered)
        }
    }

    @Test
    fun `an already-present anyOf at an allowlisted location fails loudly, before any conversion`() {
        val tampered = canonical.deepCopy<ObjectNode>()
        val textField = tampered.get("\$defs").get("textField") as ObjectNode
        textField.set<JsonNode>("anyOf", mapper.createArrayNode())

        val error = assertFailsWith<IllegalArgumentException> {
            OpenAiStructuredOutputsReceiptAnalysisSchema.derive(tampered)
        }
        assertTrue(error.message!!.contains("already present"), "expected an already-present anyOf message, got: ${error.message}")
    }

    @Test
    fun `derived tree satisfies the static OpenAI-relevant invariants`() {
        val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical)

        assertEquals("object", derived.get("type").asText(), "schema root must be type object")
        assertAdditionalPropertiesFalseEverywhere(derived)
        assertRequiredMatchesPropertiesEverywhere(derived)
        assertEveryRefIsInternalAndResolves(derived, derived)
    }

    @Test
    fun `assertRequiredMatchesPropertiesEverywhere fails loudly when required is missing from a node that has properties`() {
        val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical)
        val tamperedDerived = derived.deepCopy<ObjectNode>()
        tamperedDerived.remove("required")

        assertFailsWith<AssertionError> {
            assertRequiredMatchesPropertiesEverywhere(tamperedDerived)
        }
    }

    @Test
    fun `fromCanonicalResource loads the packaged classpath resource and derives the same tree as the file on disk`() {
        val fromClasspath = OpenAiStructuredOutputsReceiptAnalysisSchema.fromCanonicalResource()
        val expected = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical)

        val actualNode = mapper.readTree(fromClasspath.serialize())
        assertEquals(expected, actualNode)
    }

    private fun countOccurrencesOfField(node: JsonNode, fieldName: String): Int {
        var count = 0
        if (node.isObject && node.has(fieldName)) count++
        node.forEach { child -> count += countOccurrencesOfField(child, fieldName) }
        return count
    }

    private fun assertAdditionalPropertiesFalseEverywhere(node: JsonNode) {
        if (node.isObject && node.has("type") && node.get("type").asText() == "object") {
            assertTrue(
                node.has("additionalProperties") && node.get("additionalProperties").asBoolean() == false,
                "expected additionalProperties: false on object schema node: $node"
            )
        }
        node.forEach { child -> assertAdditionalPropertiesFalseEverywhere(child) }
    }

    private fun assertRequiredMatchesPropertiesEverywhere(node: JsonNode) {
        if (node.isObject && node.has("properties")) {
            assertTrue(node.has("required"), "expected required to be present alongside properties at: $node")
            val requiredNode = node.get("required")
            assertTrue(requiredNode.isArray, "expected required to be a JSON array at: $node")
            val requiredKeys = requiredNode.map { it.asText() }.toSet()
            val propertyKeys = node.get("properties").fieldNames().asSequence().toSet()
            assertEquals(propertyKeys, requiredKeys, "required must exactly match properties at: $node")
        }
        node.forEach { child -> assertRequiredMatchesPropertiesEverywhere(child) }
    }

    private fun assertEveryRefIsInternalAndResolves(node: JsonNode, root: JsonNode) {
        if (node.isObject && node.has("\$ref")) {
            val ref = node.get("\$ref").asText()
            assertTrue(ref.startsWith("#/"), "expected an internal reference, got: $ref")
            val resolved = root.at(ref.removePrefix("#"))
            assertTrue(!resolved.isMissingNode, "\$ref $ref does not resolve within the document")
        }
        node.forEach { child -> assertEveryRefIsInternalAndResolves(child, root) }
    }
    // -----------------------------------------------------------------------------------------
    // Helpers for the "only the documented transformations" invariant
    // -----------------------------------------------------------------------------------------

    /** Pointers of every node that has `const` but no `type`. */
    private fun constNodesWithoutType(node: JsonNode, path: String = ""): List<String> {
        val found = mutableListOf<String>()
        when {
            node.isObject -> {
                if (node.has("const") && !node.has("type")) found += path
                node.properties().forEach { (name, child) -> found += constNodesWithoutType(child, "$path/$name") }
            }
            node.isArray -> node.forEachIndexed { index, child -> found += constNodesWithoutType(child, "$path/$index") }
        }
        return found
    }

    private fun undoConstTypes(node: JsonNode) {
        when {
            node.isObject -> {
                val objectNode = node as ObjectNode
                if (objectNode.has("const")) objectNode.remove("type")
                objectNode.properties().forEach { (_, child) -> undoConstTypes(child) }
            }
            node.isArray -> node.forEach { undoConstTypes(it) }
        }
    }

    private fun undoAnyOfRename(node: JsonNode) {
        when {
            node.isObject -> {
                val objectNode = node as ObjectNode
                if (objectNode.has("anyOf")) objectNode.set<JsonNode>("oneOf", objectNode.remove("anyOf"))
                objectNode.properties().forEach { (_, child) -> undoAnyOfRename(child) }
            }
            node.isArray -> node.forEach { undoAnyOfRename(it) }
        }
    }

    /** Restores the canonical pattern at every rewritten pointer. Filled in by Task 2. */
    private fun undoPatternRewrites(root: JsonNode) {
        OpenAiStructuredOutputsReceiptAnalysisSchema.PATTERN_REWRITES.forEach { (pointer, rewrite) ->
            (root.at(pointer) as ObjectNode).put("pattern", rewrite.canonicalPattern)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Pattern rewrites -- Structured Outputs rejects regex lookaround outright
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the rewritten quantity pattern accepts and rejects exactly what the canonical one does`() {
        val rewrite = OpenAiStructuredOutputsReceiptAnalysisSchema.PATTERN_REWRITES.getValue("/\$defs/quantityField/oneOf/0/properties/value")
        val canonicalRegex = Regex(rewrite.canonicalPattern)
        val providerRegex = Regex(rewrite.providerPattern)

        val cases = listOf(
            "1", "10", "0.5", "0.05", "1.352", "999999", "0.000001",
            "0", "0.0", "0.000", "01", "007", ".5", "1.", "00.5", "-1", "1e3", "1,5", ""
        )

        val divergent = cases.filter { canonicalRegex.matches(it) != providerRegex.matches(it) }
        assertEquals(emptyList(), divergent, "the provider pattern must accept exactly the canonical language")
    }

    @Test
    fun `the derived tree carries the provider pattern and the canonical keeps its own`() {
        val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical)
        val rewrite = OpenAiStructuredOutputsReceiptAnalysisSchema.PATTERN_REWRITES.getValue("/\$defs/quantityField/oneOf/0/properties/value")

        assertEquals(rewrite.providerPattern, derived.at("/\$defs/quantityField/anyOf/0/properties/value").get("pattern").asText())
        assertEquals(
            rewrite.canonicalPattern,
            canonical.at("/\$defs/quantityField/oneOf/0/properties/value").get("pattern").asText(),
            "the canonical contract is never mutated"
        )
    }

    @Test
    fun `no lookaround survives anywhere in the derived tree`() {
        val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical)

        assertEquals(emptyList(), patternsWithLookaround(derived), "the provider rejects any lookaround")
    }

    @Test
    fun `an unlisted lookaround pattern fails loudly instead of reaching the provider`() {
        val tampered = canonical.deepCopy<ObjectNode>()
        (tampered.at("/\$defs/textField/oneOf/0/properties/value") as ObjectNode).put("pattern", "^(?=.*x).+$")

        val failure = assertFailsWith<IllegalArgumentException> {
            OpenAiStructuredOutputsReceiptAnalysisSchema.derive(tampered)
        }

        assertTrue(failure.message!!.contains("/\$defs/textField/oneOf/0/properties/value"), "the failure must name the offending pointer")
    }

    @Test
    fun `a rewrite whose canonical pattern no longer matches the contract fails loudly`() {
        val tampered = canonical.deepCopy<ObjectNode>()
        (tampered.at("/\$defs/quantityField/oneOf/0/properties/value") as ObjectNode).put("pattern", "^[0-9]+$")

        assertFailsWith<IllegalArgumentException> {
            OpenAiStructuredOutputsReceiptAnalysisSchema.derive(tampered)
        }
    }

    private fun patternsWithLookaround(node: JsonNode, path: String = ""): List<String> {
        val found = mutableListOf<String>()
        when {
            node.isObject -> {
                val pattern = node.get("pattern")?.takeIf { it.isTextual }?.asText()
                if (pattern != null && (pattern.contains("(?=") || pattern.contains("(?!"))) found += path
                node.properties().forEach { (name, child) -> found += patternsWithLookaround(child, "$path/$name") }
            }
            node.isArray -> node.forEachIndexed { index, child -> found += patternsWithLookaround(child, "$path/$index") }
        }
        return found
    }

}
