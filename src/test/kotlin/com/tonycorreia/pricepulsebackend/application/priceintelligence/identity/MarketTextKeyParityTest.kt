package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives [MarketTextKey] from the cross-repository contract fixture. The app runs the same fixture
 * against `ProductNameMatchKey`. A failure here means the backend rule drifted from the app. The
 * fixture is the contract: fix the code, never the fixture.
 */
class MarketTextKeyParityTest {

    private val fixture =
        File(File(System.getProperty("user.dir")), "contracts/fixtures/text-key/parity-cases.v1.json")

    private val table = ObjectMapper().readTree(fixture)

    /** Same value as the app's copy. Computed over LF-normalised content (the git blob content). */
    @Test
    fun `fixture content matches the pinned hash shared with the app`() {
        val lf = fixture.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(lf.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("aae747269ff423a7d2e38a93533a11b5d378ab3f7a902fe37d87b3c7fdbe136b", sha)
    }

    @Test
    fun `table is present and complete`() {
        assertEquals(1, table.get("version").asInt())
        assertEquals(29, table.get("equivalence").size(), "equivalence cases")
        assertEquals(10, table.get("rejection").size(), "rejection cases")
        assertEquals(13, table.get("canonicalKey").size(), "canonicalKey cases")
    }

    /**
     * The whitespace cases that the first version of this slice got wrong are asserted by id, so
     * removing them fails loudly.
     */
    @Test
    fun `whitespace contract cases are present by id`() {
        val keys = table.get("canonicalKey").map { it.get("id").asText() }.toSet()
        val rejections = table.get("rejection").map { it.get("id").asText() }.toSet()
        for (id in listOf("BORDER_EM_SPACE", "BORDER_NBSP", "BORDER_ZWSP", "INTERNAL_NBSP")) {
            assertTrue(id in keys, "canonicalKey case $id was removed from the fixture")
        }
        for (id in listOf("EM_SPACE_ONLY", "NBSP_ONLY", "ZWSP_ONLY")) {
            assertTrue(id in rejections, "rejection case $id was removed from the fixture")
        }
    }

    @Test
    fun `every canonical key case produces the pinned value`() {
        for (case in table.get("canonicalKey")) {
            val id = case.get("id").asText()
            assertEquals(
                case.get("key").asText(),
                MarketTextKey.from(case.get("input").asText()).value,
                "case $id: pinned key does not match"
            )
        }
    }

    @Test
    fun `every equivalence case matches the rule`() {
        for (case in table.get("equivalence")) {
            val id = case.get("id").asText()
            val left = MarketTextKey.from(case.get("left").asText())
            val right = MarketTextKey.from(case.get("right").asText())
            assertEquals(
                case.get("equal").asBoolean(),
                left == right,
                "case $id: expected equal=${case.get("equal").asBoolean()} " +
                    "but got left=[${left.value}] right=[${right.value}]"
            )
        }
    }

    @Test
    fun `every rejection case matches the rule`() {
        for (case in table.get("rejection")) {
            val id = case.get("id").asText()
            val input = case.get("input").asText()
            if (case.get("rejected").asBoolean()) {
                assertFailsWith<IllegalArgumentException>("case $id: expected rejection") {
                    MarketTextKey.from(input)
                }
            } else {
                assertTrue(
                    MarketTextKey.from(input).value.isNotEmpty(),
                    "case $id: expected acceptance"
                )
            }
        }
    }
}
