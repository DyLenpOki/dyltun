package com.dylenpoki.dyltun

import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpModelTest {
    @Test fun duplicateHeadersPreserveOrderAndSetReplacesAll() {
        val headers = HttpHeaders.of("Set-Cookie" to "a=1", "X-Test" to "one", "set-cookie" to "b=2")
        assertEquals(listOf("a=1", "b=2"), headers.getAll("SET-COOKIE"))
        headers["set-cookie"] = "c=3"
        assertEquals(listOf("set-cookie" to "c=3", "X-Test" to "one"), headers.entries())
        val copy = headers.copy()
        copy.remove("x-test")
        assertEquals("one", headers["X-Test"])
    }

    @Test fun headersRejectInjectionAndNonLatin1WireValues() {
        for ((name, value) in listOf("bad name" to "x", "X" to "a\r\nB: injected", "X:" to "x", "X" to "中文")) {
            assertThrows(IllegalArgumentException::class.java) { HttpHeaders.of(name to value) }
        }
    }

    @Test fun requestPathUrlAndHostStayConsistent() {
        val request = HttpRequest("GET", "http://one.example/a%20b?q=x")
        assertEquals("/a%20b?q=x", request.path)
        request.url = "http://two.example:8080/new?raw=%2F"
        assertEquals("two.example:8080", request.headers["Host"])
        request.path = "/changed?k=1"
        assertEquals("http://two.example:8080/changed?k=1", request.url)
        request.path = "/中文?q=值"
        assertEquals("/%E4%B8%AD%E6%96%87?q=%E5%80%BC", request.path)
        assertThrows(IllegalArgumentException::class.java) { request.path = "//attacker.example/x" }
        assertThrows(IllegalArgumentException::class.java) { request.url = "https://example.com" }
        assertThrows(IllegalArgumentException::class.java) { request.method = "GET\r\nX" }
    }

    @Test fun byteContentUsesCopiesAndEmptyDiffersFromUnavailable() {
        val bytes = byteArrayOf(65)
        val response = HttpResponse.make(content = bytes)
        bytes[0] = 66
        response.content!![0] = 67
        assertEquals("A", response.text)
        response.content = byteArrayOf()
        assertEquals("", response.text)
        assertThrows(IllegalArgumentException::class.java) { response.content = null }
        response.bodyState = BodyState.STREAMING
        assertNull(response.content)
        assertNull(response.text)
        assertThrows(IllegalStateException::class.java) { response.text = "new" }
    }

    @Test fun textEncodingIsStrictAndHonorsCharset() {
        val response = HttpResponse.make(content = "中文")
        assertEquals("中文", response.text)
        assertArrayEquals("中文".toByteArray(), response.content)
        assertEquals("text/plain; charset=UTF-8", response.headers["Content-Type"])
        val latin = HttpResponse.make(content = "café", charset = Charsets.ISO_8859_1)
        assertEquals(4, latin.content!!.size)
        assertEquals("café", latin.text)
        assertThrows(IllegalArgumentException::class.java) {
            HttpResponse.make(content = "中文", headers = HttpHeaders.of("Content-Type" to "text/plain; charset=ascii"))
        }
        assertThrows(CharacterCodingException::class.java) { response.text = "\uD800" }
        response.content = byteArrayOf(0xc3.toByte(), 0x28)
        assertNull(response.text)
    }

    @Test fun localResponseOwnershipAndResponseCallbackContract() {
        val limits = BodyLimits()
        val first = HttpFlow(1, connection(), HttpRequest("GET", "http://example.com"), limits)
        val second = HttpFlow(2, connection(), HttpRequest("GET", "http://example.com"), limits)
        val response = HttpResponse.make(201, "local")
        first.response = response
        assertEquals(ResponseSource.LOCAL, first.responseSource)
        assertThrows(IllegalStateException::class.java) { second.response = response }
        first.beginResponseCallback()
        assertThrows(IllegalStateException::class.java) { first.response = null }
        val upstream = HttpFlow(3, connection(), HttpRequest("GET", "http://example.com"), limits)
        upstream.receiveUpstream(HttpResponse.make())
        assertEquals(ResponseSource.UPSTREAM, upstream.responseSource)
    }

    @Test fun attachedBodiesCheckExactByteBoundariesBeforeReplacing() {
        val limit = BodyLimit(6, 6)
        val flow = HttpFlow(1, connection(), HttpRequest("POST", "http://example.com"), BodyLimits(limit, limit))
        flow.response = HttpResponse.make(content = "中文")
        assertEquals(6, flow.response!!.content!!.size)
        assertThrows(BodyLimitExceededException::class.java) { flow.response!!.text = "中文a" }
        assertEquals("中文", flow.response!!.text)
        assertThrows(BodyLimitExceededException::class.java) { flow.request.content = ByteArray(7) }
        assertThrows(BodyLimitExceededException::class.java) { flow.response = HttpResponse.make(content = ByteArray(7)) }
        flow.request.content = ByteArray(5)
        flow.request.content = ByteArray(6)
    }

    @Test fun makeSupportsEmptyAndRejectsInformationalStatuses() {
        assertEquals(200, HttpResponse.make().statusCode)
        assertEquals(0, HttpResponse.make().content!!.size)
        assertThrows(IllegalArgumentException::class.java) { HttpResponse.make(101) }
        assertThrows(IllegalArgumentException::class.java) { HttpResponse.make(600) }
    }
}
