package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimitExceededException
import com.dylenpoki.dyltun.BodyOverflow
import com.dylenpoki.dyltun.BodyState
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BodyBufferTest {
    @Test fun identityExactLimitsAndBothOverflowPolicies(): Unit = runBlocking {
        for (size in 7..9) for (policy in BodyOverflow.entries) {
            val budget = MemoryBudget(100000)
            BodyAccount(budget).use { account ->
                val body = buffer(ByteArray(size) { 65 }, Framing.Fixed(size.toLong()), BodyLimit(8, 8, overflow = policy), account, this)
                if (size > 8 && policy == BodyOverflow.REJECT) {
                    assertThrows(BodyRejection::class.java) { runBlocking { body.collect(HttpHeaders()) } }
                } else {
                    body.collect(HttpHeaders())
                    assertEquals(if (size > 8) BodyState.STREAMING else BodyState.BUFFERED, body.state)
                    if (size <= 8) assertEquals(size, body.decoded!!.size)
                    else assertArrayEquals(ByteArray(size) { 65 }, stream(body))
                }
            }
            assertEquals(0L, budget.used)
        }
    }

    @Test fun gzipDecodedBoundaryAndOriginalBytesSurviveBombFallback(): Unit = runBlocking {
        for (size in 63..65) for (policy in BodyOverflow.entries) {
            val encoded = gzip(ByteArray(size) { 65 })
            val budget = MemoryBudget(100000)
            BodyAccount(budget).use { account ->
                val body = buffer(encoded, Framing.Fixed(encoded.size.toLong()), BodyLimit(128, 64, overflow = policy), account, this)
                if (size > 64 && policy == BodyOverflow.REJECT) assertThrows(BodyRejection::class.java) {
                    runBlocking { body.collect(HttpHeaders.of("Content-Encoding" to "gzip")) }
                } else {
                    body.collect(HttpHeaders.of("Content-Encoding" to "gzip"))
                    if (size > 64) {
                        assertEquals(BodyState.STREAMING, body.state)
                        assertArrayEquals(encoded, stream(body))
                    } else assertArrayEquals(ByteArray(size) { 65 }, body.decoded)
                }
            }
            assertEquals(0L, budget.used)
        }
    }

    @Test fun sessionBudgetFallbackAndHookCopiesAreAccounted(): Unit = runBlocking {
        val budget = MemoryBudget(64)
        BodyAccount(budget).use { first ->
            first.reserve(60)
            BodyAccount(budget).use { second ->
                val body = buffer(ByteArray(10), Framing.Fixed(10), BodyLimit(100, 100), second, this)
                body.collect(HttpHeaders())
                assertEquals(BodyState.STREAMING, body.state)
                assertArrayEquals(ByteArray(10), stream(body))
                assertEquals(60L, budget.used)
            }
            val response = HttpResponse.make(content = ByteArray(4))
            response.bindAllocation(first.reserve)
            assertThrows(BodyLimitExceededException::class.java) { response.content }
        }
        assertEquals(0L, budget.used)
    }

    @Test fun timeoutMidChunkLineKeepsTheSuspendedReadForStreaming(): Unit = runBlocking {
        val chunks = Channel<ByteArray>(4)
        chunks.send("3\r\nabc\r\n".toByteArray())
        chunks.send("2;pa".toByteArray())
        val input = HttpInput(ByteSource { chunks.receiveCatching().getOrNull() })
        val budget = MemoryBudget(100000)
        BodyAccount(budget).use { account ->
            val body = BufferedBody(BodyReader(input, Framing.Chunked), this, account, BodyLimit(100, 100, maxBufferingMillis = 30))
            body.collect(HttpHeaders())
            assertEquals(BodyState.STREAMING, body.state)
            chunks.send("rt=yes\r\nde\r\n0\r\nX-End: yes\r\n\r\n".toByteArray())
            chunks.close()
            val output = stream(body).toString(Charsets.US_ASCII)
            assertEquals("3\r\nabc\r\n2\r\nde\r\n0\r\nX-End: yes\r\n\r\n", output)
        }
        assertEquals(0L, budget.used)
    }

    @Test fun monotonicDeadlineAndOpaqueEncoding(): Unit = runBlocking {
        val budget = MemoryBudget(100000)
        BodyAccount(budget).use { account ->
            var now = 0L
            val input = HttpInput(ByteSource { now += 2_000_000; "a".toByteArray() })
            val body = BufferedBody(BodyReader(input, Framing.Close), this, account, BodyLimit(10, 10, 1), MonotonicClock { now })
            body.collect(HttpHeaders())
            assertEquals(BodyState.STREAMING, body.state)
            val opaque = buffer("opaque".toByteArray(), Framing.Fixed(6), BodyLimit(10, 10), account, this)
            opaque.collect(HttpHeaders.of("Content-Encoding" to "br"))
            assertEquals(BodyState.OPAQUE, opaque.state)
            assertNull(opaque.decoded)
            assertEquals("opaque", stream(opaque).toString(Charsets.UTF_8))
        }
    }

    private fun buffer(bytes: ByteArray, framing: Framing, limit: BodyLimit, account: BodyAccount, scope: CoroutineScope): BufferedBody {
        var position = 0
        val input = HttpInput(ByteSource {
            if (position == bytes.size) null else bytes.copyOfRange(position, minOf(bytes.size, position + 3)).also { position += it.size }
        })
        return BufferedBody(BodyReader(input, framing), scope, account, limit)
    }
    private suspend fun stream(body: BufferedBody): ByteArray = ByteArrayOutputStream().also { output ->
        HttpEncoder.stream(ByteSink { output.write(it) }, body)
    }.toByteArray()
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
}
