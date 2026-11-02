package com.dylenpoki.dyltun.internal.engine

import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.DyltunEvent
import com.dylenpoki.dyltun.RoutingContext
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.RoutingEvaluator
import com.dylenpoki.dyltun.TransportProtocol
import com.dylenpoki.dyltun.internal.inspection.ProtocolProbe
import com.dylenpoki.dyltun.internal.packet.PacketWriter
import com.dylenpoki.dyltun.internal.packet.TcpPacket
import com.dylenpoki.dyltun.internal.protocol.http1.HttpRuntime
import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint
import com.dylenpoki.dyltun.internal.stack.TcpState
import com.dylenpoki.dyltun.internal.transport.TcpForwarder
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/** Tuple table, routing and socket orchestration; TCP protocol state remains in TcpEndpoint. */
internal class TcpRelay(
    private val config: DyltunConfig,
    private val forwarder: TcpForwarder,
    private val write: (ByteArray) -> Unit,
    private val emit: (DyltunEvent) -> Unit,
    private val nextId: () -> Long,
    private val packetId: () -> Int,
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
    private val http: HttpRuntime? = null,
    private val initialSequence: () -> Long = secureSequenceGenerator()
) : Closeable {
    private data class Tuple(val source: Int, val destination: Int, val sourcePort: Int, val destinationPort: Int)
    private class Flow(val connection: ConnectionInfo, val endpoint: TcpEndpoint) {
        var upstream: TcpForwarder.Handle? = null
        var probe: ProtocolProbe? = null
        var http: HttpRuntime.Bridge? = null
        var httpCommitted = 0
        var receivedEof = false
        var sentEof = false
    }
    private val flows = linkedMapOf<Tuple, Flow>()

    fun accept(packet: TcpPacket) {
        val tuple = Tuple(packet.ip.source, packet.ip.destination, packet.sourcePort, packet.destinationPort)
        var flow = flows[tuple]
        if (flow == null) {
            if (packet.flags and TcpEndpoint.SYN == 0 || packet.flags and (TcpEndpoint.ACK or TcpEndpoint.RST) != 0) {
                PacketWriter.reset(packet, packetId())?.let(write)
                return
            }
            if (flows.size >= 320 || flows.values.count { !it.endpoint.transportClosed } >= 64) {
                emit(DyltunEvent.PacketRejected("TCP connection table is full"))
                PacketWriter.reset(packet, packetId())?.let(write)
                return
            }
            val info = ConnectionInfo(nextId(), TransportProtocol.TCP, address(tuple.source), tuple.sourcePort, address(tuple.destination), tuple.destinationPort)
            try {
                if (RoutingEvaluator.evaluate(config.routing, RoutingContext(info)) == RoutingDecision.Reset) {
                    PacketWriter.reset(packet, packetId())?.let(write)
                    return
                }
                val mtu = try { NetworkInterface.getByInetAddress(info.sourceAddress)?.mtu ?: 1500 } catch (_: Exception) { 1500 }
                val endpoint = TcpEndpoint(packet, initialSequence(), clock, { output ->
                    write(PacketWriter.tcp(tuple.destination, tuple.source, tuple.destinationPort, tuple.sourcePort,
                        output.sequence, output.acknowledgement, output.flags, output.window, output.payload, packetId(), output.mss))
                }, { count -> flows[tuple]?.upstream?.acknowledge(count) }, { cause ->
                    flows[tuple]?.upstream?.close()
                    flows[tuple]?.http?.close()
                    if (cause != null) emit(DyltunEvent.ConnectionError(info, cause))
                }, (mtu.coerceIn(68, 65535) - 40).coerceAtMost(1460))
                flow = Flow(info, endpoint)
                flows[tuple] = flow
                endpoint.start()
            } catch (error: Exception) {
                emit(DyltunEvent.ConnectionError(info, error))
                flows.remove(tuple)
                PacketWriter.reset(packet, packetId())?.let(write)
                return
            }
        } else flow.endpoint.accept(packet)
        advance(flow)
    }

    fun tick() {
        val iterator = flows.values.iterator()
        while (iterator.hasNext()) {
            val flow = iterator.next()
            flow.endpoint.tick()
            advance(flow)
            if (flow.endpoint.state == TcpState.CLOSED && flow.endpoint.transportClosed) iterator.remove()
        }
    }

    private fun advance(flow: Flow) {
        val tcp = flow.endpoint
        if (!tcp.handshakeComplete || tcp.transportClosed) return
        try {
            val httpBridge = flow.http
            if (httpBridge != null) { advanceHttp(flow, httpBridge); return }
            var upstream = flow.upstream
            if (upstream == null) {
                val probe = flow.probe ?: ProtocolProbe(config.protocols, flow.connection, clock, http != null).also { flow.probe = it }
                if (!probe.advance(tcp.peekClient(ProtocolProbe.MAX_PREFIX), tcp.clientFinished)) return
                val metadata = probe.metadata
                val context = RoutingContext(flow.connection, metadata.firstOrNull(), detectedProtocols = metadata)
                val destination = when (val decision = RoutingEvaluator.evaluate(config.routing, context)) {
                    RoutingDecision.Reset -> { tcp.abort(IOException("TCP connection rejected by routing policy")); return }
                    is RoutingDecision.Redirect -> decision.destination
                    else -> flow.connection.originalDestination
                }
                if (probe.httpMatched && http != null) {
                    val bridge = http.open(flow.connection, metadata)
                    flow.http = bridge
                    advanceHttp(flow, bridge)
                    return
                }
                upstream = forwarder.open(flow.connection.id, destination)
                flow.upstream = upstream
            }
            upstream.failure.get()?.let { tcp.abort(it); return }
            val written = upstream.takeWritten()
            if (written > 0) tcp.consumedClient(written)
            if (tcp.transportClosed) return
            repeat(4) {
                if (tcp.terminal) return@repeat
                val bytes = upstream.poll() ?: return@repeat
                check(tcp.offerServer(bytes)) { "Upstream exceeded reserved TCP send credit" }
            }
            if (upstream.drained() && !flow.receivedEof) { flow.receivedEof = true; tcp.finishServer() }
            repeat(4) {
                if (tcp.clientAvailable == 0) return@repeat
                val bytes = tcp.peekClient(16384)
                if (upstream.send(bytes)) tcp.commitClient(bytes.size)
            }
            if (tcp.clientFinished && tcp.clientAvailable == 0 && !flow.sentEof) {
                upstream.finish()
                flow.sentEof = true
            }
        } catch (error: Exception) { tcp.abort(error) }
    }

    private fun advanceHttp(flow: Flow, bridge: HttpRuntime.Bridge) {
        val tcp = flow.endpoint
        bridge.failure.get()?.let { tcp.abort(it); return }
        val consumed = bridge.takeConsumed()
        if (consumed > 0) { flow.httpCommitted -= consumed; tcp.consumedClient(consumed) }
        if (tcp.transportClosed) return
        repeat(4) {
            if (tcp.terminal || flow.receivedEof) return@repeat
            val bytes = bridge.peekOutput()
            if (bytes != null && tcp.offerServer(bytes)) bridge.commitOutput()
        }
        if (bridge.outputDrained && !flow.receivedEof) { flow.receivedEof = true; tcp.finishServer() }
        if (bridge.completed) {
            bridge.takeConsumed() // Completion publishes the final consumption counter before tail discard.
            // The handler has deliberately closed the client connection; discard its unconsumed tail.
            if (flow.httpCommitted > 0) { tcp.consumedClient(flow.httpCommitted); flow.httpCommitted = 0 }
            val tail = tcp.clientAvailable
            if (tail > 0) { tcp.commitClient(tail); tcp.consumedClient(tail) }
            return
        }
        repeat(4) {
            if (tcp.clientAvailable == 0) return@repeat
            val bytes = tcp.peekClient(16384)
            if (bridge.accept(bytes)) { tcp.commitClient(bytes.size); flow.httpCommitted += bytes.size }
        }
        if (tcp.clientFinished && tcp.clientAvailable == 0 && !flow.sentEof) { bridge.finishInput(); flow.sentEof = true }
    }

    override fun close() { flows.values.forEach { it.upstream?.close(); it.http?.close() }; flows.clear() }

    private fun address(value: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    )
}

private fun secureSequenceGenerator(): () -> Long {
    val random = SecureRandom()
    return { random.nextInt().toLong() and 0xffff_ffffL }
}
