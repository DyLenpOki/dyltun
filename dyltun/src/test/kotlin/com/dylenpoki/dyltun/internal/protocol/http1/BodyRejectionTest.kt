package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimits
import com.dylenpoki.dyltun.BodyOverflow
import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.TransportProtocol
import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyRejectionTest {
    @Test fun requestSizeRejectionSkipsBothHooksUpstreamAndPipelinedRequest() = runBlocking {
        for (framing in listOf("Content-Length: 9\r\n\r\n123456789", "Transfer-Encoding: chunked\r\n\r\n9\r\n123456789\r\n0\r\n\r\n")) {
            val wire = "POST / HTTP/1.1\r\nHost: e.com\r\n${framing}GET /next HTTP/1.1\r\nHost: e.com\r\n\r\n"
            val result = exchange(wire, limits = limits(BodyLimit(8, 8, overflow = BodyOverflow.REJECT)))
            assertError(result, 413, 0, 0, 0, BodyRejectionReason.ENCODED_SIZE)
        }
    }

    @Test fun responseSizeRejectionSends502WithoutAResponseHookAndClosesPeer() = runBlocking {
        val result = exchange("GET / HTTP/1.1\r\nHost: e.com\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n123456789",
            limits(response = BodyLimit(8, 8, overflow = BodyOverflow.REJECT)))
        assertError(result, 502, 1, 0, 1, BodyRejectionReason.ENCODED_SIZE)
        assertEquals(1, result.closed)
    }

    @Test fun aggregationDeadlineMapsTo408And504AndCancelsPendingRead() = runBlocking {
        for (request in listOf(true, false)) {
            val input = if (request) "POST / HTTP/1.1\r\nHost: e.com\r\nContent-Length: 1\r\n\r\n" else "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n"
            val limit = BodyLimit(8, 8, maxBufferingMillis = 30, overflow = BodyOverflow.REJECT)
            val result = withTimeout(2000) {
                exchange(input, "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\n", limits(limit, limit), blockRequest = request, blockResponse = !request)
            }
            assertError(result, if (request) 408 else 504, if (request) 0 else 1, 0, if (request) 0 else 1, BodyRejectionReason.TIMEOUT)
        }
    }

    @Test fun totalBudgetExhaustionReturns503ForEitherDirection() = runBlocking {
        for (request in listOf(true, false)) {
            val input = if (request) "POST / HTTP/1.1\r\nHost: e.com\r\nContent-Length: 6\r\n\r\n123456" else "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n"
            val limit = BodyLimit(8, 8, overflow = BodyOverflow.REJECT)
            val result = exchange(input, "HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\n123456", BodyLimits(limit, limit, maxTotalBufferedBytes = 8))
            assertError(result, 503, if (request) 0 else 1, 0, if (request) 0 else 1, BodyRejectionReason.BUDGET)
        }
    }

    @Test fun engineErrorToHeadContainsNoBody() = runBlocking {
        val result = exchange("HEAD / HTTP/1.1\r\nHost: e.com\r\nContent-Length: 9\r\n\r\n", limits = limits(BodyLimit(8, 8, overflow = BodyOverflow.REJECT)))
        assertError(result, 413, 0, 0, 0, BodyRejectionReason.ENCODED_SIZE)
        assertTrue(result.wire.endsWith("\r\n\r\n"))
    }

    private data class Result(val wire: String, val requests: Int, val responses: Int, val opens: Int, val closed: Int, val errors: List<Throwable>)
    private fun assertError(result: Result, status: Int, requests: Int, responses: Int, opens: Int, reason: BodyRejectionReason) {
        assertTrue(result.wire, result.wire.startsWith("HTTP/1.1 $status "))
        assertEquals(1, Regex("HTTP/1.1").findAll(result.wire).count())
        assertTrue(result.wire.contains("Content-Length: 0\r\n"))
        assertTrue(result.wire.contains("Connection: close\r\n"))
        assertEquals(requests, result.requests); assertEquals(responses, result.responses); assertEquals(opens, result.opens)
        assertEquals(reason, (result.errors.single() as BodyRejection).reason)
    }

    private suspend fun exchange(
        request: String, response: String = "", limits: BodyLimits,
        blockRequest: Boolean = false, blockResponse: Boolean = false
    ): Result {
        var requests = 0; var responses = 0; var opens = 0; var closed = 0
        val errors = mutableListOf<Throwable>()
        val output = ByteArrayOutputStream()
        val budget = MemoryBudget(limits.maxTotalBufferedBytes)
        val config = DyltunConfig.Builder().apply {
            bodyLimits = limits
            hooks = object : HttpHooks {
                override fun request(flow: HttpFlow) { requests++ }
                override fun response(flow: HttpFlow) { responses++ }
            }
        }.build()
        HookExecutor().use { executor ->
            val connector = HttpConnector {
                opens++
                object : HttpPeer {
                    override val source = source(response, blockResponse)
                    override val sink = ByteSink {}
                    override fun finish() {}
                    override fun close() { closed++ }
                }
            }
            val connection = ConnectionInfo(1, TransportProtocol.TCP, InetAddress.getByName("10.0.0.1"), 1000, InetAddress.getByName("198.51.100.1"), 80)
            Http1Connection(config, connection, emptyList(), budget, executor, connector, source(request, blockRequest), ByteSink { output.write(it) }, report = errors::add).run()
        }
        assertEquals(0L, budget.used)
        return Result(output.toString("US-ASCII"), requests, responses, opens, closed, errors)
    }
    private fun source(wire: String, blockAtEof: Boolean): ByteSource {
        val bytes = wire.toByteArray(Charsets.US_ASCII)
        var position = 0
        return ByteSource {
            if (position < bytes.size) bytes.copyOfRange(position, minOf(bytes.size, position + CHUNK_BYTES)).also { position += it.size }
            else if (blockAtEof) awaitCancellation() else null
        }
    }
    private fun limits(request: BodyLimit = BodyLimit(100, 100), response: BodyLimit = BodyLimit(100, 100)) = BodyLimits(request, response)
}
