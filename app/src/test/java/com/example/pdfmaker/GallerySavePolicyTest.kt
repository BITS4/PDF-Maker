package com.example.pdfmaker

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySavePolicyTest {
    @Test
    fun `uses permissionless media store only on Android ten and newer`() {
        assertFalse(GallerySavePolicy.supportsGalleryWrite(28))
        assertTrue(GallerySavePolicy.supportsGalleryWrite(29))
    }

    @Test
    fun `reports complete partial and empty outcomes accurately`() {
        assertTrue(GallerySavePolicy.report(2, 2, emptyList()).isComplete)

        val partial = GallerySavePolicy.report(2, 1, listOf("Page 2 failed", "Page 2 failed"))
        assertFalse(partial.isComplete)
        assertTrue(partial.userMessage.contains("1 of 2"))
        assertEquals(1, partial.errors.size)

        val empty = GallerySavePolicy.report(0, 0, emptyList())
        assertFalse(empty.isComplete)
        assertTrue(empty.userMessage.contains("no images", ignoreCase = true))
    }

    @Test
    fun `rejects impossible report counts`() {
        assertTrue(runCatching { GallerySavePolicy.report(-1, 0, emptyList()) }.isFailure)
        assertTrue(runCatching { GallerySavePolicy.report(1, 2, emptyList()) }.isFailure)
    }

    @Test
    fun `maps gallery failures without exposing provider details`() {
        val privateDetail = "/storage/emulated/0/private/customer-name.jpg"
        val failures =
            listOf(
                SecurityException(privateDetail),
                IOException(privateDetail),
                IllegalStateException(privateDetail),
            )

        failures.forEach { error ->
            val message = GallerySavePolicy.failureMessage(error)
            assertFalse(message.contains(privateDetail))
            assertFalse(message.contains("customer-name"))
        }
    }
}
