package com.tonycorreia.pricepulsebackend.application.receiptanalysis

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class PreparedReceiptImageTest {

    @Test
    fun `mutating the original array after construction does not affect the image`() {
        val original = byteArrayOf(1, 2, 3)
        val image = PreparedReceiptImage(original, "image/jpeg")

        original[0] = 99

        assertContentEquals(byteArrayOf(1, 2, 3), image.bytes())
    }

    @Test
    fun `mutating the array returned by bytes() does not affect subsequent reads`() {
        val image = PreparedReceiptImage(byteArrayOf(1, 2, 3), "image/jpeg")

        val firstRead = image.bytes()
        firstRead[0] = 99

        assertContentEquals(byteArrayOf(1, 2, 3), image.bytes())
    }

    @Test
    fun `bytes() never returns the same array instance twice`() {
        val image = PreparedReceiptImage(byteArrayOf(1, 2, 3), "image/jpeg")

        assertNotSame(image.bytes(), image.bytes())
    }

    @Test
    fun `sizeBytes is derived from the actual bytes, never a separate divergent field`() {
        val image = PreparedReceiptImage(byteArrayOf(1, 2, 3, 4, 5), "image/jpeg")

        assertEquals(5L, image.sizeBytes)
    }
}
