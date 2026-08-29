package com.tonycorreia.pricepulsebackend.application.receiptanalysis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ValidatedReceiptAnalysisResultV1Test {

    private val mapper = ObjectMapper()
    private val projectRoot = File(System.getProperty("user.dir"))

    private fun fixturesIn(subdir: String): List<File> =
        File(projectRoot, "contracts/fixtures/$subdir")
            .listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?: emptyList()

    @Test
    fun `canonical schema is available on the classpath`() {
        val resource = javaClass.getResourceAsStream("/receipt-analysis-result.v1.schema.json")
        assertNotNull(resource, "schema resource missing from classpath")
        resource.close()
    }

    @Test
    fun `fixtures are never packaged onto the classpath`() {
        assertNull(javaClass.getResourceAsStream("/fixtures/valid/complete-with-items.json"))
        assertNull(javaClass.getResourceAsStream("/contracts/fixtures/valid/complete-with-items.json"))
        assertNull(javaClass.getResourceAsStream("/complete-with-items.json"))
    }

    @Test
    fun `every valid fixture is accepted`() {
        val validFixtures = fixturesIn("valid")
        check(validFixtures.isNotEmpty())

        validFixtures.forEach { fixture ->
            val result = ValidatedReceiptAnalysisResultV1.from(fixture.readBytes())
            assertNotNull(result, "${fixture.name} should be accepted")
        }
    }

    @Test
    fun `every invalid fixture is rejected`() {
        val invalidFixtures = fixturesIn("invalid")
        check(invalidFixtures.isNotEmpty())

        invalidFixtures.forEach { fixture ->
            val result = ValidatedReceiptAnalysisResultV1.from(fixture.readBytes())
            assertNull(result, "${fixture.name} should be rejected")
        }
    }

    @Test
    fun `malformed JSON is rejected, never throws`() {
        val result = ValidatedReceiptAnalysisResultV1.from("{ not json".toByteArray())

        assertNull(result)
    }

    @Test
    fun `mutating the input JsonNode after construction does not affect the wrapper`() {
        val node = mapper.readTree(fixturesIn("valid").first()) as ObjectNode
        val wrapper = ValidatedReceiptAnalysisResultV1.from(node)
        assertNotNull(wrapper)
        val before = wrapper.serialize()

        node.put("schemaVersion", "tampered")

        assertContentEquals(before, wrapper.serialize())
    }

    @Test
    fun `mutating bytes returned by serialize does not affect later calls`() {
        val wrapper = ValidatedReceiptAnalysisResultV1.from(fixturesIn("valid").first().readBytes())
        assertNotNull(wrapper)

        val firstSerialize = wrapper.serialize()
        val untouchedCopy = firstSerialize.copyOf()
        firstSerialize[0] = 0

        assertContentEquals(untouchedCopy, wrapper.serialize())
    }
}
