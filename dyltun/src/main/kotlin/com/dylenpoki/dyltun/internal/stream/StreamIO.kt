package com.dylenpoki.dyltun.internal.stream

import com.dylenpoki.dyltun.Endpoint
import java.io.Closeable

/** One reader owns each source. Chunks are at most [CHUNK_BYTES]; null denotes input EOF. */
internal fun interface ByteSource { suspend fun read(): ByteArray? }

/** One writer owns each sink. Suspends under backpressure; submitted chunks must not be mutated. */
internal fun interface ByteSink { suspend fun write(bytes: ByteArray) }

internal const val CHUNK_BYTES = 16384

internal interface StreamPeer : Closeable {
    val source: ByteSource
    val sink: ByteSink
    /** Drain queued writes then half-close upstream output; reads remain available. */
    fun finish()
}

internal fun interface StreamConnector { fun open(endpoint: Endpoint): StreamPeer }
