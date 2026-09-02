package com.example.pdfmaker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xml.sax.helpers.DefaultHandler

@RunWith(AndroidJUnit4::class)
class SecureSaxParserInstrumentedTest {
    @Test
    fun AndroidSaxProviderCannotReadExternalFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secret = context.cacheDir.resolve("xml-provider-secret.txt").apply { writeText("private-value") }
        try {
            val captured = StringBuilder()
            val entityPayload =
                "<!DOCTYPE root [<!ENTITY leak SYSTEM \"${secret.toURI()}\">]>" +
                    "<root>&leak;</root>"

            assertThrows(IllegalArgumentException::class.java) {
                SecureSaxParser.parse(entityPayload, capturing(captured))
            }
            assertTrue(captured.isEmpty())

            val xIncludePayload =
                "<root xmlns:xi=\"http://www.w3.org/2001/XInclude\">" +
                    "<xi:include href=\"${secret.toURI()}\" parse=\"text\"/>" +
                    "</root>"
            SecureSaxParser.parse(xIncludePayload, capturing(captured))

            assertTrue(captured.isEmpty())
        } finally {
            secret.delete()
        }
    }

    private fun capturing(destination: StringBuilder): DefaultHandler =
        object : DefaultHandler() {
            override fun characters(
                characters: CharArray,
                start: Int,
                length: Int,
            ) {
                destination.append(characters, start, length)
            }
        }
}
