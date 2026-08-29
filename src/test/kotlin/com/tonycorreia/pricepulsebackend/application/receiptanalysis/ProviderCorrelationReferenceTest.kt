package com.tonycorreia.pricepulsebackend.application.receiptanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProviderCorrelationReferenceTest {

    @Test
    fun `blank value is rejected`() {
        assertFailsWith<IllegalArgumentException> { ProviderCorrelationReference("") }
        assertFailsWith<IllegalArgumentException> { ProviderCorrelationReference("   ") }
    }

    @Test
    fun `non-blank value is accepted and returned unchanged by value()`() {
        val reference = ProviderCorrelationReference("resp_abc123")

        assertEquals("resp_abc123", reference.value())
    }

    @Test
    fun `toString never exposes the raw value`() {
        val reference = ProviderCorrelationReference("super-secret-looking-reference")

        val text = reference.toString()

        assertTrue(!text.contains("super-secret-looking-reference"), "toString leaked the raw value: $text")
    }

    @Test
    fun `two references with the same value are equal, with different values are not`() {
        val a = ProviderCorrelationReference("resp_abc123")
        val b = ProviderCorrelationReference("resp_abc123")
        val c = ProviderCorrelationReference("resp_xyz789")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
