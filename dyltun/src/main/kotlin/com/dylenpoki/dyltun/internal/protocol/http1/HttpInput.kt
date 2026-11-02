package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.internal.stream.ByteSource
import com.dylenpoki.dyltun.internal.stream.CHUNK_BYTES
import java.io.EOFException
import java.io.IOException

internal class HttpProtocolException(message: String) : IOException(message)

/** Incremental cursor shared by header, body and upgrade readers. Never reads past a message boundary. */
internal class HttpInput(private val source: ByteSource) {
    private var chunk = byteArrayOf()
    private var position = 0

    private suspend fun ready(): Boolean {
        while (position == chunk.size) {
            chunk = source.read() ?: return false
            check(chunk.size <= CHUNK_BYTES) { "Source exceeded its chunk bound" }
            position = 0
        }
        return true
    }

    suspend fun line(maximum: Int = 8192, allowEof: Boolean = false): String? {
        val text = StringBuilder()
        while (true) {
            if (!ready()) {
                if (allowEof && text.isEmpty()) return null
                throw EOFException("Truncated HTTP line")
            }
            val value = chunk[position++].toInt() and 255
            if (value == 13) {
                if (!ready()) throw EOFException("Truncated CRLF")
                if (chunk[position++].toInt() != 10) throw HttpProtocolException("HTTP requires CRLF")
                return text.toString()
            }
            if (value == 10 || value == 0) throw HttpProtocolException("Invalid HTTP line ending")
            if (text.length >= maximum) throw HttpProtocolException("HTTP line limit exceeded")
            text.append(value.toChar())
        }
    }

    suspend fun read(maximum: Int = CHUNK_BYTES): ByteArray? {
        require(maximum in 1..CHUNK_BYTES)
        if (!ready()) return null
        val count = minOf(maximum, chunk.size - position)
        return chunk.copyOfRange(position, position + count).also { position += count }
    }

    suspend fun required(maximum: Int): ByteArray = read(maximum) ?: throw EOFException("Truncated HTTP body")
}
