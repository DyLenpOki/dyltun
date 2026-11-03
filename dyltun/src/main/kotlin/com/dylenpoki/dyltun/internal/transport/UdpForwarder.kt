package com.dylenpoki.dyltun.internal.transport

import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.ConnectionDiagnostics
import com.dylenpoki.dyltun.DyltunEvent
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.internal.android.DatagramConnector
import com.dylenpoki.dyltun.internal.diagnostics.ConnectionTrace
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal data class UdpTuple(val source: Int, val destination: Int, val sourcePort: Int, val destinationPort: Int)
internal data class UdpRequest(
    val tuple: UdpTuple, val connection: ConnectionInfo, val endpoint: Endpoint, val payload: ByteArray,
    val trace: ConnectionTrace? = null
) {
    fun error(cause: Throwable) = DyltunEvent.ConnectionError(connection, cause, trace?.snapshot() ?: ConnectionDiagnostics())
}
internal data class UdpReply(val tuple: UdpTuple, val connectionId: Long, val payload: ByteArray)

/** All channel/session state belongs to one selector thread. */
internal class UdpForwarder(
    private val connector: DatagramConnector,
    private val reply: (UdpReply) -> Unit,
    private val emit: (DyltunEvent) -> Unit,
    private val fatal: (Throwable) -> Unit
) : Closeable {
    private data class Key(val tuple: UdpTuple, val connectionId: Long)
    private data class Session(val request: UdpRequest, val channel: DatagramChannel, val peer: SocketAddress, var touched: Long)
    private val requests = ArrayBlockingQueue<UdpRequest>(128)
    private val stopped = AtomicBoolean()
    private val selector = Selector.open()
    private val sessions = mutableMapOf<Key, Session>()
    private val worker = Thread(::execute, "dyltun-udp").apply { isDaemon = true }

    fun start() {
        try { worker.start() } catch (error: Throwable) {
            selector.close()
            throw error
        }
    }

    fun send(request: UdpRequest) {
        if (stopped.get()) return
        if (!requests.offer(request)) emit(request.error(IOException("UDP send queue is full")))
        selector.wakeup()
    }

    private fun execute() {
        val receive = ByteBuffer.allocate(65535)
        try {
            while (!stopped.get()) {
                for (batch in 0 until 16) {
                    if (stopped.get()) break
                    sendNow(requests.poll() ?: break)
                }
                if (requests.isEmpty()) selector.select(250) else selector.selectNow()
                val selected = selector.selectedKeys().iterator()
                while (selected.hasNext()) {
                    val key = selected.next()
                    selected.remove()
                    if (!key.isValid) continue
                    val session = key.attachment() as Session
                    try {
                        receive.clear()
                        val sender = session.channel.receive(receive) ?: continue
                        if (sender != session.peer) continue
                        session.touched = System.nanoTime()
                        receive.flip()
                        val bytes = ByteArray(receive.remaining()).also { receive.get(it) }
                        reply(UdpReply(session.request.tuple, session.request.connection.id, bytes))
                    } catch (error: Exception) {
                        emit(session.request.error(error))
                        remove(Key(session.request.tuple, session.request.connection.id))
                    }
                }
                val now = System.nanoTime()
                sessions.filterValues { now - it.touched >= 30_000_000_000L }.keys.toList().forEach(::remove)
            }
        } catch (error: Throwable) {
            if (!stopped.get()) fatal(error)
        } finally {
            sessions.keys.toList().forEach(::remove)
            requests.clear()
            selector.close()
        }
    }

    private fun sendNow(request: UdpRequest) {
        val key = Key(request.tuple, request.connection.id)
        try {
            sessions.keys.filter { it.tuple == request.tuple && it != key }.toList().forEach(::remove)
            val session = sessions[key] ?: run {
                check(sessions.size < 128) { "UDP connection limit reached" }
                val onConnected = request.trace?.beginUpstream(request.endpoint)
                val channel = connector.connect(request.endpoint)
                try {
                    val peer = channel.socket().remoteSocketAddress ?: throw IOException("UDP connector returned an unconnected channel")
                    // Connected NIO write(empty) can be a no-op on JDKs. Unconnected send(empty)
                    // reaches sendto; receive explicitly verifies the same peer instead.
                    channel.disconnect()
                    channel.configureBlocking(false)
                    val created = Session(request.copy(payload = byteArrayOf()), channel, peer, System.nanoTime())
                    channel.register(selector, SelectionKey.OP_READ, created)
                    sessions[key] = created
                    val address = peer as? InetSocketAddress
                    onConnected?.invoke(address?.address?.hostAddress?.let { Endpoint(it, address.port) })
                    created
                } catch (error: Throwable) {
                    channel.close()
                    throw error
                }
            }
            val written = session.channel.send(ByteBuffer.wrap(request.payload), session.peer)
            if (written != request.payload.size) throw IOException("UDP datagram could not be sent without blocking")
            session.touched = System.nanoTime()
        } catch (error: Exception) {
            emit(request.error(error))
            remove(key)
        }
    }

    private fun remove(key: Key) {
        val session = sessions.remove(key) ?: return
        try { session.channel.close() } catch (error: IOException) {
            emit(session.request.error(error))
        }
    }

    fun requestStop() {
        stopped.set(true)
        selector.wakeup()
    }

    override fun close() {
        requestStop()
        var interrupted = false
        while (worker.isAlive) try { worker.join() } catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
        if (worker.state == Thread.State.NEW) selector.close()
    }
}
