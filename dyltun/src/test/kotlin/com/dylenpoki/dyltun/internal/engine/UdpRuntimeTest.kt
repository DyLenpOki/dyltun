package com.dylenpoki.dyltun.internal.engine

import com.dylenpoki.dyltun.ConnectionRule
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.DyltunEvent
import com.dylenpoki.dyltun.DyltunState
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.RoutingContext
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.internal.android.DatagramConnector
import com.dylenpoki.dyltun.internal.android.PacketDevice
import com.dylenpoki.dyltun.internal.android.StreamConnector
import com.dylenpoki.dyltun.internal.packet.Ipv4Packet
import com.dylenpoki.dyltun.internal.packet.Ipv4Reassembler
import com.dylenpoki.dyltun.internal.packet.PacketWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpRuntimeTest {
    private val source = 0x0a000002
    private val destination = 0xc6336409.toInt()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test fun realSocketRoundTripMultipleRepliesEmptyDatagramsAndFragmentedRequest() {
        DatagramSocket(0, loopback).use { server ->
            server.soTimeout = 3000
            val device = QueueDevice()
            val endpoints = LinkedBlockingQueue<Endpoint>()
            val config = DyltunConfig.Builder().apply {
                routing { rule(object : ConnectionRule {
                    override fun evaluate(context: RoutingContext) = RoutingDecision.Redirect(Endpoint("127.0.0.1", server.localPort))
                }) }
            }.build()
            val session = ManagedSession(java.io.Closeable {}) { emit ->
                IpPacketRuntime(device, config, DatagramConnector { endpoint ->
                    endpoints.offer(endpoint)
                    DatagramChannel.open().apply { connect(InetSocketAddress(endpoint.host, endpoint.port)) }
                }, emit, StreamConnector { error("Unexpected TCP connection") })
            }
            session.start()
            try {
                val payload = ByteArray(3000) { it.toByte() }
                PacketWriter.fragments(PacketWriter.udp(source, destination, 12345, 53, payload, 1), 1200)
                    .reversed().forEach(device.incoming::offer)
                val received = DatagramPacket(ByteArray(4096), 4096)
                server.receive(received)
                assertArrayEquals(payload, received.data.copyOf(received.length))
                assertEquals(Endpoint("127.0.0.1", server.localPort), endpoints.poll(2, TimeUnit.SECONDS))
                for (reply in listOf("first".toByteArray(), byteArrayOf(), ByteArray(3000) { 7 })) {
                    server.send(DatagramPacket(reply, reply.size, received.socketAddress))
                    val assembler = Ipv4Reassembler()
                    var packet: Ipv4Packet? = null
                    assembler.use {
                        while (packet == null) {
                            val bytes = device.outgoing.poll(3, TimeUnit.SECONDS) ?: error("Missing response")
                            packet = it.accept(Ipv4Packet.parse(bytes), System.nanoTime())
                        }
                    }
                    val response = packet!!
                    val udp = response.udp()
                    assertEquals(destination, response.source)
                    assertEquals(source, response.destination)
                    assertEquals(53, udp.sourcePort)
                    assertEquals(12345, udp.destinationPort)
                    assertArrayEquals(reply, response.bytes.copyOfRange(udp.payloadOffset, response.length))
                }
                // Same tuple reuses the protected upstream datagram socket, including an empty request.
                device.incoming.offer(PacketWriter.udp(source, destination, 12345, 53, byteArrayOf(), 2))
                val empty = DatagramPacket(ByteArray(1), 1)
                server.receive(empty)
                assertEquals(0, empty.length)
                assertEquals(received.port, empty.port)
                assertTrue(endpoints.isEmpty())
            } finally {
                session.close()
                runBlocking { withTimeout(3000) { session.awaitClosed() } }
            }
            assertEquals(DyltunState.Stopped, session.state.value)
            assertTrue(device.closed)
            assertTrue(session.events.replayCache.none { it is DyltunEvent.ConnectionError })
        }
    }

    @Test fun invalidPacketsAndDropRulesNeverOpenSocketAndDoNotKillSession() {
        val device = QueueDevice()
        val attempts = AtomicInteger()
        val config = DyltunConfig.Builder().apply {
            routing { rule(object : ConnectionRule {
                override fun evaluate(context: RoutingContext) = RoutingDecision.Drop
            }) }
        }.build()
        val session = ManagedSession(java.io.Closeable {}) { emit ->
            IpPacketRuntime(device, config, DatagramConnector { attempts.incrementAndGet(); error("Should be dropped") }, emit,
                StreamConnector { error("Unexpected TCP connection") })
        }
        session.start()
        try {
            device.incoming.offer(PacketWriter.udp(source, destination, 12345, 53, byteArrayOf(1), 1).also { it[it.lastIndex] = 2 })
            device.incoming.offer(PacketWriter.udp(source, destination, 12345, 53, byteArrayOf(3), 2))
            assertTrue(device.processed.tryAcquire(2, 3, TimeUnit.SECONDS))
        } finally {
            session.close(); runBlocking { withTimeout(3000) { session.awaitClosed() } }
        }
        assertEquals(0, attempts.get())
        assertEquals(DyltunState.Stopped, session.state.value)
        assertTrue(session.events.replayCache.any { it is DyltunEvent.PacketRejected })
    }

    private class QueueDevice : PacketDevice {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val outgoing = LinkedBlockingQueue<ByteArray>()
        val processed = java.util.concurrent.Semaphore(0)
        @Volatile var closed = false
        private var previous = false
        override fun read(buffer: ByteArray): Int {
            if (previous) { processed.release(); previous = false }
            val bytes = incoming.poll(50, TimeUnit.MILLISECONDS) ?: return 0
            bytes.copyInto(buffer)
            previous = bytes.isNotEmpty()
            return bytes.size
        }
        override fun write(packet: ByteArray) { outgoing.offer(packet.copyOf()) }
        override fun wakeup() { incoming.offer(byteArrayOf()) }
        override fun close() { closed = true; wakeup() }
    }
}
