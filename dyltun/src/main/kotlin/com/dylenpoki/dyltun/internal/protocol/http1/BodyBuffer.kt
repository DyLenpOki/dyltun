package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimitExceededException
import com.dylenpoki.dyltun.BodyOverflow
import com.dylenpoki.dyltun.BodyState
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpMessage
import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** Conservative transaction accounting: retained Hook copies are charged until the transaction ends. */
internal class BodyAccount(private val budget: MemoryBudget) : Closeable {
    private val leases = mutableListOf<MemoryBudget.Lease>()
    private var closed = false
    val reserve: (Long) -> Unit = { bytes -> requireLease(bytes) }
    @Synchronized fun tryLease(bytes: Long): MemoryBudget.Lease? {
        check(!closed) { "HTTP transaction is closed" }
        if (leases.size >= 16384) return null
        return budget.acquire(bytes)?.also { leases += it }
    }
    fun requireLease(bytes: Long): MemoryBudget.Lease = tryLease(bytes)
        ?: throw BodyLimitExceededException("Session HTTP body budget exhausted")
    @Synchronized override fun close() { if (!closed) { closed = true; leases.forEach { it.close() }; leases.clear() } }
}

internal data class BodyChunk(val bytes: ByteArray, val lease: MemoryBudget.Lease)

internal class BufferedBody(
    val reader: BodyReader,
    private val scope: CoroutineScope,
    private val account: BodyAccount,
    private val limit: BodyLimit,
    private val clock: MonotonicClock = MonotonicClock.SYSTEM
) {
    val original = mutableListOf<BodyChunk>()
    var state = BodyState.BUFFERED
        private set
    var decoded: ByteArray? = null
        private set
    private var pending: Deferred<ByteArray?>? = null
    private var encodedSize = 0L
    private val started = clock.now()

    /** Keeping the in-flight read alive preserves partial chunk-size/trailer parsing across a timeout. */
    suspend fun next(maximum: Int = CHUNK_BYTES): ByteArray? {
        val task = pending ?: scope.async { reader.read(maximum) }.also { pending = it }
        return task.await().also { pending = null }
    }

    suspend fun collect(headers: HttpHeaders) {
        val encoding = encoding(headers)
        if (encoding !in setOf("identity", "gzip")) { state = BodyState.OPAQUE; return }
        val ceiling = if (encoding == "identity") minOf(limit.maxBufferedBytes, limit.maxDecodedBytes) else limit.maxBufferedBytes
        if ((reader.framing as? Framing.Fixed)?.bytes?.let { it > ceiling } == true) {
            overflow(if (ceiling < limit.maxBufferedBytes) BodyRejectionReason.DECODED_SIZE else BodyRejectionReason.ENCODED_SIZE, "Declared HTTP body exceeds limit")
            return
        }
        while (!reader.complete) {
            val remainingMillis = remainingMillis()
            if (remainingMillis <= 0) { overflow(BodyRejectionReason.TIMEOUT, "HTTP body buffering timed out"); return }
            // One extra byte distinguishes an exact boundary from an unknown-length overflow.
            val maximum = minOf(CHUNK_BYTES.toLong(), ceiling - encodedSize + 1, reader.remainingBytes ?: Long.MAX_VALUE).toInt()
            val lease = account.tryLease(maximum.toLong())
            if (lease == null) { overflow(BodyRejectionReason.BUDGET, "Session HTTP body budget exhausted"); return }
            val result = withTimeoutOrNull(remainingMillis) { ReadResult(next(maximum)) }
            if (result == null) { lease.close(); overflow(BodyRejectionReason.TIMEOUT, "HTTP body buffering timed out"); return }
            val bytes = result.bytes
            if (bytes == null) { lease.close(); break }
            lease.shrinkTo(bytes.size.toLong())
            original += BodyChunk(bytes, lease)
            encodedSize += bytes.size
            if (encodedSize > ceiling) {
                overflow(if (ceiling < limit.maxBufferedBytes) BodyRejectionReason.DECODED_SIZE else BodyRejectionReason.ENCODED_SIZE, "HTTP body exceeds limit")
                return
            }
        }
        if (encoding == "gzip" && encodedSize > 0) decodeGzip() else {
            decoded = flatten(original, encodedSize, account) ?: run { overflow(BodyRejectionReason.BUDGET, "Session HTTP body budget exhausted"); return }
        }
    }

    private fun decodeGzip() {
        val parts = mutableListOf<BodyChunk>()
        var size = 0L
        try {
            GZIPInputStream(ChunkInput(original.map { it.bytes })).use { gzip ->
                while (true) {
                    if (remainingMillis() <= 0) { overflow(BodyRejectionReason.TIMEOUT, "HTTP gzip decoding timed out"); return }
                    val count = minOf(CHUNK_BYTES.toLong(), limit.maxDecodedBytes - size + 1).toInt()
                    val lease = account.tryLease(count.toLong())
                    if (lease == null) { overflow(BodyRejectionReason.BUDGET, "Session HTTP decode budget exhausted"); return }
                    val bytes = ByteArray(count)
                    val read = try { gzip.read(bytes) } catch (error: Throwable) { lease.close(); throw error }
                    if (read < 0) { lease.close(); break }
                    // Charge capacity, including the unused tail of the final decode chunk.
                    parts += BodyChunk(bytes, lease)
                    size += read
                    if (size > limit.maxDecodedBytes) { overflow(BodyRejectionReason.DECODED_SIZE, "Decoded HTTP body exceeds limit"); return }
                    if (read < bytes.size) {
                        // GZIPInputStream may produce short reads before EOF; retain only live bytes.
                        val copyLease = account.tryLease(read.toLong())
                        if (copyLease == null) { overflow(BodyRejectionReason.BUDGET, "Session HTTP decode budget exhausted"); return }
                        parts[parts.lastIndex] = BodyChunk(bytes.copyOf(read), copyLease)
                        lease.close()
                    }
                }
            }
            decoded = flatten(parts, size, account) ?: run { overflow(BodyRejectionReason.BUDGET, "Session HTTP decode budget exhausted"); return }
        } finally { parts.forEach { it.lease.close() } }
    }

    private fun remainingMillis(): Long = limit.maxBufferingMillis - (clock.now() - started) / 1_000_000
    private fun overflow(reason: BodyRejectionReason, message: String) {
        if (limit.overflow == BodyOverflow.REJECT) throw BodyRejection(reason, message)
        state = BodyState.STREAMING
    }
    fun releaseOriginal() { original.forEach { it.lease.close() }; original.clear() }
    private data class ReadResult(val bytes: ByteArray?)
}

internal fun encoding(headers: HttpHeaders): String = headers.getAll("Content-Encoding").let {
    if (it.isEmpty()) "identity" else it.joinToString(",").trim().lowercase()
}

private fun flatten(chunks: List<BodyChunk>, size: Long, account: BodyAccount): ByteArray? {
    if (size == 0L) return byteArrayOf()
    if (account.tryLease(size) == null) return null
    val bytes = ByteArray(size.toInt())
    var offset = 0
    for (chunk in chunks) { chunk.bytes.copyInto(bytes, offset); offset += chunk.bytes.size }
    check(offset == bytes.size)
    return bytes
}

private class ChunkInput(private val chunks: List<ByteArray>) : InputStream() {
    private var index = 0
    private var offset = 0
    override fun read(): Int {
        while (index < chunks.size && offset == chunks[index].size) { index++; offset = 0 }
        return if (index == chunks.size) -1 else chunks[index][offset++].toInt() and 255
    }
    override fun read(buffer: ByteArray, start: Int, length: Int): Int {
        if (length == 0) return 0
        while (index < chunks.size && offset == chunks[index].size) { index++; offset = 0 }
        if (index == chunks.size) return -1
        val count = minOf(length, chunks[index].size - offset)
        chunks[index].copyInto(buffer, start, offset, offset + count)
        offset += count
        return count
    }
}

/** Encodes completely and validates before any final headers are sent. */
internal fun encodeBody(message: HttpMessage, limit: BodyLimit, account: BodyAccount): List<ByteArray> {
    message.validateContent()
    val bytes = message.ownedContent ?: throw HttpProtocolException("Buffered message has no body")
    return when (encoding(message.headers)) {
        "identity" -> {
            if (bytes.size.toLong() > limit.maxBufferedBytes) throw BodyLimitExceededException("Encoded body exceeds limit")
            bytes.asListOfChunks()
        }
        "gzip" -> {
            val output = SegmentOutput(limit.maxBufferedBytes, account)
            GZIPOutputStream(output).use { it.write(bytes) }
            output.chunks
        }
        else -> throw HttpProtocolException("A buffered Hook body requires identity or gzip Content-Encoding")
    }
}

internal fun ByteArray.asListOfChunks(): List<ByteArray> = if (isEmpty()) emptyList() else listOf(this)

private class SegmentOutput(private val maximum: Long, private val account: BodyAccount) : OutputStream() {
    val chunks = mutableListOf<ByteArray>()
    private var size = 0L
    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        if (length > maximum - size) throw BodyLimitExceededException("Re-encoded HTTP body exceeds limit")
        account.reserve(length.toLong())
        chunks += bytes.copyOfRange(offset, offset + length)
        size += length
    }
}
