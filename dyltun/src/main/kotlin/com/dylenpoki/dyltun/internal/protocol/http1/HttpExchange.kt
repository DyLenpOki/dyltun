package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.internal.stream.StreamConnector
import com.dylenpoki.dyltun.internal.stream.StreamPeer
import com.dylenpoki.dyltun.BodyState
import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.ConnectionStage
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpRequest
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.ProtocolMetadata
import com.dylenpoki.dyltun.RoutingContext
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.RoutingEvaluator
import com.dylenpoki.dyltun.internal.diagnostics.ConnectionTrace
import java.io.EOFException
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal enum class ExchangeResult { KEEP_ALIVE, CLOSE, UPGRADED }

/** One transaction. Only upstream I/O while AWAITING_UPSTREAM is eligible for error-hook recovery. */
internal class HttpExchange(
    private val config: DyltunConfig,
    private val connection: ConnectionInfo,
    private val metadata: List<ProtocolMetadata>,
    private val executor: HookExecutor,
    private val connector: StreamConnector,
    private val client: HttpClient,
    private val responses: HttpResponseWriter,
    private val flowIds: AtomicLong,
    private val report: (Throwable) -> Unit,
    private val trace: ConnectionTrace? = null
) {
    private enum class ResponsePhase { AWAITING_UPSTREAM, WRITING_INTERIM, HANDLING_FINAL }

    private data class PreparedRequest(
        val head: RequestHead,
        val body: BufferedBody,
        val flow: HttpFlow,
        val closes: Boolean,
        val handledContinue: Boolean,
        val upgradeRequested: Boolean
    )

    private val hooks = checkNotNull(config.hooks)
    private var phase = ResponsePhase.AWAITING_UPSTREAM

    suspend fun run(head: RequestHead, account: BodyAccount, transactionScope: CoroutineScope): ExchangeResult {
        val body = BufferedBody(BodyReader(client.input, head.framing), transactionScope, account, config.bodyLimits.request)
        val handledContinue = handleExpectation(head)
        try {
            body.collect(head.headers)
        } catch (rejection: BodyRejection) {
            return reject(rejection, request = true)
        }
        val flow = prepareRequest(head, body, account)
        val closes = "close" in HttpFraming.tokens(head.headers, "Connection") ||
            "close" in HttpFraming.tokens(flow.request.headers, "Connection")
        if (flow.response != null) {
            return responses.write(flow, ResponseInput.Local, account, closes || !body.reader.complete, head.method)
        }
        return forward(PreparedRequest(head, body, flow, closes, handledContinue, upgradeRequested(flow.request)), account, transactionScope)
    }

    private suspend fun handleExpectation(head: RequestHead): Boolean {
        val expect = HttpFraming.tokens(head.headers, "Expect")
        if (expect.isEmpty()) return false
        if (expect != setOf("100-continue")) throw HttpProtocolException("Unsupported Expect field")
        if (head.framing != Framing.Empty) responses.writeContinue()
        head.headers.remove("Expect")
        return true
    }

    private suspend fun prepareRequest(head: RequestHead, body: BufferedBody, account: BodyAccount): HttpFlow {
        val request = HttpRequest(head.method, head.url, head.headers)
        request.loadBody(body.decoded, body.state, config.bodyLimits.request, account.reserve)
        val flow = HttpFlow(flowIds.incrementAndGet(), connection, request, config.bodyLimits, account.reserve)
        trace?.httpFlow(flow.id)
        val guard = FramingGuard(request.headers)
        executor.execute { hooks.request(flow) }
        currentCoroutineContext().ensureActive()
        if (request.bodyState != BodyState.BUFFERED) {
            guard.check(request.headers)
            if (request.method != head.method) throw HttpProtocolException("Streaming request method cannot change")
        }
        return flow
    }

    private suspend fun forward(
        request: PreparedRequest, account: BodyAccount, transactionScope: CoroutineScope
    ): ExchangeResult {
        trace?.stage(ConnectionStage.ROUTING)
        val endpoint = destination(request.flow.request, request.head.url)
        try {
            guardedHttpPeer(connector, endpoint).use { peer ->
                val chunks = writeRequestHead(request, peer, account)
                val upstream = HttpInput(peer.source)
                return coroutineScope {
                    val upload = async(start = CoroutineStart.UNDISPATCHED) {
                        if (chunks != null) HttpEncoder.buffered(peer.sink, chunks) else HttpEncoder.stream(peer.sink, request.body)
                        // HTTP framing ends an ordinary request. Keep the TCP write side open for the response.
                    }
                    val head = receiveFinalHead(upstream, request)
                    val early = !upload.isCompleted
                    if (early) upload.cancelAndJoin() else upload.await()
                    if (head.status == 101) {
                        phase = ResponsePhase.HANDLING_FINAL
                        return@coroutineScope upgrade(request, head, peer, upstream, account)
                    }
                    receiveResponse(request, head, upstream, account, transactionScope, early)
                }
            }
        } catch (error: HttpUpstreamException) {
            if (phase != ResponsePhase.AWAITING_UPSTREAM) throw error.upstreamCause
            currentCoroutineContext().ensureActive()
            executor.execute { hooks.error(request.flow, error.upstreamCause) }
            if (request.flow.response == null) throw error.upstreamCause
            report(error.upstreamCause)
            return responses.write(request.flow, ResponseInput.Local, account, true, request.head.method)
        }
    }

    private suspend fun writeRequestHead(
        prepared: PreparedRequest, peer: StreamPeer, account: BodyAccount
    ): List<ByteArray>? {
        val request = prepared.flow.request
        val chunks = if (request.bodyState == BodyState.BUFFERED) {
            val changed = request.revision != 0 || encoding(request.headers) != encoding(prepared.head.headers)
            if (changed) HttpEncoder.changedContent(request.headers)
            val bytes = if (!changed) prepared.body.original.map { it.bytes } else encodeBody(request, config.bodyLimits.request, account)
            prepared.body.reader.trailers.entries().forEach { (name, value) -> request.headers.add(name, value) }
            HttpEncoder.bufferedFraming(request.headers, bytes.sumOf { it.size.toLong() })
            bytes
        } else null
        val upgrade = prepared.upgradeRequested
        HttpEncoder.cleanHopHeaders(request.headers, upgrade)
        if (!upgrade) request.headers["Connection"] = "close"
        val target = if (prepared.head.target == "*" && request.path == "/") "*" else request.path
        HttpEncoder.bytes(peer.sink, HttpEncoder.headers("${request.method} $target HTTP/1.1", request.headers))
        return chunks
    }

    private suspend fun receiveFinalHead(upstream: HttpInput, request: PreparedRequest): ResponseHead {
        var interim = 0
        while (true) {
            val head = try {
                HttpFraming.response(upstream, request.flow.request.method)
            } catch (error: EOFException) {
                throw HttpUpstreamException(error)
            }
            if (head.status == 101 || head.status >= 200) return head
            if (++interim > 16) throw HttpProtocolException("Too many interim HTTP responses")
            if (head.status != 100 || !request.handledContinue) {
                phase = ResponsePhase.WRITING_INTERIM
                responses.writeInterim(head)
                phase = ResponsePhase.AWAITING_UPSTREAM
            }
        }
    }

    private suspend fun receiveResponse(
        request: PreparedRequest, head: ResponseHead, upstream: HttpInput,
        account: BodyAccount, transactionScope: CoroutineScope, early: Boolean
    ): ExchangeResult {
        // Pending reads belong to the transaction. The connection loop cancels them even when
        // aggregation times out; the upload/response scope must not wait for them on return.
        val body = BufferedBody(BodyReader(upstream, head.framing), transactionScope, account, config.bodyLimits.response)
        try {
            body.collect(head.headers)
        } catch (rejection: BodyRejection) {
            phase = ResponsePhase.HANDLING_FINAL
            return reject(rejection, request = false)
        } catch (error: EOFException) {
            throw HttpUpstreamException(error)
        }
        val response = HttpResponse.fromWire(head.status, head.headers)
        HttpEncoder.cleanHopHeaders(response.headers)
        response.loadBody(body.decoded, body.state, config.bodyLimits.response, account.reserve)
        phase = ResponsePhase.HANDLING_FINAL
        request.flow.receiveUpstream(response)
        val closes = request.closes || early || head.framing == Framing.Close
        return responses.write(request.flow, ResponseInput.Upstream(head, body), account, closes, request.head.method)
    }

    private suspend fun upgrade(
        request: PreparedRequest, head: ResponseHead, peer: StreamPeer, upstream: HttpInput, account: BodyAccount
    ): ExchangeResult {
        val requestedHeaders = request.flow.request.headers
        val protocol = requestedHeaders["Upgrade"]
        if (!request.upgradeRequested || head.headers["Upgrade"]?.equals(protocol, true) != true ||
            "upgrade" !in HttpFraming.tokens(head.headers, "Connection")) {
            throw HttpProtocolException("Unsolicited HTTP upgrade")
        }
        val response = HttpResponse.fromWire(101, head.headers)
        val guard = FramingGuard(response.headers)
        request.flow.receiveUpstream(response)
        responses.invokeHook(request.flow)
        if (request.flow.response !== response || response.statusCode != 101) {
            return responses.write(request.flow, ResponseInput.Local, account, true, request.head.method, invokeHook = false)
        }
        if (response.contentSize != 0) throw HttpProtocolException("Upgrade response cannot contain a body")
        guard.check(response.headers)
        if (response.headers["Upgrade"]?.equals(requestedHeaders["Upgrade"], true) != true ||
            "upgrade" !in HttpFraming.tokens(response.headers, "Connection")) {
            throw HttpProtocolException("Hook changed the negotiated upgrade")
        }
        responses.writeUpgrade(response.headers)
        tunnel(peer, upstream)
        return ExchangeResult.UPGRADED
    }

    private suspend fun tunnel(peer: StreamPeer, upstream: HttpInput) = coroutineScope {
        val upload = launch {
            while (true) peer.sink.write(client.input.read() ?: break)
            peer.finish()
        }
        while (true) client.sink.write(upstream.read() ?: break)
        client.finishOutput()
        upload.join()
    }

    private suspend fun reject(rejection: BodyRejection, request: Boolean): ExchangeResult {
        report(rejection)
        return responses.writeRejection(rejection, request)
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

    private fun upgradeRequested(request: HttpRequest): Boolean =
        "upgrade" in HttpFraming.tokens(request.headers, "Connection") && request.headers["Upgrade"] != null

    private fun authority(url: String): Pair<String, Int> =
        URI(url).let { it.host.lowercase() to if (it.port == -1) 80 else it.port }
}
