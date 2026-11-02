package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyOverflow
import com.dylenpoki.dyltun.BodyState
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BodyOverflowMatrixTest {
    @Test fun sizeFallbackAndRejectPreserveAllFramingAndEncodingCombinations() = runBlocking {
        val decoded = ByteArray(64) { 65 }
        for (mode in listOf("identity", "gzip-encoded", "gzip-decoded")) {
            val gzip = mode != "identity"
            val encoded = if (gzip) ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(decoded) } }.toByteArray() else decoded
            for (framing in listOf(Framing.Fixed(encoded.size.toLong()), Framing.Chunked, Framing.Close)) {
                for (policy in BodyOverflow.entries) {
                    val budget = MemoryBudget(100000)
                    BodyAccount(budget).use { account ->
                        val limit = when (mode) {
                            "identity" -> BodyLimit(63, 128, overflow = policy)
                            "gzip-encoded" -> BodyLimit(encoded.size - 1L, 128, overflow = policy)
                            else -> BodyLimit(128, 63, overflow = policy)
                        }
                        val wire = if (framing == Framing.Chunked) "${encoded.size.toString(16)}\r\n".toByteArray() + encoded + "\r\n0\r\nX-End: yes\r\n\r\n".toByteArray() else encoded
                        val input = input(wire)
                        val body = BufferedBody(BodyReader(input, framing), this, account, limit)
                        val headers = if (gzip) HttpHeaders.of("Content-Encoding" to "gzip") else HttpHeaders()
                        if (policy == BodyOverflow.REJECT) {
                            val error = assertThrows(BodyRejection::class.java) { runBlocking { body.collect(headers) } }
                            assertEquals(if (mode == "gzip-decoded") BodyRejectionReason.DECODED_SIZE else BodyRejectionReason.ENCODED_SIZE, error.reason)
                        } else {
                            body.collect(headers)
                            assertEquals(BodyState.STREAMING, body.state)
                            assertNull(body.decoded)
                            val output = ByteArrayOutputStream()
                            HttpEncoder.stream(ByteSink { output.write(it) }, body)
                            val replay = BodyReader(input(output.toByteArray()), framing)
                            val payload = ByteArrayOutputStream()
                            while (true) { payload.write(replay.read() ?: break) }
                            assertArrayEquals("$mode/$framing", encoded, payload.toByteArray())
                            if (framing == Framing.Chunked) assertEquals("yes", replay.trailers["X-End"])
                        }
                    }
                    assertEquals(0L, budget.used)
                }
            }
        }
    }

    @Test fun deadlineAndBudgetHandleBothPoliciesWithoutConsumingThePrefix(): Unit = runBlocking {
        for (reason in listOf(BodyRejectionReason.TIMEOUT, BodyRejectionReason.BUDGET)) for (policy in BodyOverflow.entries) {
            val budget = MemoryBudget(if (reason == BodyRejectionReason.BUDGET) 1 else 100000)
            BodyAccount(budget).use { account ->
                var now = 0L
                var index = 0
                val bytes = "abcd".toByteArray()
                val source = ByteSource { if (index == bytes.size) null else {
                    now += 2_000_000
                    byteArrayOf(bytes[index++])
                } }
                val body = BufferedBody(BodyReader(HttpInput(source), Framing.Close), this, account,
                    BodyLimit(8, 8, 1, policy), com.dylenpoki.dyltun.internal.stack.MonotonicClock { now })
                if (policy == BodyOverflow.REJECT) {
                    assertEquals(reason, assertThrows(BodyRejection::class.java) { runBlocking { body.collect(HttpHeaders()) } }.reason)
                } else {
                    body.collect(HttpHeaders())
                    assertEquals(BodyState.STREAMING, body.state)
                    val output = ByteArrayOutputStream()
                    HttpEncoder.stream(ByteSink { output.write(it) }, body)
                    assertArrayEquals(bytes, output.toByteArray())
                }
            }
            assertEquals(0L, budget.used)
        }
    }

    private fun input(bytes: ByteArray): HttpInput {
        var index = 0
        return HttpInput(ByteSource { if (index == bytes.size) null else bytes.copyOfRange(index, minOf(index + 3, bytes.size)).also { index += it.size } })
    }
}
