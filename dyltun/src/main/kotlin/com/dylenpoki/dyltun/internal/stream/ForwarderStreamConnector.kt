package com.dylenpoki.dyltun.internal.stream

import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.internal.diagnostics.ConnectionTrace
import com.dylenpoki.dyltun.internal.transport.TcpForwarder
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Processed streams release upstream credit on consumption; output has its own bounded queue. */
internal class ForwarderStreamConnector(
    private val forwarder: TcpForwarder,
    private val ids: AtomicLong,
    private val trace: ConnectionTrace
) : StreamConnector {

    override fun open(endpoint: Endpoint): StreamPeer = Peer(forwarder.open(ids.decrementAndGet(), endpoint, trace))

    private class Peer(private val handle: TcpForwarder.Handle) : StreamPeer {
        override val source = ByteSource {
            var result: ByteArray?
            while (true) {
                currentCoroutineContext().ensureActive()
                handle.failure.get()?.let { throw it }
                result = handle.poll()
                if (result != null) { handle.acknowledge(result.size); break }
                if (handle.drained()) break
                if (handle.closing.get()) throw IOException("Upstream stream closed")
                handle.changed.receive()
            }
            result
        }

        override val sink = ByteSink { bytes ->
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                handle.takeWritten()
                val part = if (offset == 0 && bytes.size <= CHUNK_BYTES) bytes else
                    bytes.copyOfRange(offset, minOf(bytes.size, offset + CHUNK_BYTES))
                while (!handle.send(part)) {
                    currentCoroutineContext().ensureActive()
                    handle.failure.get()?.let { throw it }
                    if (handle.closing.get() || handle.ending.get()) throw IOException("Upstream output closed")
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
