package com.dylenpoki.dyltun.internal.engine

import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.ConnectionStage
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.DyltunEvent
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.RoutingContext
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.RoutingEvaluator
import com.dylenpoki.dyltun.TransportProtocol
import com.dylenpoki.dyltun.internal.android.DatagramConnector
import com.dylenpoki.dyltun.internal.android.PacketDevice
import com.dylenpoki.dyltun.internal.android.StreamConnector
import com.dylenpoki.dyltun.internal.diagnostics.ConnectionTrace
import com.dylenpoki.dyltun.internal.diagnostics.DnsNames
import com.dylenpoki.dyltun.internal.packet.Ipv4Packet
import com.dylenpoki.dyltun.internal.packet.Ipv4Reassembler
import com.dylenpoki.dyltun.internal.packet.PacketWriter
import com.dylenpoki.dyltun.internal.protocol.http1.HttpStreamHandler
import com.dylenpoki.dyltun.internal.stream.StreamHandler
import com.dylenpoki.dyltun.internal.stream.StreamRuntime
import com.dylenpoki.dyltun.internal.transport.TcpForwarder
import com.dylenpoki.dyltun.internal.transport.UdpForwarder
import com.dylenpoki.dyltun.internal.transport.UdpReply
import com.dylenpoki.dyltun.internal.transport.UdpRequest
import com.dylenpoki.dyltun.internal.transport.UdpTuple
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class IpPacketRuntime(
    private val device: PacketDevice,
    private val config: DyltunConfig,
    private val connector: DatagramConnector,
    private val emit: (DyltunEvent) -> Unit,
    private val streamConnector: StreamConnector,
    private val createHandlers: () -> List<StreamHandler> = {
        if (config.hooks != null) listOf(HttpStreamHandler(config)) else emptyList()
    }
) : SessionRuntime {
    private val stopped = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private val replies = ArrayBlockingQueue<UdpReply>(64)
    private data class Flow(val connection: ConnectionInfo, val endpoint: Endpoint, var touched: Long, val trace: ConnectionTrace)
    private val connections = linkedMapOf<UdpTuple, Flow>()
    private val fragments = Ipv4Reassembler()
    private val dns = DnsNames()
    private var nextConnection = 0L
    private var packetId = 0
    @Volatile private var forwarder: UdpForwarder? = null
    @Volatile private var tcpForwarder: TcpForwarder? = null
    private var tcpRelay: TcpRelay? = null
    @Volatile private var streamRuntime: StreamRuntime? = null

    override fun run(onReady: () -> Unit) {
        val udp = UdpForwarder(connector, { reply ->
            if (!replies.offer(reply)) emit(DyltunEvent.PacketRejected("UDP receive queue is full"))
            device.wakeup()
        }, emit, { error ->
            failure.compareAndSet(null, error)
            requestStop()
        })
        forwarder = udp
        udp.start()
        val tcp = TcpForwarder(streamConnector, device::wakeup, { error ->
            failure.compareAndSet(null, error)
            requestStop()
        })
        tcpForwarder = tcp
        tcp.start()
        val streams = StreamRuntime(config.protocols, tcp, device::wakeup,
            emit, createHandlers())
        streamRuntime = streams
        val relay = TcpRelay(config, streams, device::write, emit, { ++nextConnection }, { packetId++ }, dns = dns)
        tcpRelay = relay
        val buffer = ByteArray(65535)
        onReady()
        while (!stopped.get()) {
            drainReplies()
            relay.tick()
            val now = System.nanoTime()
            connections.entries.removeAll { now - it.value.touched >= 30_000_000_000L }
            fragments.expire(now)
            val size = device.read(buffer, 50)
            if (size < 0) {
                if (!stopped.get()) throw IOException("Packet descriptor reached EOF")
                break
            }
            if (size == 0) continue
            try {
                val packet = fragments.accept(Ipv4Packet.parse(buffer, size), System.nanoTime()) ?: continue
                when (packet.protocol) {
                    17 -> forward(packet, udp)
                    6 -> relay.accept(packet.tcp())
                    else -> emit(DyltunEvent.PacketRejected("Unsupported IP protocol: ${packet.protocol}"))
                }
            } catch (error: IllegalArgumentException) {
                emit(DyltunEvent.PacketRejected(error.message ?: "Invalid packet"))
            }
        }
        failure.get()?.let { throw it }
    }

    private fun forward(packet: Ipv4Packet, udp: UdpForwarder) {
        val datagram = packet.udp()
        val tuple = UdpTuple(packet.source, packet.destination, datagram.sourcePort, datagram.destinationPort)
        val existing = connections[tuple]
        val connection = existing?.connection ?: ConnectionInfo(
            ++nextConnection, TransportProtocol.UDP, address(tuple.source), tuple.sourcePort,
            address(tuple.destination), tuple.destinationPort
        )
        val trace = existing?.trace ?: ConnectionTrace(connection, {
            dns.candidates(requireNotNull(connection.destinationAddress.hostAddress))
        })
        try {
            if (existing == null) trace.stage(ConnectionStage.ROUTING)
            val endpoint = existing?.endpoint ?: when (val decision = RoutingEvaluator.evaluate(config.routing, RoutingContext(connection))) {
                RoutingDecision.Drop -> return
                is RoutingDecision.Redirect -> decision.destination
                else -> connection.originalDestination
            }
            if (existing == null && connections.size >= 128) throw IOException("UDP connection limit reached")
            val flow = existing ?: Flow(connection, endpoint, System.nanoTime(), trace).also {
                trace.beginUpstream(endpoint)
                connections[tuple] = it
            }
            flow.touched = System.nanoTime()
            val payload = packet.bytes.copyOfRange(datagram.payloadOffset, packet.length)
            trace.clientBytes(payload.size)
            if (tuple.destinationPort == 53) dns.query(connection.id, payload)
            udp.send(UdpRequest(tuple, connection, endpoint, payload, trace))
        } catch (error: Exception) {
            emit(DyltunEvent.ConnectionError(connection, error, trace.snapshot()))
        }
    }

    private fun drainReplies() {
        repeat(64) {
            val reply = replies.poll() ?: return
            val key = reply.tuple
            val flow = connections[key] ?: return@repeat
            if (flow.connection.id != reply.connectionId) return@repeat
            if (reply.payload.size > 65507) {
                emit(DyltunEvent.ConnectionError(flow.connection, IOException("Reply exceeds IPv4 UDP maximum"), flow.trace.snapshot()))
                return@repeat
            }
            flow.touched = System.nanoTime()
            flow.trace.serverBytes(reply.payload.size)
            if (key.destinationPort == 53) dns.response(flow.connection.id, reply.payload)
            val packet = PacketWriter.udp(key.destination, key.source, key.destinationPort, key.sourcePort, reply.payload, packetId++)
            val mtu = try { NetworkInterface.getByInetAddress(address(key.source))?.mtu ?: 1500 } catch (_: Exception) { 1500 }
            PacketWriter.fragments(packet, mtu.coerceIn(68, 65535)).forEach(device::write)
        }
    }

    override fun requestStop() {
        stopped.set(true)
        forwarder?.requestStop()
        tcpForwarder?.requestStop()
        streamRuntime?.requestStop()
        device.requestStop()
    }

    override fun close() {
        requestStop()
        var failure: Throwable? = null
        for (resource in listOfNotNull(forwarder, tcpForwarder, tcpRelay, streamRuntime, device, fragments)) {
            try { resource.close() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
            }
        }
        replies.clear()
        connections.clear()
        dns.clear()
        failure?.let { throw it }
    }

    private fun address(value: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    )
}
