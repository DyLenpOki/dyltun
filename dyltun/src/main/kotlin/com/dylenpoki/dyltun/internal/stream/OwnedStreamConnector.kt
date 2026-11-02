package com.dylenpoki.dyltun.internal.stream

import com.dylenpoki.dyltun.Endpoint
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Closes forgotten peers and rejects peers opened concurrently with cancellation. */
internal class OwnedStreamConnector(private val delegate: StreamConnector) : StreamConnector, Closeable {
    private val lock = Any()
    private val peers = linkedSetOf<OwnedPeer>()
    private var closed = false

    override fun open(endpoint: Endpoint): StreamPeer {
        synchronized(lock) { checkOpen() }
        val peer = OwnedPeer(delegate.open(endpoint))
        synchronized(lock) {
            if (!closed) { peers += peer; return peer }
        }
        peer.close()
        throw IOException("Stream connection is closed")
    }

    private fun checkOpen() {
        if (closed) throw IOException("Stream connection is closed")
    }

    override fun close() {
        val snapshot = synchronized(lock) {
            if (closed) return
            closed = true
            peers.toList().also { peers.clear() }
        }
        var failure: Throwable? = null
        for (peer in snapshot) {
            try { peer.close() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private inner class OwnedPeer(private val peer: StreamPeer) : StreamPeer {
        private val closed = AtomicBoolean()
        override val source: ByteSource get() = peer.source
        override val sink: ByteSink get() = peer.sink
        override fun finish() { peer.finish() }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { peer.close() } finally { synchronized(lock) { peers.remove(this) } }
        }
    }
}
