package com.tonycorreia.pricepulsebackend.contracts

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Validates the fixtures in contracts/fixtures/{valid,invalid}/ against
 * contracts/receipt-analysis-result.v1.schema.json -- the only source of truth for this contract
 * (no parallel Kotlin model exists in this repository, per this slice's restriction).
 */
class ReceiptAnalysisResultSchemaTest {

    private val projectRoot = File(System.getProperty("user.dir"))
    private val mapper = ObjectMapper()
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(mapper.readTree(File(projectRoot, "contracts/receipt-analysis-result.v1.schema.json")))

    private fun fixturesIn(subdir: String): List<File> =
        File(projectRoot, "contracts/fixtures/$subdir")
            .listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?: emptyList()

    @Test
    fun `valid fixtures satisfy the schema`() {
        val validFixtures = fixturesIn("valid")
        assertTrue(validFixtures.isNotEmpty(), "no valid fixtures found")

        validFixtures.forEach { fixture ->
            val errors = schema.validate(mapper.readTree(fixture))
            assertTrue(errors.isEmpty(), "${fixture.name} should satisfy the schema, but: $errors")
        }
    }

    @Test
    fun `valid fixtures together cover all four documentStatus values`() {
        val statuses = fixturesIn("valid")
            .map { mapper.readTree(it).get("documentStatus").asText() }
            .toSet()

        assertTrue(
            statuses == setOf("COMPLETE", "PARTIAL", "UNREADABLE", "NOT_A_RECEIPT"),
            "expected all 4 documentStatus values across valid fixtures, got: $statuses"
        )
    }

    @Test
    fun `invalid fixtures are rejected by the schema`() {
        val invalidFixtures = fixturesIn("invalid")
        assertTrue(invalidFixtures.isNotEmpty(), "no invalid fixtures found")

        invalidFixtures.forEach { fixture ->
            val errors = schema.validate(mapper.readTree(fixture))
            assertTrue(errors.isNotEmpty(), "${fixture.name} should be rejected by the schema, but it validated")
        }
    }

    @Test
    fun `rejection fixtures cover every required rejection case`() {
        val names = fixturesIn("invalid").map { it.nameWithoutExtension }.toSet()

        assertTrue("unknown-document-status" in names)
        assertTrue("wrong-schema-version" in names)
        assertTrue("present-field-missing-confidence" in names)
        assertTrue("negative-money" in names)
        assertTrue("non-positive-quantity" in names)
        assertTrue("malformed-quantity" in names)
    }
}
