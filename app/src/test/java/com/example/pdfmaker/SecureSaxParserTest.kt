package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.util.concurrent.CancellationException

class SecureSaxParserTest {
    @Test
    fun `parses ordinary namespaced XML`() {
        val captured = StringBuilder()

        SecureSaxParser.parse("<w:root xmlns:w=\"urn:test\">safe</w:root>", capturing(captured))

        assertEquals("safe", captured.toString())
    }

    @Test
    fun `rejects external and parameter entities before expansion`() {
        val payloads =
            listOf(
                "<!DOCTYPE root [<!ENTITY leak SYSTEM \"file:///private\">]><root>&leak;</root>",
                "<!DOCTYPE root [<!ENTITY % remote SYSTEM \"https://invalid.example/dtd\">%remote;]><root/>",
                "<!doctype root><root/>",
                "<!entity value 'unsafe'><root/>",
            )

        payloads.forEach { payload ->
            val captured = StringBuilder()
            assertThrows(IllegalArgumentException::class.java) {
                SecureSaxParser.parse(payload, capturing(captured))
            }
            assertTrue(captured.isEmpty())
        }
    }

    @Test
    fun `lexical fallback permits declaration text in comments and CDATA`() {
        val captured = StringBuilder()
        val xml = "<root><!-- <!DOCTYPE harmless> --><![CDATA[<!ENTITY harmless>safe]]></root>"

        SecureSaxParser.parse(xml, capturing(captured))

        assertEquals("<!ENTITY harmless>safe", captured.toString())
    }

    @Test
    fun `XInclude nodes cannot load external content`() {
        val secret = File.createTempFile("xml-secret", ".txt").apply { writeText("must-not-escape") }
        try {
            val captured = StringBuilder()
            val xml =
                "<root xmlns:xi=\"http://www.w3.org/2001/XInclude\">" +
                    "<xi:include href=\"${secret.toURI()}\" parse=\"text\"/>" +
                    "</root>"

            SecureSaxParser.parse(xml, capturing(captured))

            assertTrue(captured.toString().isEmpty())
        } finally {
            secret.delete()
        }
    }

    @Test
    fun `cancellation interrupts parsing between SAX events`() {
        val xml =
            buildString {
                append("<root>")
                repeat(100) { index -> append("<item>$index</item>") }
                append("</root>")
            }
        var checks = 0
        var visitedElements = 0
        val handler =
            object : DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: org.xml.sax.Attributes?,
                ) {
                    visitedElements += 1
                }
            }

        assertThrows(CancellationException::class.java) {
            SecureSaxParser.parse(xml, handler) {
                checks += 1
                if (checks == 12) throw CancellationException("cancelled")
            }
        }
        assertEquals(12, checks)
        assertTrue(visitedElements in 1 until 101)
    }

    @Test
    fun `enforces input event and nesting limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureSaxParser.parse(
                "<root>oversized</root>",
                DefaultHandler(),
                limits = SecureSaxLimits(maximumCharacters = 8),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecureSaxParser.parse(
                "<root><first/><second/></root>",
                DefaultHandler(),
                limits = SecureSaxLimits(maximumEvents = 4),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecureSaxParser.parse(
                "<one><two><three/></two></one>",
                DefaultHandler(),
                limits = SecureSaxLimits(maximumDepth = 2),
            )
        }
    }

    @Test
    fun `rejects invalid limit configurations`() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureSaxLimits(maximumCharacters = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecureSaxLimits(maximumEvents = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecureSaxLimits(maximumDepth = 0)
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
