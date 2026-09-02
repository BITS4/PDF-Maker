package com.example.pdfmaker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
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
    fun copyChecksCancellationBeforeEachProviderRead() {
        val source = ByteArrayInputStream(ByteArray(DEFAULT_BUFFER_SIZE * 3))
        val output = ByteArrayOutputStream()
        var checks = 0

        val error =
            runCatching {
                BoundedIo.copy(source, output, Long.MAX_VALUE) {
                    checks += 1
                    if (checks == 2) throw CancellationException("cancelled")
                }
            }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(2, checks)
        assertEquals(DEFAULT_BUFFER_SIZE, output.size())
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

    @Test
    fun boundedOutputAcceptsItsLimitAndRejectsOverflow() {
        val destination = ByteArrayOutputStream()
        val bounded = BoundedIo.limit(destination, 4)

        bounded.write(byteArrayOf(1, 2, 3), 0, 3)
        bounded.write(4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), destination.toByteArray())

        val error = runCatching { bounded.write(5) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), destination.toByteArray())
    }

    @Test
    fun boundedOutputRejectsInvalidLimitsAndRanges() {
        assertTrue(runCatching { BoundedIo.limit(ByteArrayOutputStream(), 0) }.isFailure)
        val bounded = BoundedIo.limit(ByteArrayOutputStream(), 10)
        assertTrue(runCatching { bounded.write(byteArrayOf(1, 2), 1, 2) }.isFailure)
    }

    @Test
    fun boundedOutputChecksCancellationBeforeEveryWrite() {
        val destination = ByteArrayOutputStream()
        var writesAllowed = true
        var checks = 0
        val bounded = BoundedIo.limit(destination, 10) {
            checks += 1
            check(writesAllowed) { "cancelled" }
        }

        bounded.write(byteArrayOf(1, 2))
        writesAllowed = false
        assertTrue(runCatching { bounded.write(3) }.isFailure)
        assertEquals(2, checks)
        assertArrayEquals(byteArrayOf(1, 2), destination.toByteArray())
    }
}
