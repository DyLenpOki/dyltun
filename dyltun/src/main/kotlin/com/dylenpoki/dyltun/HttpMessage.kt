package com.dylenpoki.dyltun

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class BodyState { BUFFERED, STREAMING, OPAQUE }

class BodyLimitExceededException(message: String) : IllegalArgumentException(message)

abstract class HttpMessage internal constructor(
    headers: HttpHeaders,
    content: ByteArray?,
    bodyState: BodyState
) {
    val headers: HttpHeaders = headers.copy()
    var bodyState: BodyState = bodyState
        internal set
    private var body = content?.copyOf()
    internal var bodyLimit: BodyLimit? = null
    private var allocation: ((Long) -> Unit)? = null
    internal var revision = 0
        private set

    var content: ByteArray?
        get() = if (bodyState == BodyState.BUFFERED) body?.let { allocation?.invoke(it.size.toLong()); it.copyOf() } else null
        set(value) {
            check(bodyState == BodyState.BUFFERED) { "Body is unavailable for modification" }
            requireNotNull(value) { "Use an empty array to remove content" }
            validateContent(value.size)
            allocation?.invoke(value.size.toLong())
            body = value.copyOf()
            revision++
        }

    var text: String?
        get() {
            if (bodyState != BodyState.BUFFERED) return null
            val bytes = body ?: return null
            val charset = contentCharset() ?: return null
            // Decoder scratch plus the returned String; caller-created intermediate Strings are outside this budget.
            allocation?.invoke(bytes.size.toLong() * 6 + 64)
            return try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                null
            }
        }
        set(value) {
            requireNotNull(value) { "Use an empty string to remove content" }
            check(bodyState == BodyState.BUFFERED) { "Body is unavailable for modification" }
            val charset = requireNotNull(contentCharset()) { "Unsupported content charset" }
            val ceiling = bodyLimit?.let { if (gzip()) it.maxDecodedBytes else minOf(it.maxBufferedBytes, it.maxDecodedBytes) }
                ?: Int.MAX_VALUE.toLong()
            body = encodeText(value, charset, ceiling) { allocation?.invoke(it) }
            revision++
        }

    internal val contentSize: Int get() = body?.size ?: 0
    internal val ownedContent: ByteArray? get() = body

    internal fun bindAllocation(reserve: (Long) -> Unit) {
        if (allocation === reserve) return
        check(allocation == null) { "Message already belongs to a body account" }
        reserve(contentSize.toLong())
        allocation = reserve
    }

    /** Caller has already reserved the transferred byte array. */
    internal fun loadBody(bytes: ByteArray?, state: BodyState, limit: BodyLimit, reserve: (Long) -> Unit) {
        check(allocation == null)
        body = bytes
        bodyState = state
        bodyLimit = limit
        allocation = reserve
    }

    internal fun validateContent(size: Int = contentSize) {
        bodyLimit?.let {
            if (size.toLong() > it.maxDecodedBytes || !gzip() && size.toLong() > it.maxBufferedBytes) {
                throw BodyLimitExceededException("Decoded body exceeds the configured limit")
            }
        }
    }

    private fun gzip(): Boolean = headers["Content-Encoding"]?.trim()?.equals("gzip", true) == true

    private fun contentCharset(): Charset? {
        val type = headers["Content-Type"] ?: return Charsets.UTF_8
        val name = CHARSET.find(type)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            ?: return Charsets.UTF_8
        return try { Charset.forName(name) } catch (_: IllegalArgumentException) { null }
    }

    companion object {
        private val CHARSET = Regex("(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", RegexOption.IGNORE_CASE)
    }
}

/** Count through a fixed scratch buffer before allocating the final encoded body. */
internal fun encodeText(value: String, charset: Charset, maxBytes: Long, reserve: (Long) -> Unit = {}): ByteArray {
    val encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = java.nio.CharBuffer.wrap(value)
    val scratch = ByteBuffer.allocate(8192)
    var size = 0L
    fun count() {
        size += scratch.position()
        if (size > maxBytes || size > Int.MAX_VALUE) throw BodyLimitExceededException("Encoded text exceeds the configured limit")
        scratch.clear()
    }
    while (true) {
        val result = encoder.encode(input, scratch, true)
        if (result.isError) result.throwException()
        count()
        if (result.isUnderflow) break
    }
    while (true) {
        val result = encoder.flush(scratch)
        if (result.isError) result.throwException()
        count()
        if (result.isUnderflow) break
    }
    reserve(size)
    val output = ByteBuffer.allocate(size.toInt())
    encoder.reset()
    input.rewind()
    val encoded = encoder.encode(input, output, true)
    check(encoded.isUnderflow)
    val flushed = encoder.flush(output)
    check(flushed.isUnderflow && output.position() == size.toInt())
    return output.array()
}

internal fun validateBodySize(size: Int, limit: BodyLimit) {
    if (size.toLong() > limit.maxDecodedBytes || size.toLong() > limit.maxBufferedBytes) {
        throw BodyLimitExceededException("Body size $size exceeds the configured limit")
    }
}
