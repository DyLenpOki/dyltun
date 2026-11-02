package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.BodyState
import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpRequest
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.ProtocolMetadata
import com.dylenpoki.dyltun.RoutingContext
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.RoutingEvaluator
import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import java.io.EOFException
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One ordered transaction at a time. Pipelined bytes remain in bounded TCP/bridge buffers. */
internal class Http1Connection(
    private val config: DyltunConfig,
    private val connection: ConnectionInfo,
    private val metadata: List<ProtocolMetadata>,
    private val budget: MemoryBudget,
    private val executor: HookExecutor,
    private val connector: HttpConnector,
    private val clientSource: ByteSource,
    private val clientSink: ByteSink,
    private val finishOutput: () -> Unit = {},
    private val flowIds: AtomicLong = AtomicLong(),
    private val report: (Throwable) -> Unit = {}
) {
    private val hooks = checkNotNull(config.hooks)
    private val client = HttpInput(clientSource)

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val head = HttpFraming.request(client) ?: return
            val keepAlive = coroutineScope {
                BodyAccount(budget).use { account ->
                    try { transaction(head, account, this) }
                    finally { coroutineContext.cancelChildren() }
                }
            }
            if (!keepAlive) return
        }
    }

    private suspend fun transaction(head: RequestHead, account: BodyAccount, scope: CoroutineScope): Boolean {
        val requestBody = BufferedBody(BodyReader(client, head.framing), scope, account, config.bodyLimits.request)
        val expect = HttpFraming.tokens(head.headers, "Expect")
        if (expect.isNotEmpty()) {
            if (expect != setOf("100-continue")) throw HttpProtocolException("Unsupported Expect field")
            if (head.framing != Framing.Empty) clientSink.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            head.headers.remove("Expect")
        }
        try { requestBody.collect(head.headers) }
        catch (rejection: BodyRejection) { return reject(rejection, request = true) }
        val request = HttpRequest(head.method, head.url, head.headers)
        request.loadBody(requestBody.decoded, requestBody.state, config.bodyLimits.request, account.reserve)
        val flow = HttpFlow(flowIds.incrementAndGet(), connection, request, config.bodyLimits, account.reserve)
        val requestGuard = FramingGuard(request.headers)
        executor.execute { hooks.request(flow) }
        currentCoroutineContext().ensureActive()
        if (request.bodyState != BodyState.BUFFERED) {
            requestGuard.check(request.headers)
            if (request.method != head.method) throw HttpProtocolException("Streaming request method cannot change")
        }
        val requestCloses = "close" in HttpFraming.tokens(head.headers, "Connection") ||
            "close" in HttpFraming.tokens(request.headers, "Connection")
        val local = flow.response
        if (local != null) {
            val close = requestCloses || !requestBody.reader.complete
            return !respond(flow, null, null, account, close, scope, head.method)
        }
        val endpoint = destination(request, head.url)
        var canRecover = true
        try {
            guardedHttpPeer(connector, endpoint).use { peer ->
                val upgradeRequested = "upgrade" in HttpFraming.tokens(request.headers, "Connection") && request.headers["Upgrade"] != null
                val requestChunks = if (request.bodyState == BodyState.BUFFERED) {
                    val changed = request.revision != 0 || encoding(request.headers) != encoding(head.headers)
                    if (changed) HttpEncoder.changedContent(request.headers)
                    val chunks = if (!changed) requestBody.original.map { it.bytes } else encodeBody(request, config.bodyLimits.request, account)
                    requestBody.reader.trailers.entries().forEach { (name, value) -> request.headers.add(name, value) }
                    HttpEncoder.bufferedFraming(request.headers, chunks.sumOf { it.size.toLong() })
                    chunks
                } else null
                HttpEncoder.cleanHopHeaders(request.headers, upgradeRequested)
                if (!upgradeRequested) request.headers["Connection"] = "close"
                val target = if (head.target == "*" && request.path == "/") "*" else request.path
                HttpEncoder.bytes(peer.sink, HttpEncoder.headers("${request.method} $target HTTP/1.1", request.headers))
                val upstream = HttpInput(peer.source)
                return coroutineScope {
                    val upload = async(start = CoroutineStart.UNDISPATCHED) {
                        if (requestChunks != null) HttpEncoder.buffered(peer.sink, requestChunks) else HttpEncoder.stream(peer.sink, requestBody)
                        // Content-Length/chunked terminates an HTTP message. An early TCP FIN can
                        // abort processing in upstream HTTP servers; keep output open for the response.
                    }
                    var headResponse: ResponseHead
                    var interim = 0
                    while (true) {
                        headResponse = try { HttpFraming.response(upstream, request.method) }
                        catch (error: EOFException) { throw HttpUpstreamException(error) }
                        if (headResponse.status == 101 || headResponse.status >= 200) break
                        if (++interim > 16) throw HttpProtocolException("Too many interim HTTP responses")
                        if (headResponse.status != 100 || expect.isEmpty()) {
                            HttpEncoder.cleanHopHeaders(headResponse.headers)
                            canRecover = false
                            HttpEncoder.bytes(clientSink, HttpEncoder.headers("HTTP/1.1 ${headResponse.status} ${headResponse.reason}", headResponse.headers))
                            canRecover = true
                        }
                    }
                    // An early rejection must not wait forever for a client upload that the server stopped reading.
                    val early = !upload.isCompleted
                    if (early) upload.cancelAndJoin() else upload.await()
                    if (headResponse.status == 101) {
                        canRecover = false
                        if (!upgradeRequested || headResponse.headers["Upgrade"]?.equals(request.headers["Upgrade"], true) != true ||
                            "upgrade" !in HttpFraming.tokens(headResponse.headers, "Connection")) throw HttpProtocolException("Unsolicited HTTP upgrade")
                        val response = HttpResponse.fromWire(101, headResponse.headers)
                        val upgradeGuard = FramingGuard(response.headers)
                        flow.receiveUpstream(response)
                        flow.beginResponseCallback()
                        executor.execute { hooks.response(flow) }
                        if (flow.response !== response || response.statusCode != 101) {
                            respond(flow, null, null, account, true, scope, head.method, invokeHook = false)
                            return@coroutineScope false
                        }
                        if (response.contentSize != 0) throw HttpProtocolException("Upgrade response cannot contain a body")
                        upgradeGuard.check(response.headers)
                        if (response.headers["Upgrade"]?.equals(request.headers["Upgrade"], true) != true ||
                            "upgrade" !in HttpFraming.tokens(response.headers, "Connection")) throw HttpProtocolException("Hook changed the negotiated upgrade")
                        HttpEncoder.cleanHopHeaders(response.headers, upgrade = true)
                        HttpEncoder.bytes(clientSink, HttpEncoder.headers("HTTP/1.1 101 Switching Protocols", response.headers))
                        tunnel(peer, upstream)
                        return@coroutineScope false
                    }
                    val body = BufferedBody(BodyReader(upstream, headResponse.framing), scope, account, config.bodyLimits.response)
                    try { body.collect(headResponse.headers) }
                    catch (rejection: BodyRejection) { canRecover = false; return@coroutineScope reject(rejection, request = false) }
                    catch (error: EOFException) { throw HttpUpstreamException(error) }
                    val response = HttpResponse.fromWire(headResponse.status, headResponse.headers)
                    // Each transaction owns its upstream socket. Its close does not end a framed client connection.
                    HttpEncoder.cleanHopHeaders(response.headers)
                    response.loadBody(body.decoded, body.state, config.bodyLimits.response, account.reserve)
                    canRecover = false
                    flow.receiveUpstream(response)
                    val close = requestCloses || early || headResponse.framing == Framing.Close
                    val responseClose = respond(flow, headResponse, body, account, close, scope, head.method)
                    !responseClose
                }
            }
        } catch (error: HttpUpstreamException) {
            if (!canRecover) throw error.upstreamCause
            currentCoroutineContext().ensureActive()
            executor.execute { hooks.error(flow, error.upstreamCause) }
            if (flow.response == null) throw error.upstreamCause
            report(error.upstreamCause)
            respond(flow, null, null, account, true, scope, head.method)
            return false
        }
    }

    private suspend fun reject(rejection: BodyRejection, request: Boolean): Boolean {
        // Aggregation runs before final headers. A fixed empty response also works for HEAD and exhausted budgets.
        report(rejection)
        val status = rejection.status(request)
        val reason = when (status) {
            413 -> "Content Too Large"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            408 -> "Request Timeout"
            else -> "Gateway Timeout"
        }
        HttpEncoder.bytes(clientSink, HttpEncoder.headers("HTTP/1.1 $status $reason",
            HttpHeaders.of("Content-Length" to "0", "Connection" to "close")))
        return false
    }

    private fun destination(request: HttpRequest, originalUrl: String): Endpoint {
        val candidate = if (authority(request.url) != authority(originalUrl)) {
            val uri = URI(request.url)
            Endpoint(uri.host, if (uri.port == -1) 80 else uri.port)
        } else connection.originalDestination
        val context = RoutingContext(connection, metadata.firstOrNull(), candidate, metadata)
        val final = when (val decision = RoutingEvaluator.evaluate(config.routing, context)) {
            RoutingDecision.Reset -> throw HttpProtocolException("HTTP request rejected by routing policy")
            is RoutingDecision.Redirect -> decision.destination
            else -> candidate
        }
        if (RoutingEvaluator.evaluate(config.routing, context.copy(destination = final)) == RoutingDecision.Reset) {
            throw HttpProtocolException("Final HTTP endpoint rejected by routing policy")
        }
        return final
    }

    private suspend fun respond(
        flow: HttpFlow, head: ResponseHead?, body: BufferedBody?, account: BodyAccount, close: Boolean,
        scope: CoroutineScope, clientMethod: String, invokeHook: Boolean = true
    ): Boolean {
        val before = checkNotNull(flow.response)
        val guard = FramingGuard(before.headers)
        if (invokeHook) { flow.beginResponseCallback(); executor.execute { hooks.response(flow) } }
        scope.coroutineContext.ensureActive()
        val response = checkNotNull(flow.response)
        val replacement = response !== before || head == null
        val noBody = clientMethod == "HEAD" || response.statusCode == 204 || response.statusCode == 304
        val closes = close || "close" in HttpFraming.tokens(response.headers, "Connection")
        val chunks: List<ByteArray>?
        if (!replacement && response.bodyState != BodyState.BUFFERED) {
            guard.check(response.headers)
            if (response.statusCode != head!!.status) throw HttpProtocolException("Streaming response status cannot change")
            chunks = null
        } else {
            val changed = replacement || response.revision != 0 ||
                (encoding(response.headers) != encoding(head.headers) || response.statusCode != head.status)
            if (changed) HttpEncoder.changedContent(response.headers)
            chunks = if (!changed && body != null) body.original.map { it.bytes } else encodeBody(response, config.bodyLimits.response, account)
            if (noBody && response.statusCode == 204 && chunks.sumOf { it.size.toLong() } != 0L) throw HttpProtocolException("204 cannot contain a body")
            body?.takeIf { !replacement }?.reader?.trailers?.entries()?.forEach { (name, value) -> response.headers.add(name, value) }
            val bytes = chunks.sumOf { it.size.toLong() }
            HttpEncoder.bufferedFraming(response.headers, bytes, noBody, retainLength = noBody && response.statusCode != 204 && !changed)
            if (noBody && clientMethod == "HEAD" && (replacement || response.revision != 0)) response.headers["Content-Length"] = bytes.toString()
        }
        HttpEncoder.cleanHopHeaders(response.headers)
        if (closes) response.headers["Connection"] = "close"
        HttpEncoder.bytes(clientSink, HttpEncoder.headers("HTTP/1.1 ${response.statusCode} ${reason(response.statusCode)}", response.headers))
        if (!noBody) {
            if (chunks != null) HttpEncoder.buffered(clientSink, chunks) else HttpEncoder.stream(clientSink, checkNotNull(body))
        }
        return closes
    }

    private suspend fun tunnel(peer: HttpPeer, upstream: HttpInput) = coroutineScope {
        val upload = launch {
            while (true) { peer.sink.write(client.read() ?: break) }
            peer.finish()
        }
        while (true) { clientSink.write(upstream.read() ?: break) }
        finishOutput()
        upload.join()
    }

    private fun authority(url: String): Pair<String, Int> = URI(url).let { it.host.lowercase() to if (it.port == -1) 80 else it.port }
    private fun reason(code: Int): String = when (code) { 200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 304 -> "Not Modified"; 400 -> "Bad Request"; 404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "Response" }
}
