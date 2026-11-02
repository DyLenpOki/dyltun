package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.internal.stream.ByteSource
import com.dylenpoki.dyltun.internal.stream.CHUNK_BYTES
import com.dylenpoki.dyltun.HttpHeaders
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpFramingTest {
    @Test fun everySplitPreservesChunkedBodyTrailersAndNextRequest() = runBlocking {
        val wire = "POST /a?q=1 HTTP/1.1\r\nHost: example.com\r\nTransfer-Encoding: chunked\r\n\r\n3;ext=yes\r\nabc\r\n2\r\nde\r\n0\r\nX-Check: ok\r\n\r\nGET /next HTTP/1.1\r\nHost: example.com\r\n\r\n"
        for (split in 1 until wire.length) {
            val input = input(wire, split)
            val head = HttpFraming.request(input)!!
            assertEquals("http://example.com/a?q=1", head.url)
            val reader = BodyReader(input, head.framing)
            assertEquals("abcde", readBody(reader))
            assertEquals("ok", reader.trailers["X-Check"])
            assertEquals("/next", HttpFraming.request(input)!!.target)
            assertNull(HttpFraming.request(input))
        }
    }

    @Test fun byteAtATimeAndCloseDelimitedResponse() = runBlocking {
        val bytes = "HTTP/1.1 200 OK\r\nX-Test: a\r\nX-Test: b\r\n\r\nhello".toByteArray()
        var position = 0
        val input = HttpInput(ByteSource { if (position == bytes.size) null else byteArrayOf(bytes[position++]) })
        val head = HttpFraming.response(input, "GET")
        assertEquals(listOf("a", "b"), head.headers.getAll("x-test"))
        assertEquals(Framing.Close, head.framing)
        assertEquals("hello", readBody(BodyReader(input, head.framing)))
    }

    @Test fun headAndNoBodyStatusesDoNotConsumeFollowingResponse() = runBlocking {
        val input = input("HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\nHTTP/1.1 304 Not Modified\r\nContent-Length: 888\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n", 7)
        assertEquals(Framing.Empty, HttpFraming.response(input, "HEAD").framing)
        assertEquals(Framing.Empty, HttpFraming.response(input, "GET").framing)
        assertEquals(Framing.Empty, HttpFraming.response(input, "GET").framing)
    }

    @Test fun ambiguousFramingAndHostAreRejected() = runBlocking {
        for (fields in listOf(
            "Content-Length: 1\r\nContent-Length: 2\r\n", "Content-Length: -1\r\n",
            "Content-Length: 9223372036854775808\r\n", "Transfer-Encoding: chunked\r\nContent-Length: 1\r\n",
            "Transfer-Encoding: gzip, chunked\r\n", "Host: other.example\r\n", " folded: bad\r\n")) {
            assertThrows(HttpProtocolException::class.java) { runBlocking {
                HttpFraming.request(input("GET / HTTP/1.1\r\nHost: example.com\r\n${fields}\r\n", 3))
            } }
        }
        assertThrows(HttpProtocolException::class.java) { runBlocking {
            HttpFraming.request(input("GET http://other.example/ HTTP/1.1\r\nHost: example.com\r\n\r\n", 1))
        } }
        assertEquals(Framing.Fixed(12), HttpFraming.framing(HttpHeaders.of("Content-Length" to "12", "content-length" to "12"), true))
    }

    @Test fun malformedChunkAndTruncationAreRejected(): Unit = runBlocking {
        for (wire in listOf("Z\r\n", "8000000000000000\r\n", "1\r\naX\r\n", "0\r\nHost: evil\r\n\r\n")) {
            assertThrows(HttpProtocolException::class.java) { runBlocking { readBody(BodyReader(input(wire, 1), Framing.Chunked)) } }
        }
        assertThrows(EOFException::class.java) { runBlocking { readBody(BodyReader(input("abc", 1), Framing.Fixed(4))) } }
        assertThrows(HttpProtocolException::class.java) { runBlocking { HttpFraming.request(input("GET / HTTP/1.1\nHost: example.com\n\n", 1)) } }
    }

    @Test fun headerAndFieldCountsAreBounded(): Unit = runBlocking {
        val fields = (1..129).joinToString("") { "X-$it: v\r\n" }
        assertThrows(HttpProtocolException::class.java) { runBlocking { HttpFraming.request(input("GET / HTTP/1.1\r\nHost: e.com\r\n$fields\r\n", 7)) } }
        assertThrows(HttpProtocolException::class.java) { runBlocking { HttpFraming.request(input("GET / HTTP/1.1\r\nHost: e.com\r\nX: ${"a".repeat(8192)}\r\n\r\n", 7)) } }
    }

    private fun input(wire: String, split: Int): HttpInput {
        val bytes = wire.toByteArray(Charsets.ISO_8859_1)
        var position = 0
        return HttpInput(ByteSource {
            if (position == bytes.size) null else bytes.copyOfRange(position, minOf(bytes.size, position + split, position + CHUNK_BYTES)).also { position += it.size }
        })
    }
    private suspend fun readBody(reader: BodyReader): String {
        val output = ByteArrayOutputStream()
        while (true) { output.write(reader.read() ?: break) }
        return output.toString("UTF-8")
    }
}
