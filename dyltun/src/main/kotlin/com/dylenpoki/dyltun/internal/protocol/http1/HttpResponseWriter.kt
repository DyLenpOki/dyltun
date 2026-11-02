package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyState
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.HttpResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface ResponseInput {
    data object Local : ResponseInput
    data class Upstream(val head: ResponseHead, val body: BufferedBody) : ResponseInput
}

/** Shared response path for upstream, local and recovered exchanges. */
internal class HttpResponseWriter(
    private val limit: BodyLimit,
    private val hooks: HttpHooks,
    private val executor: HookExecutor,
    private val sink: ByteSink
) {
    private sealed interface Content {
        data class Buffered(val chunks: List<ByteArray>) : Content
        data class Streaming(val body: BufferedBody) : Content
    }

    suspend fun invokeHook(flow: HttpFlow) {
        flow.beginResponseCallback()
        executor.execute { hooks.response(flow) }
    }

    suspend fun write(
        flow: HttpFlow,
        input: ResponseInput,
        account: BodyAccount,
        close: Boolean,
        clientMethod: String,
        invokeHook: Boolean = true
    ): ExchangeResult {
        val before = checkNotNull(flow.response)
        val guard = FramingGuard(before.headers)
        if (invokeHook) this.invokeHook(flow)
        currentCoroutineContext().ensureActive()
        val response = checkNotNull(flow.response)
        val replacement = response !== before || input is ResponseInput.Local
        val noBody = clientMethod == "HEAD" || response.statusCode == 204 || response.statusCode == 304
        val closes = close || "close" in HttpFraming.tokens(response.headers, "Connection")
        val content = prepareContent(response, input, account, guard, replacement, noBody, clientMethod)

        HttpEncoder.cleanHopHeaders(response.headers)
        if (closes) response.headers["Connection"] = "close"
        writeHead(response.statusCode, reason(response.statusCode), response.headers)
        if (!noBody) {
            when (content) {
                is Content.Buffered -> HttpEncoder.buffered(sink, content.chunks)
                is Content.Streaming -> HttpEncoder.stream(sink, content.body)
            }
        }
        return if (closes) ExchangeResult.CLOSE else ExchangeResult.KEEP_ALIVE
    }

    private fun prepareContent(
        response: HttpResponse,
        input: ResponseInput,
        account: BodyAccount,
        guard: FramingGuard,
        replacement: Boolean,
        noBody: Boolean,
        clientMethod: String
    ): Content {
        if (!replacement && response.bodyState != BodyState.BUFFERED) {
            val upstream = input as ResponseInput.Upstream
            guard.check(response.headers)
            if (response.statusCode != upstream.head.status) {
                throw HttpProtocolException("Streaming response status cannot change")
            }
            return Content.Streaming(upstream.body)
        }

        val changed = replacement || response.revision != 0 || input is ResponseInput.Upstream &&
            (encoding(response.headers) != encoding(input.head.headers) || response.statusCode != input.head.status)
        if (changed) HttpEncoder.changedContent(response.headers)
        val chunks = if (!changed && input is ResponseInput.Upstream) {
            input.body.original.map { it.bytes }
        } else encodeBody(response, limit, account)
        val bytes = chunks.sumOf { it.size.toLong() }
        if (noBody && response.statusCode == 204 && bytes != 0L) {
            throw HttpProtocolException("204 cannot contain a body")
        }
        if (!replacement && input is ResponseInput.Upstream) {
            input.body.reader.trailers.entries().forEach { (name, value) -> response.headers.add(name, value) }
        }
        HttpEncoder.bufferedFraming(response.headers, bytes, noBody, retainLength = noBody && response.statusCode != 204 && !changed)
        if (noBody && clientMethod == "HEAD" && (replacement || response.revision != 0)) {
            response.headers["Content-Length"] = bytes.toString()
        }
        return Content.Buffered(chunks)
    }

    suspend fun writeContinue() {
        sink.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
    }

    suspend fun writeInterim(head: ResponseHead) {
        HttpEncoder.cleanHopHeaders(head.headers)
        writeHead(head.status, head.reason, head.headers)
    }

    suspend fun writeUpgrade(headers: HttpHeaders) {
        HttpEncoder.cleanHopHeaders(headers, upgrade = true)
        writeHead(101, "Switching Protocols", headers)
    }

    suspend fun writeRejection(rejection: BodyRejection, request: Boolean): ExchangeResult {
        val status = rejection.status(request)
        val reason = when (status) {
            413 -> "Content Too Large"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            408 -> "Request Timeout"
            else -> "Gateway Timeout"
        }
        writeHead(status, reason, HttpHeaders.of("Content-Length" to "0", "Connection" to "close"))
        return ExchangeResult.CLOSE
    }

    private suspend fun writeHead(status: Int, reason: String, headers: HttpHeaders) {
        HttpEncoder.bytes(sink, HttpEncoder.headers("HTTP/1.1 $status $reason", headers))
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        304 -> "Not Modified"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Response"
    }
}
