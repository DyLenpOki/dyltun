package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.internal.stream.CHUNK_BYTES
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.isHttpToken
import java.net.URI

internal sealed interface Framing {
    data object Empty : Framing
    data class Fixed(val bytes: Long) : Framing
    data object Chunked : Framing
    data object Close : Framing
}

internal data class RequestHead(val method: String, val target: String, val url: String, val headers: HttpHeaders, val framing: Framing)
internal data class ResponseHead(val status: Int, val reason: String, val headers: HttpHeaders, val framing: Framing)

internal object HttpFraming {
    const val MAX_HEADERS = 32768
    const val MAX_FIELDS = 128
    private val forbiddenTrailers = setOf("content-length", "transfer-encoding", "host", "connection", "trailer",
        "content-encoding", "content-type", "authorization", "proxy-authorization", "expect", "upgrade")

    suspend fun headers(input: HttpInput, trailer: Boolean = false): HttpHeaders {
        val headers = HttpHeaders()
        var bytes = 0
        var fields = 0
        while (true) {
            val line = input.line(minOf(8192, MAX_HEADERS - bytes))!!
            bytes += line.length + 2
            if (bytes > MAX_HEADERS) throw HttpProtocolException("HTTP header limit exceeded")
            if (line.isEmpty()) return headers
            if (++fields > MAX_FIELDS) throw HttpProtocolException("Too many HTTP fields")
            val colon = line.indexOf(':')
            if (colon <= 0 || !isHttpToken(line.substring(0, colon))) throw HttpProtocolException("Invalid HTTP field")
            val name = line.substring(0, colon)
            if (trailer && name.lowercase() in forbiddenTrailers) throw HttpProtocolException("Forbidden HTTP trailer")
            try { headers.add(name, line.substring(colon + 1).trim(' ', '\t')) }
            catch (error: IllegalArgumentException) { throw HttpProtocolException(error.message ?: "Invalid HTTP field") }
        }
    }

    suspend fun request(input: HttpInput): RequestHead? {
        val line = input.line(allowEof = true) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3 || !isHttpToken(parts[0]) || parts[2] != "HTTP/1.1") throw HttpProtocolException("Invalid HTTP/1.1 request line")
        val headers = headers(input)
        val hosts = headers.getAll("Host")
        if (hosts.size != 1) throw HttpProtocolException("Exactly one Host field is required")
        val host = hosts.single()
        val authority = try { URI("http://$host/") } catch (_: Exception) { throw HttpProtocolException("Invalid Host") }
        if (authority.host.isNullOrEmpty() || authority.rawUserInfo != null || authority.rawAuthority != host ||
            authority.rawPath != "/" || authority.rawQuery != null || authority.rawFragment != null ||
            authority.port != -1 && authority.port !in 1..65535) throw HttpProtocolException("Invalid Host")
        val target = parts[1]
        if (target.any { it.code <= 32 || it.code >= 127 } || '#' in target) throw HttpProtocolException("Invalid request target")
        val url = when {
            target == "*" && parts[0] == "OPTIONS" -> "http://$host/"
            target.startsWith('/') && !target.startsWith("//") -> "http://$host$target"
            target.startsWith("http://", true) -> {
                val absolute = try { URI(target) } catch (_: Exception) { throw HttpProtocolException("Invalid absolute target") }
                if (absolute.host.isNullOrEmpty() || absolute.rawUserInfo != null || absolute.rawFragment != null ||
                    !absolute.host.equals(authority.host, true) || port(absolute) != port(authority)) throw HttpProtocolException("Target and Host disagree")
                target
            }
            else -> throw HttpProtocolException("Unsupported request target")
        }
        return RequestHead(parts[0], target, url, headers, framing(headers, request = true))
    }

    suspend fun response(input: HttpInput, requestMethod: String): ResponseHead {
        val line = input.line()!!
        if (!line.startsWith("HTTP/1.1 ") || line.length < 12 || line.length > 12 && line[12] != ' ') throw HttpProtocolException("Invalid HTTP/1.1 response line")
        val status = line.substring(9, 12).takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            ?.takeIf { it in 100..599 } ?: throw HttpProtocolException("Invalid response status")
        val reason = line.drop(13)
        if (reason.any { it != '\t' && it.code !in 32..255 }) throw HttpProtocolException("Invalid reason phrase")
        val headers = headers(input)
        val framing = framing(headers, request = false)
        if ((status in 100..199 || status == 204) && (headers["Content-Length"] != null || headers["Transfer-Encoding"] != null)) {
            throw HttpProtocolException("Framing is forbidden on this status")
        }
        return ResponseHead(status, reason, headers, if (requestMethod == "HEAD" || status in 100..199 || status == 204 || status == 304) Framing.Empty else framing)
    }

    fun framing(headers: HttpHeaders, request: Boolean): Framing {
        val transfer = headers.getAll("Transfer-Encoding")
        val lengths = headers.getAll("Content-Length").flatMap { it.split(',') }.map { it.trim(' ', '\t') }
        if (transfer.isNotEmpty() && lengths.isNotEmpty()) throw HttpProtocolException("Conflicting HTTP framing")
        if (transfer.isNotEmpty()) {
            if (transfer.flatMap { it.split(',') }.map { it.trim().lowercase() } != listOf("chunked")) throw HttpProtocolException("Unsupported transfer coding")
            return Framing.Chunked
        }
        if (lengths.isNotEmpty()) {
            if (lengths.any { it.isEmpty() || it.any { char -> char !in '0'..'9' } } || lengths.distinct().size != 1) throw HttpProtocolException("Conflicting Content-Length")
            val count = lengths.singleOrNull()?.toLongOrNull() ?: lengths[0].toLongOrNull()
                ?: throw HttpProtocolException("Content-Length overflow")
            return if (count == 0L) Framing.Empty else Framing.Fixed(count)
        }
        return if (request) Framing.Empty else Framing.Close
    }

    fun tokens(headers: HttpHeaders, name: String): Set<String> = headers.getAll(name).flatMap { it.split(',') }.map { it.trim().lowercase() }.toSet()
    private fun port(uri: URI): Int = if (uri.port == -1) 80 else uri.port
}

/** Dechunks without aggregating; the cursor retains bytes of the next pipelined message. */
internal class BodyReader(private val input: HttpInput, val framing: Framing) {
    private var remaining = (framing as? Framing.Fixed)?.bytes ?: 0
    private var chunkRemaining = 0L
    private var needChunkEnding = false
    var complete = framing == Framing.Empty
        private set
    val remainingBytes: Long? get() = if (framing is Framing.Fixed) remaining else null
    var trailers = HttpHeaders()
        private set

    suspend fun read(maximum: Int = CHUNK_BYTES): ByteArray? {
        if (complete) return null
        when (framing) {
            Framing.Empty -> return null
            is Framing.Fixed -> {
                val bytes = input.required(minOf(maximum.toLong(), remaining).toInt())
                remaining -= bytes.size
                if (remaining == 0L) complete = true
                return bytes
            }
            Framing.Close -> return input.read(maximum).also { if (it == null) complete = true }
            Framing.Chunked -> {
                if (chunkRemaining == 0L) {
                    if (needChunkEnding && input.line(0) != "") throw HttpProtocolException("Invalid chunk ending")
                    val line = input.line()!!
                    val hex = line.substringBefore(';')
                    if (hex.isEmpty() || hex.length > 16 || hex.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' } ||
                        line.any { it.code !in 32..126 && it != '\t' }) throw HttpProtocolException("Invalid chunk size")
                    chunkRemaining = hex.toLongOrNull(16) ?: throw HttpProtocolException("Chunk size overflow")
                    if (chunkRemaining == 0L) {
                        trailers = HttpFraming.headers(input, trailer = true)
                        complete = true
                        return null
                    }
                    needChunkEnding = true
                }
                val bytes = input.required(minOf(maximum.toLong(), chunkRemaining).toInt())
                chunkRemaining -= bytes.size
                return bytes
            }
        }
    }
}
