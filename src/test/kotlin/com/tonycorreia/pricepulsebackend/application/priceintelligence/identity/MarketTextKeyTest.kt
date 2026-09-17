package com.tonycorreia.pricepulsebackend.application.priceintelligence.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MarketTextKeyTest {

    @Test
    fun `rejects empty text`() {
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from("") }
    }

    @Test
    fun `rejects ascii-whitespace-only text`() {
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from("   ") }
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from("\t") }
    }

    @Test
    fun `rejects text made only of non-ascii space separators`() {
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from(" ") }
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from(" ") }
        assertFailsWith<IllegalArgumentException> { MarketTextKey.from("   \t") }
    }

    @Test
    fun `trims ascii borders and collapses internal runs`() {
        assertEquals(
            MarketTextKey.from("Biscoito Chocolate 120 g Trakinas"),
            MarketTextKey.from("  Biscoito  Chocolate 120  g Trakinas  ")
        )
    }

    @Test
    fun `collapses internal unicode whitespace that java considers whitespace`() {
        assertEquals(
            MarketTextKey.from("Biscoito Chocolate"),
            MarketTextKey.from("Biscoito Chocolate")
        )
    }

    @Test
    fun `does not collapse internal nbsp`() {
        assertNotEquals(
            MarketTextKey.from("Biscoito Chocolate"),
            MarketTextKey.from("Biscoito Chocolate")
        )
        assertEquals("biscoito chocolate", MarketTextKey.from("Biscoito Chocolate").value)
    }

    /**
     * The app's rule uses Kotlin's `trim()`, which removes every character for which Kotlin's
     * `Char.isWhitespace()` is true — including NBSP, narrow NBSP and figure space. Do NOT replace it
     * with `java.lang.String.trim()` or `strip()`: neither removes NBSP, and the backend would split
     * text identity from the app.
     */
    @Test
    fun `trims non-ascii space separators at the borders`() {
        assertEquals("trakinas", MarketTextKey.from(" Trakinas").value)
        assertEquals("trakinas", MarketTextKey.from("Trakinas ").value)
        assertEquals("trakinas", MarketTextKey.from("　Trakinas").value)
        assertEquals("trakinas", MarketTextKey.from(" Trakinas").value)
        assertEquals("trakinas", MarketTextKey.from(" Trakinas").value)
        assertEquals("trakinas", MarketTextKey.from(" Trakinas").value)
        assertEquals("trakinas", MarketTextKey.from("  Trakinas").value)
    }

    @Test
    fun `never removes zero width space`() {
        assertEquals("​trakinas", MarketTextKey.from("​Trakinas").value)
        assertEquals("​", MarketTextKey.from("​").value)
    }

    @Test
    fun `folds case using a fixed locale`() {
        assertEquals(MarketTextKey.from("KILO"), MarketTextKey.from("kilo"))
        assertEquals("kilo", MarketTextKey.from("KILO").value)
    }

    @Test
    fun `preserves accents`() {
        assertNotEquals(MarketTextKey.from("Café 500 g"), MarketTextKey.from("Cafe 500 g"))
        assertEquals("café 500 g", MarketTextKey.from("Café 500 g").value)
    }

    @Test
    fun `does not apply unicode normalization`() {
        assertNotEquals(MarketTextKey.from("Café"), MarketTextKey.from("Café"))
    }

    @Test
    fun `preserves hyphens and apostrophes`() {
        assertNotEquals(MarketTextKey.from("COCA-COLA 2 l"), MarketTextKey.from("COCA COLA 2 l"))
        assertNotEquals(MarketTextKey.from("M&M's 100 g"), MarketTextKey.from("M&Ms 100 g"))
    }

    @Test
    fun `does not treat a partial name as equivalent`() {
        assertNotEquals(
            MarketTextKey.from("Biscoito Chocolate 120 g"),
            MarketTextKey.from("Biscoito Chocolate 120 g Trakinas")
        )
    }
}
