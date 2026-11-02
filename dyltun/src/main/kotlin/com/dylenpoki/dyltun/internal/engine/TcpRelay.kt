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
import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint
import com.dylenpoki.dyltun.internal.stack.TcpState
import com.dylenpoki.dyltun.internal.stream.CHUNK_BYTES
import com.dylenpoki.dyltun.internal.stream.StreamRuntime
import com.dylenpoki.dyltun.internal.stream.StreamSession
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/** Tuple table and packet-loop orchestration. Stream processors never manipulate TCP state. */
internal class TcpRelay(
    private val config: DyltunConfig,
    private val streams: StreamRuntime,
    private val write: (ByteArray) -> Unit,
    private val emit: (DyltunEvent) -> Unit,
    private val nextId: () -> Long,
    private val packetId: () -> Int,
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
    private val initialSequence: () -> Long = secureSequenceGenerator()
) : Closeable {
    private data class Tuple(val source: Int, val destination: Int, val sourcePort: Int, val destinationPort: Int)
    private class Flow(val connection: ConnectionInfo, val endpoint: TcpEndpoint) {
        var probe: ProtocolProbe? = null
        var session: StreamSession? = null
        var committedInput = 0
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
                }, { count -> flows[tuple]?.session?.acknowledgeOutput(count) }, { cause ->
                    flows[tuple]?.session?.close()
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
            var session = flow.session
            if (session == null) {
                val probe = flow.probe ?: streams.probe(flow.connection, clock).also { flow.probe = it }
                if (!probe.advance(tcp.peekClient(ProtocolProbe.MAX_PREFIX), tcp.clientFinished)) return
                val detection = probe.result
                val metadata = detection.metadata
                val context = RoutingContext(flow.connection, metadata.firstOrNull(), detectedProtocols = metadata)
                val destination = when (val decision = RoutingEvaluator.evaluate(config.routing, context)) {
                    RoutingDecision.Reset -> { tcp.abort(IOException("TCP connection rejected by routing policy")); return }
                    is RoutingDecision.Redirect -> decision.destination
                    else -> flow.connection.originalDestination
                }
                session = streams.open(flow.connection, destination, detection)
                flow.session = session
                flow.probe = null
            }
            advanceSession(flow, session)
        } catch (error: Exception) { tcp.abort(error) }
    }

    private fun advanceSession(flow: Flow, session: StreamSession) {
        val tcp = flow.endpoint
        session.failure?.let { tcp.abort(it); return }
        val consumed = session.takeConsumed()
        if (consumed > 0) {
            check(consumed <= flow.committedInput) { "Stream consumed uncommitted input" }
            flow.committedInput -= consumed
            tcp.consumedClient(consumed)
        }
        if (tcp.transportClosed) return
        repeat(4) {
            if (tcp.terminal || flow.receivedEof) return@repeat
            val bytes = session.peekOutput()
            if (bytes != null && tcp.offerServer(bytes)) session.commitOutput()
        }
        if (session.outputDrained && !flow.receivedEof) { flow.receivedEof = true; tcp.finishServer() }
        if (session.completed) {
            session.takeConsumed() // Completion publishes the final consumption counter before tail discard.
            // The handler has deliberately closed the client connection; discard its unconsumed tail.
            if (flow.committedInput > 0) { tcp.consumedClient(flow.committedInput); flow.committedInput = 0 }
            val tail = tcp.clientAvailable
            if (tail > 0) { tcp.commitClient(tail); tcp.consumedClient(tail) }
            return
        }
        repeat(4) {
            if (tcp.clientAvailable == 0) return@repeat
            val bytes = tcp.peekClient(CHUNK_BYTES)
            if (session.accept(bytes)) { tcp.commitClient(bytes.size); flow.committedInput += bytes.size }
        }
        if (tcp.clientFinished && tcp.clientAvailable == 0 && !flow.sentEof) { session.finishInput(); flow.sentEof = true }
    }

    override fun close() { flows.values.forEach { it.session?.close() }; flows.clear() }

    private fun address(value: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    )
}

private fun secureSequenceGenerator(): () -> Long {
    val random = SecureRandom()
    return { random.nextInt().toLong() and 0xffff_ffffL }
}
