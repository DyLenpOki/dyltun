package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimitExceededException
import com.dylenpoki.dyltun.BodyLimits
import com.dylenpoki.dyltun.BodyOverflow
import com.dylenpoki.dyltun.BodyState
import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.HttpRequest
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.TransportProtocol
import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyBudgetConcurrencyTest {
    @Test fun requestResponseAndHookCopiesCompeteWithAnotherConnection() = runBlocking {
        val budget = MemoryBudget(24)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val snapshots = mutableListOf<Long>()
        val limit = BodyLimit(8, 8, overflow = BodyOverflow.REJECT)
        val cfg = DyltunConfig.Builder().apply {
            bodyLimits = BodyLimits(limit, limit, 24)
            hooks = object : HttpHooks {
                override fun request(flow: HttpFlow) {
                    snapshots += budget.used
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    flow.response = HttpResponse.make(content = byteArrayOf(1, 2, 3, 4))
                }
                override fun response(flow: HttpFlow) {
                    snapshots += budget.used
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), flow.response!!.content)
                    snapshots += budget.used
                    assertThrows(BodyLimitExceededException::class.java) { flow.response!!.content = byteArrayOf(5) }
                }
            }
        }.build()
        HookExecutor().use { hooks ->
            val firstOutput = ByteArrayOutputStream()
            val first = async(Dispatchers.Default) { connection(cfg, budget, hooks, firstOutput, 1).run() }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertEquals(16L, budget.used)
                val secondOutput = ByteArrayOutputStream()
                withTimeout(2000) { connection(cfg, budget, hooks, secondOutput, 2).run() }
                assertTrue(secondOutput.toString("US-ASCII").startsWith("HTTP/1.1 503 "))
                assertEquals(16L, budget.used)
            } finally { release.countDown() }
            first.await()
            assertTrue(firstOutput.toString("US-ASCII").startsWith("HTTP/1.1 200 "))
        }
        assertEquals(listOf(16L, 20L, 24L), snapshots)
        assertEquals(0L, budget.used)
    }

    @Test fun gzipTemporaryBuffersAndReencodingStayWithinOneBudget(): Unit = runBlocking {
        val original = ByteArray(128) { 65 }
        val encoded = ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(original) } }.toByteArray()
        val budget = MemoryBudget(2048)
        val account = BodyAccount(budget)
        var read = false
        val body = BufferedBody(BodyReader(HttpInput(ByteSource { if (read) null else encoded.also { read = true } }), Framing.Fixed(encoded.size.toLong())),
            this, account, BodyLimit(256, 256))
        try {
            body.collect(HttpHeaders.of("Content-Encoding" to "gzip"))
            assertEquals(BodyState.BUFFERED, body.state)
            // Decode chunks have been released; both original encoded bytes and decoded Hook content remain.
            assertEquals(encoded.size.toLong() + original.size, budget.used)
            val request = HttpRequest("POST", "http://example.com", HttpHeaders.of("Content-Encoding" to "gzip"))
            request.loadBody(body.decoded, body.state, BodyLimit(256, 256), account.reserve)
            request.content = ByteArray(200) { 66 }
            val beforeEncoding = budget.used
            val chunks = encodeBody(request, BodyLimit(256, 256), account)
            assertEquals(beforeEncoding + chunks.sumOf { it.size.toLong() }, budget.used)
            assertArrayEquals(ByteArray(200) { 66 }, java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(chunks.fold(byteArrayOf()) { bytes, next -> bytes + next })).use { it.readBytes() })
        } finally { account.close() }
        assertEquals(0L, budget.used)
    }

    private fun connection(config: DyltunConfig, budget: MemoryBudget, hooks: HookExecutor, output: ByteArrayOutputStream, id: Long): Http1Connection {
        var position = 0
        val input = "POST / HTTP/1.1\r\nHost: example.com\r\nContent-Length: 8\r\nConnection: close\r\n\r\n12345678".toByteArray()
        val info = ConnectionInfo(id, TransportProtocol.TCP, InetAddress.getByName("10.0.0.1"), id.toInt() + 1000, InetAddress.getByName("198.51.100.1"), 80)
        return Http1Connection(config, info, emptyList(), budget, hooks, HttpConnector { error("Local/rejected response opened upstream") },
            ByteSource { if (position == input.size) null else input.copyOfRange(position, minOf(input.size, position + CHUNK_BYTES)).also { position += it.size } },
            ByteSink { output.write(it) })
    }
}
