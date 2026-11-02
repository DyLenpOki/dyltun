package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimitExceededException
import com.dylenpoki.dyltun.BodyLimits
import com.dylenpoki.dyltun.BodyOverflow
import com.dylenpoki.dyltun.BodyState
import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.ConnectionRule
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.ResponseSource
import com.dylenpoki.dyltun.RoutingContext
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.TransportProtocol
import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Http1ConnectionTest {
    @Test fun requestCompletionDoesNotHalfCloseUpstreamBeforeItsResponse() = runBlocking {
        for (request in listOf(
            "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n",
            "POST / HTTP/1.1\r\nHost: e.com\r\nContent-Length: 4\r\n\r\ndata"
        )) {
            var halfClosed = false
            var replied = false
            val received = ByteArrayOutputStream()
            val connector = HttpConnector {
                object : HttpPeer {
                    override val source = ByteSource {
                        // Some HTTP frontends abort processing when the client sends an early FIN.
                        if (halfClosed || replied) null else {
                            replied = true
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
                        }
                    }
                    override val sink = ByteSink { received.write(it) }
                    override fun finish() { halfClosed = true }
                    override fun close() {}
                }
            }
            val wire = run(config(object : HttpHooks {}), request.toByteArray(), connector)
            assertFalse("An HTTP message boundary is not a TCP half-close", halfClosed)
            assertTrue(received.toString("US-ASCII").contains("Connection: close\r\n"))
            assertTrue(wire.toString(Charsets.US_ASCII).startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(wire.toString(Charsets.US_ASCII).endsWith("ok"))
        }
    }

    @Test fun localPipelinedResponsesNeverOpenUpstreamAndHaveOneResponseHook() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val hooks = object : HttpHooks {
            override fun request(flow: HttpFlow) {
                assertTrue(Thread.currentThread().name.startsWith("dyltun-http-hook"))
                calls += "request:${flow.id}"
                flow.response = HttpResponse.make(201, "local:${flow.request.path}")
            }
            override fun response(flow: HttpFlow) {
                assertEquals(ResponseSource.LOCAL, flow.responseSource)
                calls += "response:${flow.id}"
                flow.response!!.headers["X-Local"] = "yes"
            }
        }
        val connector = FakeConnector()
        val wire = run(config(hooks), "GET /one HTTP/1.1\r\nHost: e.com\r\n\r\nGET /two HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector)
        assertEquals(0, connector.requests.size)
        assertEquals(listOf("request:1", "response:1", "request:2", "response:2"), calls)
        val input = source(wire)
        repeat(2) { index ->
            val response = HttpFraming.response(input, "GET")
            assertEquals(201, response.status)
            assertEquals("yes", response.headers["X-Local"])
            assertEquals(if (index == 0) "local:/one" else "local:/two", body(input, response.framing).toString(Charsets.UTF_8))
        }
        assertNull(input.line(allowEof = true))
    }

    @Test fun requestAndGzipResponseChangesReframeAndRemoveStaleValidators() = runBlocking {
        val encoded = gzip("before".toByteArray())
        val response = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nETag: old\r\nConnection: close\r\nContent-Length: ${encoded.size}\r\n\r\n".toByteArray() + encoded
        val connector = FakeConnector(response)
        val hooks = object : HttpHooks {
            override fun request(flow: HttpFlow) {
                assertEquals("hello", flow.request.text)
                flow.request.path = "/changed"
                flow.request.text = "longer request"
            }
            override fun response(flow: HttpFlow) {
                assertEquals(ResponseSource.UPSTREAM, flow.responseSource)
                assertEquals("before", flow.response!!.text)
                flow.response!!.text = "changed response with a new length"
            }
        }
        val wire = run(config(hooks), "POST / HTTP/1.1\r\nHost: e.com\r\nTransfer-Encoding: chunked\r\nContent-MD5: old\r\n\r\n5\r\nhello\r\n0\r\n\r\n".toByteArray(), connector)
        val requestInput = source(connector.requests.single().toByteArray())
        val request = HttpFraming.request(requestInput)!!
        assertEquals("/changed", request.target)
        assertNull(request.headers["Content-MD5"])
        assertNull(request.headers["Transfer-Encoding"])
        assertEquals("longer request", body(requestInput, request.framing).toString(Charsets.UTF_8))
        val input = source(wire)
        val head = HttpFraming.response(input, "GET")
        assertNull(head.headers["ETag"])
        assertNull(head.headers["Connection"])
        assertEquals("changed response with a new length", GZIPInputStream(ByteArrayInputStream(body(input, head.framing))).use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test fun streamingPreservesPayloadAndRejectsFramingChanges(): Unit = runBlocking {
        var state: BodyState? = null
        val hooks = object : HttpHooks {
            override fun request(flow: HttpFlow) { state = flow.request.bodyState; assertNull(flow.request.content); flow.request.headers["X-Test"] = "stream" }
        }
        val request = "POST / HTTP/1.1\r\nHost: e.com\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n6\r\ndefghi\r\n0\r\nX-Trailer: yes\r\n\r\n".toByteArray()
        val connector = FakeConnector("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
        val cfg = config(hooks, BodyLimits(BodyLimit(8, 8), BodyLimit(8, 8)))
        run(cfg, request, connector)
        assertEquals(BodyState.STREAMING, state)
        val input = source(connector.requests.single().toByteArray())
        val head = HttpFraming.request(input)!!
        val reader = BodyReader(input, head.framing)
        assertEquals("abcdefghi", all(reader).toString(Charsets.UTF_8))
        assertEquals("yes", reader.trailers["X-Trailer"])
        val invalid = config(object : HttpHooks { override fun request(flow: HttpFlow) { flow.request.headers["Content-Length"] = "0" } }, cfg.bodyLimits)
        assertThrows(HttpProtocolException::class.java) { runBlocking { run(invalid, request, FakeConnector()) } }
    }

    @Test fun rejectsIncomingAndLocalOverflowBeforeSendingFinalHeaders(): Unit = runBlocking {
        val cfg = config(object : HttpHooks {}, BodyLimits(BodyLimit(4, 4, overflow = BodyOverflow.REJECT), BodyLimit(4, 4)))
        val connector = FakeConnector()
        val rejected = run(cfg, "POST / HTTP/1.1\r\nHost: e.com\r\nContent-Length: 5\r\n\r\nhello".toByteArray(), connector)
        assertTrue(rejected.toString(Charsets.US_ASCII).startsWith("HTTP/1.1 413 "))
        assertEquals(0, connector.requests.size)
        val local = config(object : HttpHooks { override fun request(flow: HttpFlow) { flow.response = HttpResponse.make(content = "12345") } }, cfg.bodyLimits)
        assertThrows(BodyLimitExceededException::class.java) { runBlocking { run(local, "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector) } }
        assertEquals(0, connector.requests.size)
    }

    @Test fun urlAuthorityChangesDestinationAndExplicitRedirectWins() = runBlocking {
        val hooks = object : HttpHooks { override fun request(flow: HttpFlow) { flow.request.url = "http://changed.example:8080/new" } }
        val connector = FakeConnector("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
        run(config(hooks), "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector)
        assertEquals(Endpoint("changed.example", 8080), connector.destinations.single())
        val cfg = DyltunConfig.Builder().apply {
            this.hooks = hooks
            routing { rule(object : ConnectionRule { override fun evaluate(context: RoutingContext) = RoutingDecision.Redirect(Endpoint("127.0.0.1", 8888)) }) }
        }.build()
        run(cfg, "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector)
        assertEquals(Endpoint("127.0.0.1", 8888), connector.destinations.last())
    }

    @Test fun upgradeForwardsRemainingBytesInBothDirections() = runBlocking {
        var responses = 0
        val connector = FakeConnector("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: test\r\n\r\nserver-raw".toByteArray())
        val cfg = config(object : HttpHooks { override fun response(flow: HttpFlow) { responses++; assertEquals(101, flow.response!!.statusCode) } })
        val wire = run(cfg, "GET / HTTP/1.1\r\nHost: e.com\r\nConnection: Upgrade\r\nUpgrade: test\r\n\r\nclient-raw".toByteArray(), connector)
        assertEquals(1, responses)
        val input = source(wire)
        assertEquals(101, HttpFraming.response(input, "GET").status)
        assertEquals("server-raw", input.read()!!.toString(Charsets.UTF_8))
        assertTrue(connector.requests.single().toString("UTF-8").endsWith("client-raw"))
    }

    @Test fun expectContinueIsAnsweredBeforeReadingBodyAndNotForwarded() = runBlocking {
        val incoming = kotlinx.coroutines.channels.Channel<ByteArray>(2)
        incoming.send("POST / HTTP/1.1\r\nHost: e.com\r\nExpect: 100-continue\r\nContent-Length: 5\r\n\r\n".toByteArray())
        val connector = FakeConnector("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
        val output = ByteArrayOutputStream()
        HookExecutor().use { executor ->
            Http1Connection(config(object : HttpHooks {}), connection(), emptyList(), MemoryBudget(100000), executor, connector,
                ByteSource { incoming.receiveCatching().getOrNull() }, ByteSink { bytes ->
                    output.write(bytes)
                    if (bytes.toString(Charsets.US_ASCII).startsWith("HTTP/1.1 100")) { incoming.send("hello".toByteArray()); incoming.close() }
                }).run()
        }
        assertEquals(1, Regex("100 Continue").findAll(output.toString("UTF-8")).count())
        assertFalse(connector.requests.single().toString("UTF-8").contains("Expect:"))
        assertTrue(connector.requests.single().toString("UTF-8").endsWith("hello"))
    }

    @Test fun headAndNotModifiedKeepRepresentationLengthWithoutSendingContent() = runBlocking {
        for ((method, status) in listOf("HEAD" to "200 OK", "GET" to "304 Not Modified")) {
            val connector = FakeConnector("HTTP/1.1 $status\r\nContent-Length: 1234\r\n\r\n".toByteArray())
            val output = run(config(object : HttpHooks {}), "$method / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector)
            val input = source(output)
            val head = HttpFraming.response(input, method)
            assertEquals("1234", head.headers["Content-Length"])
            assertEquals(Framing.Empty, head.framing)
            assertNull(input.read())
        }
    }

    @Test fun methodRewriteKeepsTheOriginalClientsHeadSemantics() = runBlocking {
        for ((original, rewritten) in listOf("GET" to "HEAD", "HEAD" to "GET")) {
            val response = "HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\n" + if (rewritten == "GET") "payload" else ""
            val connector = FakeConnector(response.toByteArray())
            val cfg = config(object : HttpHooks { override fun request(flow: HttpFlow) { flow.request.method = rewritten } })
            val wire = run(cfg, "$original / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector)
            val input = source(wire)
            val head = HttpFraming.response(input, original)
            assertEquals(if (original == "HEAD") "7" else "0", head.headers["Content-Length"])
            assertNull(input.read())
            assertTrue(connector.requests.single().toString("US-ASCII").startsWith("$rewritten / HTTP/1.1\r\n"))
        }
    }

    @Test fun upgradeHookCannotInsertFramingOrChangeNegotiatedProtocol(): Unit = runBlocking {
        for (field in listOf("Upgrade" to "other", "Content-Length" to "1", "Connection" to "close")) {
            val response = "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: test\r\n\r\n"
            val connector = FakeConnector(response.toByteArray())
            val cfg = config(object : HttpHooks { override fun response(flow: HttpFlow) { flow.response!!.headers[field.first] = field.second } })
            assertThrows(HttpProtocolException::class.java) { runBlocking {
                run(cfg, "GET / HTTP/1.1\r\nHost: e.com\r\nConnection: Upgrade\r\nUpgrade: test\r\n\r\n".toByteArray(), connector)
            } }
            assertEquals(1, connector.closed)
        }
    }

    @Test fun localResponseToUnreadStreamingUploadClosesWithoutReadingRemainder() = runBlocking {
        val incoming = kotlinx.coroutines.channels.Channel<ByteArray>(1)
        incoming.send("POST / HTTP/1.1\r\nHost: e.com\r\nContent-Length: 999\r\n\r\n".toByteArray())
        val cfg = config(object : HttpHooks {
            override fun request(flow: HttpFlow) { flow.response = HttpResponse.make(403, "denied") }
        }, BodyLimits(BodyLimit(8, 8), BodyLimit(8, 8)))
        val connector = FakeConnector()
        val output = ByteArrayOutputStream()
        HookExecutor().use { executor ->
            withTimeout(1000) {
                Http1Connection(cfg, connection(), emptyList(), MemoryBudget(100000), executor, connector,
                    ByteSource { incoming.receiveCatching().getOrNull() }, ByteSink { output.write(it) }).run()
            }
        }
        assertEquals(0, connector.requests.size)
        assertTrue(output.toString("UTF-8").contains("Connection: close\r\n"))
        assertTrue(output.toString("UTF-8").endsWith("denied"))
        incoming.cancel()
    }

    @Test fun earlyUpstreamFinalDoesNotWaitForUnfinishedUpload() = runBlocking {
        val incoming = kotlinx.coroutines.channels.Channel<ByteArray>(1)
        incoming.send("POST / HTTP/1.1\r\nHost: e.com\r\nContent-Length: 999\r\n\r\n".toByteArray())
        val cfg = config(object : HttpHooks {}, BodyLimits(BodyLimit(8, 8), BodyLimit(8, 8)))
        val output = ByteArrayOutputStream()
        val final = "HTTP/1.1 413 Content Too Large\r\nContent-Length: 0\r\n\r\n".toByteArray()
        var delivered = false
        HookExecutor().use { executor ->
            withTimeout(1000) {
                Http1Connection(cfg, connection(), emptyList(), MemoryBudget(100000), executor,
                    HttpConnector { object : HttpPeer {
                        override val source = ByteSource { if (delivered) null else final.also { delivered = true } }
                        override val sink = ByteSink {}
                        override fun finish() {}
                        override fun close() {}
                    } }, ByteSource { incoming.receiveCatching().getOrNull() }, ByteSink { output.write(it) }).run()
            }
        }
        assertTrue(output.toString("UTF-8").startsWith("HTTP/1.1 413 "))
        assertTrue(output.toString("UTF-8").contains("Connection: close\r\n"))
        incoming.cancel()
    }

    @Test fun finalRedirectDenialIsNotBypassedByEarlierForwardRule(): Unit = runBlocking {
        val cfg = DyltunConfig.Builder().apply {
            hooks = object : HttpHooks { override fun request(flow: HttpFlow) { flow.request.url = "http://changed.example:8080/" } }
            routing {
                rule(object : ConnectionRule { override fun evaluate(context: RoutingContext) = RoutingDecision.Forward })
                rule(object : ConnectionRule {
                    override fun evaluate(context: RoutingContext) = if (context.destination.host == "changed.example") RoutingDecision.Reset else RoutingDecision.Continue
                })
            }
        }.build()
        val connector = FakeConnector()
        assertThrows(HttpProtocolException::class.java) { runBlocking { run(cfg, "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector) } }
        assertEquals(0, connector.requests.size)
    }

    @Test fun responseReplacementInvokesOnlyOneCallbackAndClosesOldPeer() = runBlocking {
        var callbacks = 0
        val connector = FakeConnector("HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\nunused".toByteArray())
        val cfg = config(object : HttpHooks {
            override fun response(flow: HttpFlow) {
                callbacks++
                assertEquals(BodyState.STREAMING, flow.response!!.bodyState)
                flow.response = HttpResponse.make(202, "local replacement")
            }
        }, BodyLimits(BodyLimit(100, 100), BodyLimit(32, 32)))
        val output = run(cfg, "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector)
        assertEquals(1, callbacks)
        assertEquals(1, connector.closed)
        assertTrue(output.toString(Charsets.UTF_8).endsWith("local replacement"))
    }

    @Test fun upstreamConnectFailureCanProduceOneLocalResponseAndClosesPipeline() = runBlocking {
        val calls = mutableListOf<String>()
        val failure = IOException("connect failed")
        val hooks = object : HttpHooks {
            override fun request(flow: HttpFlow) { calls += "request:${flow.id}" }
            override fun error(flow: HttpFlow, cause: Throwable) {
                assertSame(failure, cause)
                assertTrue(Thread.currentThread().name.startsWith("dyltun-http-hook"))
                calls += "error:${flow.id}"
                flow.response = HttpResponse.make(200, "fallback")
            }
            override fun response(flow: HttpFlow) {
                assertEquals(ResponseSource.LOCAL, flow.responseSource)
                calls += "response:${flow.id}"
            }
        }
        val wire = run(config(hooks), ("GET /one HTTP/1.1\r\nHost: e.com\r\n\r\n" +
            "GET /two HTTP/1.1\r\nHost: e.com\r\n\r\n").toByteArray(), HttpConnector { throw failure })
        assertEquals(listOf("request:1", "error:1", "response:1"), calls)
        assertTrue(wire.toString(Charsets.UTF_8).contains("Connection: close\r\n"))
        assertTrue(wire.toString(Charsets.UTF_8).endsWith("fallback"))
    }

    @Test fun emptyUpstreamResponseCanRecoverAndClosesPeer() = runBlocking {
        val connector = FakeConnector()
        val hooks = object : HttpHooks {
            override fun error(flow: HttpFlow, cause: Throwable) {
                assertTrue(cause is java.io.EOFException)
                flow.response = HttpResponse.make(503, "offline")
            }
        }
        val wire = run(config(hooks), "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector)
        assertTrue(wire.toString(Charsets.UTF_8).startsWith("HTTP/1.1 503 "))
        assertTrue(wire.toString(Charsets.UTF_8).endsWith("offline"))
        assertEquals(1, connector.closed)
    }

    @Test fun upstreamEofAfterCompleteInterimResponseCanRecover() = runBlocking {
        var responses = 0
        var errors = 0
        val connector = FakeConnector("HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\n".toByteArray())
        val hooks = object : HttpHooks {
            override fun error(flow: HttpFlow, cause: Throwable) {
                assertTrue(cause is java.io.EOFException)
                errors++
                flow.response = HttpResponse.make(503, "offline")
            }
            override fun response(flow: HttpFlow) { responses++ }
        }
        val wire = run(config(hooks), "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector)
        val input = source(wire)
        assertEquals(103, HttpFraming.response(input, "GET").status)
        val final = HttpFraming.response(input, "GET")
        assertEquals(503, final.status)
        assertEquals("offline", body(input, final.framing).toString(Charsets.UTF_8))
        assertEquals(1, errors)
        assertEquals(1, responses)
        assertEquals(1, connector.closed)
    }

    @Test fun upgradedUpstreamFailureCannotAppendHttpFallback(): Unit = runBlocking {
        var errors = 0
        var responses = 0
        var closed = 0
        val failure = IOException("upgraded peer reset")
        val hooks = object : HttpHooks {
            override fun response(flow: HttpFlow) { responses++ }
            override fun error(flow: HttpFlow, cause: Throwable) {
                errors++
                flow.response = HttpResponse.make(503, "must not appear")
            }
        }
        val connector = HttpConnector {
            var sent = false
            object : HttpPeer {
                override val source = ByteSource {
                    if (sent) throw failure
                    sent = true
                    "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: test\r\n\r\n".toByteArray()
                }
                override val sink = ByteSink {}
                override fun finish() {}
                override fun close() { closed++ }
            }
        }
        val output = ByteArrayOutputStream()
        val budget = MemoryBudget(100000)
        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                HookExecutor().use { executor ->
                    Http1Connection(config(hooks), connection(), emptyList(), budget, executor, connector,
                        sourceChunks("GET / HTTP/1.1\r\nHost: e.com\r\nConnection: Upgrade\r\nUpgrade: test\r\n\r\n".toByteArray()),
                        ByteSink { output.write(it) }).run()
                }
            }
        }
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure })
        val input = source(output.toByteArray())
        assertEquals(101, HttpFraming.response(input, "GET").status)
        assertEquals(null, input.read())
        assertEquals(0, errors)
        assertEquals(1, responses)
        assertEquals(1, closed)
        assertEquals(0L, budget.used)
    }

    @Test fun requestAndResponseHookIoErrorsAreNeverOfferedForRecovery(): Unit = runBlocking {
        for (failRequest in listOf(true, false)) {
            var errors = 0
            val failure = IOException("hook failure")
            val hooks = object : HttpHooks {
                override fun request(flow: HttpFlow) { if (failRequest) throw failure }
                override fun response(flow: HttpFlow) { throw failure }
                override fun error(flow: HttpFlow, cause: Throwable) { errors++; flow.response = HttpResponse.make() }
            }
            val connector = FakeConnector("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
            val thrown = assertThrows(IOException::class.java) {
                runBlocking { run(config(hooks), "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector) }
            }
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure })
            assertEquals(0, errors)
            if (failRequest) assertTrue(connector.requests.isEmpty())
        }
    }

    @Test fun upstreamFailureAfterFinalHeadersCannotAppendFallback(): Unit = runBlocking {
        var errors = 0
        val hooks = object : HttpHooks {
            override fun error(flow: HttpFlow, cause: Throwable) { errors++; flow.response = HttpResponse.make() }
        }
        val connector = HttpConnector {
            var first = true
            object : HttpPeer {
                override val source = ByteSource {
                    if (first) { first = false; "HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\n".toByteArray() }
                    else throw IOException("upstream reset")
                }
                override val sink = ByteSink {}
                override fun finish() {}
                override fun close() {}
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking { run(config(hooks, BodyLimits(response = BodyLimit(4, 4))),
                "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), connector) }
        }
        assertEquals(0, errors)
    }

    @Test fun errorFallbackStillEnforcesResponseBodyLimits(): Unit = runBlocking {
        val hooks = object : HttpHooks {
            override fun error(flow: HttpFlow, cause: Throwable) { flow.response = HttpResponse.make(200, "too large") }
        }
        assertThrows(BodyLimitExceededException::class.java) {
            runBlocking { run(config(hooks, BodyLimits(response = BodyLimit(4, 4))),
                "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(), HttpConnector { throw IOException("offline") }) }
        }
    }

    @Test fun errorWithoutReplacementPreservesOriginalFailure(): Unit = runBlocking {
        val failure = IOException("offline")
        val thrown = assertThrows(IOException::class.java) {
            runBlocking { run(config(object : HttpHooks {}), "GET / HTTP/1.1\r\nHost: e.com\r\n\r\n".toByteArray(),
                HttpConnector { throw failure }) }
        }
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure })
    }

    private suspend fun run(config: DyltunConfig, bytes: ByteArray, connector: HttpConnector): ByteArray {
        val output = ByteArrayOutputStream()
        val budget = MemoryBudget(config.bodyLimits.maxTotalBufferedBytes)
        try {
            HookExecutor().use { executor ->
                Http1Connection(config, connection(), emptyList(), budget, executor, connector, sourceChunks(bytes), ByteSink { output.write(it) }).run()
            }
        } finally { assertEquals("Body budget leaked", 0L, budget.used) }
        return output.toByteArray()
    }
    private fun config(hooks: HttpHooks, limits: BodyLimits = BodyLimits()) = DyltunConfig.Builder().apply { this.hooks = hooks; bodyLimits = limits }.build()
    private fun connection() = ConnectionInfo(1, TransportProtocol.TCP, InetAddress.getByName("10.0.0.2"), 12345, InetAddress.getByName("198.51.100.1"), 80)
    private fun source(bytes: ByteArray): HttpInput = HttpInput(sourceChunks(bytes))
    private fun sourceChunks(bytes: ByteArray): ByteSource {
        var position = 0
        return ByteSource { if (position == bytes.size) null else bytes.copyOfRange(position, minOf(bytes.size, position + CHUNK_BYTES)).also { position += it.size } }
    }
    private suspend fun body(input: HttpInput, framing: Framing): ByteArray = all(BodyReader(input, framing))
    private suspend fun all(reader: BodyReader): ByteArray = ByteArrayOutputStream().also { output -> while (true) { output.write(reader.read() ?: break) } }.toByteArray()
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private inner class FakeConnector(private val response: ByteArray = byteArrayOf()) : HttpConnector {
        val requests = mutableListOf<ByteArrayOutputStream>()
        val destinations = mutableListOf<Endpoint>()
        var closed = 0
        override fun open(endpoint: Endpoint): HttpPeer {
            destinations += endpoint
            val request = ByteArrayOutputStream().also { requests += it }
            val sent = CompletableDeferred<Unit>()
            var position = 0
            return object : HttpPeer {
                override val source = ByteSource {
                    sent.await()
                    if (position == response.size) null else response.copyOfRange(position, minOf(response.size, position + CHUNK_BYTES)).also { position += it.size }
                }
                override val sink = ByteSink {
                    request.write(it)
                    // A real HTTP server uses message framing, rather than waiting for TCP EOF.
                    val input = this@Http1ConnectionTest.source(request.toByteArray())
                    try {
                        val head = HttpFraming.request(input)!!
                        body(input, head.framing)
                        sent.complete(Unit)
                    } catch (_: java.io.EOFException) { /* More request bytes are still being sent. */ }
                }
                override fun finish() {}
                override fun close() { closed++ }
            }
        }
    }
}
