package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.internal.stream.ByteSink
import com.dylenpoki.dyltun.internal.stream.ByteSource
import com.dylenpoki.dyltun.internal.stream.StreamConnector
import com.dylenpoki.dyltun.internal.stream.StreamPeer
import java.io.IOException

/** Tags transport errors so client reads, protocol errors and Hook failures cannot become fallbacks. */
internal class HttpUpstreamException(val upstreamCause: IOException) : IOException(upstreamCause.message, upstreamCause)

internal fun guardedHttpPeer(connector: StreamConnector, endpoint: Endpoint): StreamPeer {
    val delegate = upstreamIo { connector.open(endpoint) }
    return object : StreamPeer {
        override val source = ByteSource { upstreamIo { delegate.source.read() } }
        override val sink = ByteSink { bytes -> upstreamIo { delegate.sink.write(bytes) } }
        override fun finish() { upstreamIo { delegate.finish() } }
        override fun close() { delegate.close() }
    }
}

private inline fun <T> upstreamIo(block: () -> T): T = try { block() }
catch (error: IOException) { throw HttpUpstreamException(error) }
