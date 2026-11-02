package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.internal.stream.ByteSink
import com.dylenpoki.dyltun.internal.stream.CHUNK_BYTES
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.isHttpToken

internal class FramingGuard(headers: HttpHeaders) {
    private val snapshot = NAMES.associateWith { headers.getAll(it) }
    fun check(headers: HttpHeaders) {
        if (snapshot.any { (name, values) -> headers.getAll(name) != values }) {
            throw HttpProtocolException("Streaming Hooks cannot change Content-Length, Transfer-Encoding, Content-Encoding or Trailer")
        }
    }
    private companion object { val NAMES = listOf("Content-Length", "Transfer-Encoding", "Content-Encoding", "Trailer") }
}

internal object HttpEncoder {
    fun headers(startLine: String, headers: HttpHeaders): ByteArray {
        if (startLine.any { it.code !in 32..126 }) throw HttpProtocolException("Invalid HTTP start line")
        val fields = headers.entries()
        if (fields.size > HttpFraming.MAX_FIELDS) throw HttpProtocolException("Too many Hook header fields")
        val size = startLine.length.toLong() + 4 + fields.sumOf { (name, value) -> name.length.toLong() + value.length + 4 }
        if (size > HttpFraming.MAX_HEADERS) throw HttpProtocolException("Hook header limit exceeded")
        return buildString(size.toInt()) {
            append(startLine).append("\r\n")
            fields.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
    }

    fun cleanHopHeaders(headers: HttpHeaders, upgrade: Boolean = false) {
        val names = HttpFraming.tokens(headers, "Connection")
        if (names.any { !isHttpToken(it) }) throw HttpProtocolException("Invalid Connection tokens")
        if (names.any { it in setOf("content-length", "transfer-encoding", "host", "content-encoding", "trailer") }) {
            throw HttpProtocolException("Connection nominates a framing or routing field")
        }
        names.filter { !upgrade || it != "upgrade" }.forEach(headers::remove)
        listOf("Proxy-Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "TE", "Connection").forEach(headers::remove)
        if (!upgrade) headers.remove("Upgrade")
        else headers["Connection"] = "Upgrade"
    }

    fun bufferedFraming(headers: HttpHeaders, bytes: Long, noBody: Boolean = false, retainLength: Boolean = false) {
        headers.remove("Transfer-Encoding")
        headers.remove("Trailer")
        if (noBody) { if (!retainLength) headers.remove("Content-Length") }
        else headers["Content-Length"] = bytes.toString()
    }

    fun changedContent(headers: HttpHeaders) {
        listOf("Content-MD5", "Digest", "Content-Digest", "Repr-Digest", "ETag", "Last-Modified", "Content-Range").forEach(headers::remove)
    }

    suspend fun bytes(sink: ByteSink, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val part = if (offset == 0 && bytes.size <= CHUNK_BYTES) bytes else bytes.copyOfRange(offset, minOf(offset + CHUNK_BYTES, bytes.size))
            sink.write(part)
            offset += part.size
        }
    }

    suspend fun buffered(sink: ByteSink, chunks: List<ByteArray>) { chunks.forEach { bytes(sink, it) } }

    suspend fun stream(sink: ByteSink, body: BufferedBody) {
        val chunked = body.reader.framing == Framing.Chunked
        suspend fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            if (chunked) sink.write("${bytes.size.toString(16)}\r\n".toByteArray())
            bytes(sink, bytes)
            if (chunked) sink.write("\r\n".toByteArray())
        }
        body.original.forEach { write(it.bytes); it.lease.close() }
        body.original.clear()
        while (true) { write(body.next() ?: break) }
        if (chunked) bytes(sink, headers("0", body.reader.trailers))
    }
}
