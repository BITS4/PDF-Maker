package com.example.pdfmaker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedIoTest {
    @Test
    fun copyAcceptsAnInputExactlyAtTheLimit() {
        val bytes = ByteArray(16_384) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()

        assertEquals(bytes.size.toLong(), BoundedIo.copy(ByteArrayInputStream(bytes), output, bytes.size.toLong()))
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun copyRejectsTheFirstByteOverTheLimit() {
        val output = ByteArrayOutputStream()

        val error = runCatching {
            BoundedIo.copy(ByteArrayInputStream(ByteArray(17)), output, 16)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(output.size() <= 16)
    }

    @Test
    fun copyRejectsAProviderThatNeverMakesProgress() {
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }

        val error = runCatching { BoundedIo.copy(stalled, ByteArrayOutputStream(), 100) }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    @Test
    fun invalidLimitsAreRejectedBeforeReading() {
        val tracking = object : InputStream() {
            var wasRead = false
            override fun read(): Int {
                wasRead = true
                return -1
            }
        }

        assertTrue(runCatching { BoundedIo.copy(tracking, ByteArrayOutputStream(), 0) }.isFailure)
        assertTrue(!tracking.wasRead)
    }

    @Test
    fun prefixReadNeverConsumesBeyondTheRequestedBoundary() {
        val input = ByteArrayInputStream("0123456789".toByteArray())

        assertArrayEquals("0123".toByteArray(), BoundedIo.readPrefix(input, 4))
        assertEquals('4'.code, input.read())
    }
}
