package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.internal.transport.TcpForwarder
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface HttpPeer : Closeable {
    val source: ByteSource
    val sink: ByteSink
    fun finish()
}

internal fun interface HttpConnector { fun open(endpoint: Endpoint): HttpPeer }

/** Tags transport errors so client reads, protocol errors and Hook failures cannot become fallbacks. */
internal class HttpUpstreamException(val upstreamCause: IOException) : IOException(upstreamCause.message, upstreamCause)

internal fun guardedHttpPeer(connector: HttpConnector, endpoint: Endpoint): HttpPeer {
    val delegate = upstreamIo { connector.open(endpoint) }
    return object : HttpPeer {
        override val source = ByteSource { upstreamIo { delegate.source.read() } }
        override val sink = ByteSink { bytes -> upstreamIo { delegate.sink.write(bytes) } }
        override fun finish() { upstreamIo { delegate.finish() } }
        override fun close() { delegate.close() }
    }
}

private inline fun <T> upstreamIo(block: () -> T): T = try { block() }
catch (error: IOException) { throw HttpUpstreamException(error) }

/** HTTP consumers release upstream credit on consumption; rewritten client output has its own bounded queue. */
internal class ForwarderHttpConnector(private val forwarder: TcpForwarder) : HttpConnector {
    private val ids = AtomicLong(0)
    override fun open(endpoint: Endpoint): HttpPeer = Peer(forwarder.open(ids.decrementAndGet(), endpoint))

    private class Peer(private val handle: TcpForwarder.Handle) : HttpPeer {
        override val source = ByteSource {
            var result: ByteArray? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                handle.failure.get()?.let { throw it }
                result = handle.poll()
                if (result != null) { handle.acknowledge(result.size); break }
                if (handle.drained()) break
                if (handle.closing.get()) throw IOException("HTTP upstream closed")
                handle.changed.receive()
            }
            result
        }
        override val sink = ByteSink { bytes ->
            var offset = 0
            while (offset < bytes.size) {
                handle.takeWritten() // HTTP has separate input accounting; do not accumulate an unobserved write counter.
                val part = if (offset == 0 && bytes.size <= CHUNK_BYTES) bytes else bytes.copyOfRange(offset, minOf(bytes.size, offset + CHUNK_BYTES))
                while (!handle.send(part)) {
                    currentCoroutineContext().ensureActive()
                    handle.failure.get()?.let { throw it }
                    if (handle.closing.get() || handle.ending.get()) throw IOException("HTTP upstream output closed")
                    handle.changed.receive()
                    handle.takeWritten()
                }
                offset += part.size
            }
        }
        override fun finish() { handle.finish() }
        override fun close() { handle.close() }
    }
}
