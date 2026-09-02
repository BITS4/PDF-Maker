package com.example.pdfmaker

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
}
