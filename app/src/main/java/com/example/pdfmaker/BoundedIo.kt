package com.example.pdfmaker

import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Stream primitives that fail closed when an untrusted provider exceeds its advertised size. */
object BoundedIo {
    private const val MAX_EMPTY_READS = 32

    @Throws(IOException::class)
    fun copy(input: InputStream, output: OutputStream, maximumBytes: Long): Long {
        require(maximumBytes > 0) { "Maximum byte count must be positive" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var emptyReads = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            if (read == 0) {
                emptyReads += 1
                if (emptyReads > MAX_EMPTY_READS) throw IOException("Input stream made no progress")
                continue
            }
            emptyReads = 0
            if (total > maximumBytes - read) throw IOException("Input exceeds the size limit")
            output.write(buffer, 0, read)
            total += read
        }
    }

    fun readPrefix(input: InputStream, maximumBytes: Int): ByteArray {
        require(maximumBytes > 0) { "Maximum byte count must be positive" }
        val result = ByteArray(maximumBytes)
        var offset = 0
        while (offset < result.size) {
            val read = input.read(result, offset, result.size - offset)
            if (read < 0) break
            if (read == 0) break
            offset += read
        }
        return result.copyOf(offset)
    }

    /** Wraps an output and rejects writes once the configured byte budget is exhausted. */
    fun limit(output: OutputStream, maximumBytes: Long): OutputStream {
        require(maximumBytes > 0) { "Maximum byte count must be positive" }
        return LimitedOutputStream(output, maximumBytes)
    }

    private class LimitedOutputStream(
        output: OutputStream,
        private val maximumBytes: Long,
    ) : FilterOutputStream(output) {
        private var writtenBytes = 0L

        override fun write(value: Int) {
            requireCapacity(1)
            out.write(value)
            writtenBytes += 1
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
                "Invalid output buffer range"
            }
            requireCapacity(length)
            out.write(bytes, offset, length)
            writtenBytes += length
        }

        private fun requireCapacity(additionalBytes: Int) {
            if (writtenBytes > maximumBytes - additionalBytes) {
                throw IOException("Output exceeds the size limit")
            }
        }
    }
}
