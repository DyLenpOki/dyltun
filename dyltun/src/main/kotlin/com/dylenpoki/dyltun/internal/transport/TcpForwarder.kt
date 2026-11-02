package com.dylenpoki.dyltun.internal.transport

import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.internal.android.StreamConnector
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel

/** One selector for all upstream sockets. Per-connection mailboxes have both byte credit and item bounds. */
internal class TcpForwarder(
    private val connector: StreamConnector,
    private val wakeup: () -> Unit,
    private val fatal: (Throwable) -> Unit
) : Closeable {
    inner class Handle internal constructor(val id: Long, internal val destination: Endpoint) : Closeable {
        internal val writes = ArrayBlockingQueue<ByteArray>(8)
        internal val reads = ArrayBlockingQueue<ByteArray>(4)
        internal val credit = AtomicInteger(TcpEndpoint.SEND_CAPACITY)
        internal val written = AtomicInteger()
        internal val ending = AtomicBoolean()
        internal val closing = AtomicBoolean()
        internal val channel = AtomicReference<SocketChannel?>()
        internal var initialized = false // Selector owner only.
        @Volatile var connected = false
            internal set
        @Volatile var eof = false
            internal set
        val failure = AtomicReference<Throwable?>()
        internal val changed = Channel<Unit>(Channel.CONFLATED)
        internal fun signal() { changed.trySend(Unit); wakeup() }

        fun send(bytes: ByteArray): Boolean {
            require(bytes.size <= 16384)
            if (closing.get() || ending.get() || failure.get() != null) return false
            val accepted = writes.offer(bytes)
            if (accepted) selector.wakeup()
            return accepted
        }

        fun poll(): ByteArray? = reads.poll().also { if (it != null) selector.wakeup() }
        fun drained(): Boolean = eof && reads.isEmpty()
        fun takeWritten(): Int = written.getAndSet(0)
        fun acknowledge(bytes: Int) {
            require(bytes >= 0)
            val updated = credit.addAndGet(bytes)
            check(updated <= TcpEndpoint.SEND_CAPACITY) { "TCP read credit overflow" }
            selector.wakeup()
        }
        fun finish() { ending.set(true); selector.wakeup() }
        override fun close() {
            if (closing.compareAndSet(false, true)) {
                try { channel.get()?.close() } catch (_: IOException) { }
                changed.trySend(Unit)
                selector.wakeup()
            }
        }
    }

    private class Peer(val handle: TcpForwarder.Handle, val socket: SocketChannel, val key: SelectionKey, val created: Long) {
        var pending: ByteBuffer? = null
        var outputClosed = false
    }
    private val selector = Selector.open()
    private val handles = ConcurrentHashMap<Long, Handle>()
    private val peers = mutableMapOf<Long, Peer>()
    private val stopped = AtomicBoolean()
    private val worker = Thread(::execute, "dyltun-tcp").apply { isDaemon = true }

    fun start() { try { worker.start() } catch (error: Throwable) { selector.close(); throw error } }

    fun open(id: Long, destination: Endpoint): Handle {
        check(!stopped.get())
        check(handles.size < 64) { "TCP upstream connection limit reached" }
        val handle = Handle(id, destination)
        check(handles.putIfAbsent(id, handle) == null)
        selector.wakeup()
        return handle
    }

    private fun execute() {
        val scratch = ByteBuffer.allocate(16384)
        try {
            while (!stopped.get()) {
                refresh()
                selector.select(50)
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (!key.isValid) continue
                    val peer = key.attachment() as Peer
                    val handle = peer.handle
                    if (handle.closing.get()) continue
                    try {
                        if (key.isConnectable && peer.socket.finishConnect()) { handle.connected = true; handle.signal() }
                        if (key.isValid && key.isWritable) write(peer)
                        if (key.isValid && key.isReadable && handle.credit.get() > 0 && handle.reads.remainingCapacity() > 0) {
                            scratch.clear()
                            scratch.limit(minOf(scratch.capacity(), handle.credit.get()))
                            val count = peer.socket.read(scratch)
                            if (count < 0) handle.eof = true
                            else if (count > 0) {
                                handle.credit.addAndGet(-count)
                                scratch.flip()
                                val bytes = ByteArray(count).also(scratch::get)
                                check(handle.reads.offer(bytes))
                            }
                            if (count != 0) handle.signal()
                        }
                    } catch (error: Exception) { fail(handle, error) }
                }
            }
        } catch (error: Throwable) { if (!stopped.get()) fatal(error) }
        finally {
            handles.values.forEach { it.close() }
            handles.clear(); peers.clear(); selector.close()
        }
    }

    private fun refresh() {
        for (handle in handles.values) {
            if (handle.closing.get()) {
                peers.remove(handle.id)?.key?.cancel()
                handles.remove(handle.id, handle)
                handle.writes.clear(); handle.reads.clear()
                continue
            }
            if (handle.failure.get() != null) continue
            try {
                if (!handle.initialized) {
                    handle.initialized = true
                    val socket = connector.connect(handle.destination)
                    handle.channel.set(socket)
                    if (stopped.get() || handle.closing.get()) { socket.close(); continue }
                    socket.configureBlocking(false)
                    val key = socket.register(selector, 0)
                    val peer = Peer(handle, socket, key, System.nanoTime())
                    peers[handle.id] = peer
                    key.attach(peer)
                    handle.connected = socket.isConnected
                    handle.signal()
                }
                val peer = peers[handle.id] ?: continue
                if (!peer.socket.isOpen) continue // Both directions finished; endpoint still awaits its FIN ACK.
                if (!handle.connected) {
                    if (System.nanoTime() - peer.created >= 10_000_000_000L) throw IOException("Upstream TCP connect timed out")
                    peer.key.interestOps(SelectionKey.OP_CONNECT)
                    continue
                }
                if (peer.pending == null) peer.pending = handle.writes.poll()?.let(ByteBuffer::wrap)
                if (handle.ending.get() && peer.pending == null && handle.writes.isEmpty() && !peer.outputClosed) {
                    peer.socket.socket().shutdownOutput()
                    peer.outputClosed = true
                }
                if (peer.outputClosed && handle.eof) { peer.socket.close(); continue }
                var operations = 0
                if (!handle.eof && handle.credit.get() > 0 && handle.reads.remainingCapacity() > 0) operations = operations or SelectionKey.OP_READ
                if (peer.pending != null) operations = operations or SelectionKey.OP_WRITE
                peer.key.interestOps(operations)
            } catch (error: Exception) { fail(handle, error) }
        }
    }

    private fun write(peer: Peer) {
        val bytes = peer.pending ?: return
        val count = peer.socket.write(bytes)
        if (count > 0) { peer.handle.written.addAndGet(count); peer.handle.signal() }
        if (!bytes.hasRemaining()) peer.pending = null
    }

    private fun fail(handle: Handle, error: Throwable) {
        if (!handle.closing.get() && handle.failure.compareAndSet(null, error)) {
            try { handle.channel.get()?.close() } catch (_: IOException) { }
            peers.remove(handle.id)?.key?.cancel()
            handle.signal()
        }
    }

    fun requestStop() { stopped.set(true); handles.values.forEach { it.close() }; selector.wakeup() }
    override fun close() {
        requestStop()
        var interrupted = false
        while (worker.isAlive) try { worker.join() } catch (_: InterruptedException) { interrupted = true }
        if (worker.state == Thread.State.NEW) selector.close()
        if (interrupted) Thread.currentThread().interrupt()
    }
}
