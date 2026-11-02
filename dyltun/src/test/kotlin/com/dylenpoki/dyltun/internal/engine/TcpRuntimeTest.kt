package com.dylenpoki.dyltun.internal.engine

import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimits
import com.dylenpoki.dyltun.BodyOverflow
import com.dylenpoki.dyltun.ConnectionRule
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.DyltunEvent
import com.dylenpoki.dyltun.DyltunState
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.MqttInspector
import com.dylenpoki.dyltun.ResponseSource
import com.dylenpoki.dyltun.RoutingContext
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.TlsClientHelloInspector
import com.dylenpoki.dyltun.internal.android.DatagramConnector
import com.dylenpoki.dyltun.internal.android.PacketDevice
import com.dylenpoki.dyltun.internal.android.StreamConnector
import com.dylenpoki.dyltun.internal.packet.Ipv4Packet
import com.dylenpoki.dyltun.internal.packet.PacketWriter
import com.dylenpoki.dyltun.internal.packet.TcpPacket
import com.dylenpoki.dyltun.internal.protocol.http1.BodyReader
import com.dylenpoki.dyltun.internal.protocol.http1.ByteSource
import com.dylenpoki.dyltun.internal.protocol.http1.HttpFraming
import com.dylenpoki.dyltun.internal.protocol.http1.HttpInput
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint.Companion.ACK
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint.Companion.FIN
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint.Companion.RST
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint.Companion.SYN
import com.dylenpoki.dyltun.internal.stack.unwrapSequence
import com.dylenpoki.dyltun.internal.stack.wireSequence
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.SocketChannel
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpRuntimeTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test fun realSocketRoundTripReorderingLossLargeResponseAndHalfClose() {
        ServerSocket(0, 50, loopback).use { server ->
            server.soTimeout = 5000
            val connections = AtomicInteger()
            val config = redirect(server.localPort)
            Harness(config, StreamConnector { endpoint ->
                connections.incrementAndGet()
                channel(endpoint)
            }).use { harness ->
                var lostSyn = false
                var lostData = false
                harness.device.drop = { packet ->
                    when {
                        !lostSyn && packet.flags and SYN != 0 -> { lostSyn = true; true }
                        !lostData && packet.payloadLength > 0 -> { lostData = true; true }
                        else -> false
                    }
                }
                val client = harness.connect()
                server.accept().use { upstream ->
                    upstream.soTimeout = 5000
                    // A duplicated/out-of-order request must reach the real socket once.
                    client.sendAt(3, "def".toByteArray())
                    client.sendAt(3, "def".toByteArray())
                    client.sendAt(0, "abc".toByteArray())
                    client.position += 6
                    client.finish()
                    val request = ByteArray(6)
                    DataInputStream(upstream.getInputStream()).readFully(request)
                    assertArrayEquals("abcdef".toByteArray(), request)
                    assertEquals(-1, upstream.getInputStream().read())
                    val response = ByteArray(200000) { (it * 31).toByte() }
                    val worker = Executors.newSingleThreadExecutor()
                    try {
                        val sent = worker.submit { upstream.getOutputStream().write(response); upstream.shutdownOutput() }
                        client.readUntilFinished()
                        sent.get(5, TimeUnit.SECONDS)
                        assertArrayEquals(response, client.body.toByteArray())
                    } finally { worker.shutdownNow() }
                }
                assertEquals(1, connections.get())
                assertTrue(lostSyn && lostData)
                assertTrue(harness.session.events.replayCache.none { it is DyltunEvent.ConnectionError })
            }
        }
    }

    @Test fun sameSourcePortDifferentDestinationsHaveIndependentStreams() {
        ServerSocket(0, 50, loopback).use { server ->
            server.soTimeout = 5000
            Harness(redirect(server.localPort), StreamConnector(::channel)).use { harness ->
                val first = harness.connect(destination = 0xc6336401.toInt())
                server.accept().use { a ->
                    a.soTimeout = 5000
                    val second = harness.connect(destination = 0xc6336402.toInt())
                    server.accept().use { b ->
                        b.soTimeout = 5000
                        first.sendAt(0, "one".toByteArray()); first.position += 3
                        second.sendAt(0, "two".toByteArray()); second.position += 3
                        val one = ByteArray(3); DataInputStream(a.getInputStream()).readFully(one)
                        val two = ByteArray(3); DataInputStream(b.getInputStream()).readFully(two)
                        assertArrayEquals("one".toByteArray(), one); assertArrayEquals("two".toByteArray(), two)
                        b.getOutputStream().write("reply-two".toByteArray()); b.shutdownOutput()
                        a.getOutputStream().write("reply-one".toByteArray()); a.shutdownOutput()
                        first.readUntilFinished(); second.readUntilFinished()
                        assertEquals("reply-one", first.body.toString("UTF-8"))
                        assertEquals("reply-two", second.body.toString("UTF-8"))
                        first.finish(); second.finish()
                    }
                }
            }
        }
    }

    @Test fun fragmentedClientHelloIsResetBeforeAnyUpstreamSocket() {
        val attempts = AtomicInteger()
        val config = DyltunConfig.Builder().apply {
            protocols { install(TlsClientHelloInspector()) }
            routing { tlsSni(listOf("other.example.com", "blocked.example.com"), includeSubdomains = true) { reset() } }
        }.build()
        Harness(config, StreamConnector { attempts.incrementAndGet(); error("Denied stream escaped") }).use { harness ->
            val client = harness.connect()
            val hello = clientHello("www.blocked.example.com")
            client.sendAt(0, hello.copyOfRange(0, 17))
            // The second TCP segment includes the rest of the first TLS record and another record.
            client.sendAt(17, hello.copyOfRange(17, hello.size))
            harness.until { client.reset }
            assertEquals(0, attempts.get())
        }
    }

    @Test fun mqttRedirectReplaysEntireConnectAndReturnsBrokerData() {
        ServerSocket(0, 50, loopback).use { broker ->
            broker.soTimeout = 5000
            val endpoints = LinkedBlockingQueue<Endpoint>()
            val config = DyltunConfig.Builder().apply {
                protocols { install(MqttInspector()); install(TlsClientHelloInspector()) }
                routing { mqtt { redirect("127.0.0.1", broker.localPort) } }
            }.build()
            Harness(config, StreamConnector { endpoint -> endpoints.offer(endpoint); channel(endpoint) }).use { harness ->
                val client = harness.connect()
                val connect = byteArrayOf(0x10, 18, 0, 4, 77, 81, 84, 84, 4, 2, 0, 60, 0, 6, 99, 108, 105, 101, 110, 116)
                client.sendAt(0, connect.copyOfRange(0, 7))
                client.sendAt(7, connect.copyOfRange(7, connect.size)); client.position += connect.size
                broker.accept().use { socket ->
                    socket.soTimeout = 5000
                    val received = ByteArray(connect.size)
                    DataInputStream(socket.getInputStream()).readFully(received)
                    assertArrayEquals(connect, received)
                    assertEquals(Endpoint("127.0.0.1", broker.localPort), endpoints.poll(2, TimeUnit.SECONDS))
                    socket.getOutputStream().write(byteArrayOf(0x20, 2, 0, 0)); socket.shutdownOutput()
                    client.readUntilFinished()
                    assertArrayEquals(byteArrayOf(0x20, 2, 0, 0), client.body.toByteArray())
                    client.finish()
                }
            }
        }
    }

    @Test fun failingUpstreamResetsOnlyItsOwnConnection() {
        val config = DyltunConfig.Builder().build()
        Harness(config, StreamConnector { throw java.io.IOException("test connect failure") }).use { harness ->
            val client = harness.connect()
            harness.until { client.reset }
            assertEquals(DyltunState.Running, harness.session.state.value)
            runBlocking { withTimeout(3000) { harness.session.events.first { it is DyltunEvent.ConnectionError } } }
        }
    }

    @Test fun httpLocalPipelineUsesNoUpstreamSocket() {
        val attempts = AtomicInteger()
        val requests = AtomicInteger()
        val responses = AtomicInteger()
        val config = DyltunConfig.Builder().apply {
            hooks = object : HttpHooks {
                override fun request(flow: HttpFlow) {
                    requests.incrementAndGet()
                    flow.response = HttpResponse.make(200, "local:${flow.request.path}")
                }
                override fun response(flow: HttpFlow) {
                    assertEquals(ResponseSource.LOCAL, flow.responseSource)
                    responses.incrementAndGet()
                    flow.response!!.headers["X-Hook"] = "called"
                }
            }
        }.build()
        Harness(config, StreamConnector { attempts.incrementAndGet(); error("Local response opened a socket") }).use { harness ->
            val client = harness.connect()
            val bytes = "GET /one HTTP/1.1\r\nHost: example.com\r\n\r\nGET /two HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
            client.sendAt(0, bytes.copyOfRange(0, 11))
            client.sendAt(11, bytes.copyOfRange(11, bytes.size)); client.position += bytes.size
            client.finish()
            client.readUntilFinished()
            val wire = client.body.toString("UTF-8")
            assertTrue(wire.contains("local:/oneHTTP/1.1 200 OK"))
            assertTrue(wire.endsWith("local:/two"))
            assertEquals(2, requests.get()); assertEquals(2, responses.get()); assertEquals(0, attempts.get())
        }
    }

    @Test fun httpRewritesRequestAndLargeResponseAcrossRealUpstreamSocket() {
        ServerSocket(0, 50, loopback).use { server ->
            server.soTimeout = 5000
            val config = DyltunConfig.Builder().apply {
                hooks = object : HttpHooks {
                    override fun request(flow: HttpFlow) { flow.request.path = "/rewritten"; flow.request.text = "new upload" }
                    override fun response(flow: HttpFlow) {
                        assertEquals(ResponseSource.UPSTREAM, flow.responseSource)
                        assertEquals(200000, flow.response!!.content!!.size)
                        flow.response!!.text = "changed:" + flow.response!!.text
                    }
                }
                routing { rule(object : ConnectionRule {
                    override fun evaluate(context: RoutingContext) = RoutingDecision.Redirect(Endpoint("127.0.0.1", server.localPort))
                }) }
            }.build()
            Harness(config, StreamConnector(::channel)).use { harness ->
                val client = harness.connect()
                val bytes = "POST / HTTP/1.1\r\nHost: example.com\r\nContent-Length: 3\r\n\r\nold".toByteArray()
                client.sendAt(0, bytes); client.position += bytes.size; client.finish()
                server.accept().use { upstream ->
                    upstream.soTimeout = 5000
                    val socketInput = upstream.getInputStream()
                    runBlocking {
                        val input = HttpInput(ByteSource {
                            val buffer = ByteArray(16384)
                            val count = socketInput.read(buffer)
                            if (count < 0) null else buffer.copyOf(count)
                        })
                        val head = HttpFraming.request(input)!!
                        assertEquals("POST", head.method)
                        assertEquals("/rewritten", head.target)
                        assertEquals("10", head.headers["Content-Length"])
                        val body = BodyReader(input, head.framing)
                        val bytes = ByteArrayOutputStream()
                        while (true) bytes.write(body.read() ?: break)
                        assertEquals("new upload", bytes.toString("UTF-8"))
                    }
                    upstream.soTimeout = 100
                    assertThrows("The HTTP upstream must remain open while awaiting its response",
                        java.net.SocketTimeoutException::class.java) { socketInput.read() }
                    upstream.soTimeout = 5000
                    val worker = Executors.newSingleThreadExecutor()
                    try {
                        val sent = worker.submit {
                            upstream.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 200000\r\n\r\n" + "a".repeat(200000)).toByteArray())
                            upstream.shutdownOutput()
                        }
                        client.readUntilFinished()
                        sent.get(5, TimeUnit.SECONDS)
                        val response = client.body.toString("UTF-8")
                        assertTrue(response.contains("Content-Length: 200008\r\n"))
                        assertTrue(response.endsWith("changed:" + "a".repeat(200000)))
                    } finally { worker.shutdownNow() }
                }
            }
        }
    }

    @Test fun stopDuringHookClosesNetworkAndDiscardsLateLocalResponse() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val attempts = AtomicInteger()
        val config = DyltunConfig.Builder().apply {
            hooks = object : HttpHooks {
                override fun request(flow: HttpFlow) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    flow.response = HttpResponse.make(content = "too late")
                }
            }
        }.build()
        Harness(config, StreamConnector { attempts.incrementAndGet(); error("Stopped Hook escaped") }).use { harness ->
            try {
                val client = harness.connect()
                client.sendAt(0, "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val started = System.nanoTime()
                harness.session.close()
                assertTrue(System.nanoTime() - started < 500_000_000)
                assertEquals(DyltunState.Stopping, harness.session.state.value)
            } finally { release.countDown() }
            runBlocking { withTimeout(3000) { harness.session.awaitClosed() } }
            assertEquals(0, attempts.get())
            assertTrue(harness.device.outgoing.none { Ipv4Packet.parse(it).tcp().payloadLength > 0 })
        }
    }

    @Test fun httpBodyRejectionReachesClientAs413AndFinInsteadOfReset() {
        val attempts = AtomicInteger()
        val requests = AtomicInteger()
        val config = DyltunConfig.Builder().apply {
            hooks = object : HttpHooks { override fun request(flow: HttpFlow) { requests.incrementAndGet() } }
            bodyLimits = BodyLimits(request = BodyLimit(8, 8, overflow = BodyOverflow.REJECT))
        }.build()
        Harness(config, StreamConnector { attempts.incrementAndGet(); error("Rejected body reached upstream") }).use { harness ->
            val client = harness.connect()
            val bytes = "POST / HTTP/1.1\r\nHost: example.com\r\nContent-Length: 9\r\n\r\n123456789".toByteArray()
            client.sendAt(0, bytes); client.position += bytes.size; client.finish()
            client.readUntilFinished()
            assertTrue(client.body.toString("UTF-8").startsWith("HTTP/1.1 413 "))
            assertEquals(0, attempts.get()); assertEquals(0, requests.get())
            assertEquals(DyltunState.Running, harness.session.state.value)
            assertTrue(harness.session.events.replayCache.any { it is DyltunEvent.ConnectionError })
        }
    }

    private fun redirect(port: Int) = DyltunConfig.Builder().apply {
        routing { rule(object : ConnectionRule {
            override fun evaluate(context: RoutingContext) = RoutingDecision.Redirect(Endpoint("127.0.0.1", port))
        }) }
    }.build()

    private fun channel(endpoint: Endpoint): SocketChannel = SocketChannel.open().apply {
        configureBlocking(false); connect(InetSocketAddress(endpoint.host, endpoint.port))
    }

    private class Device : PacketDevice {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val outgoing = LinkedBlockingQueue<ByteArray>()
        @Volatile var drop: (TcpPacket) -> Boolean = { false }
        override fun read(buffer: ByteArray): Int {
            val bytes = incoming.poll(50, TimeUnit.MILLISECONDS) ?: return 0
            bytes.copyInto(buffer)
            return bytes.size
        }
        override fun write(packet: ByteArray) { if (!drop(Ipv4Packet.parse(packet).tcp())) outgoing.offer(packet.copyOf()) }
        override fun wakeup() { incoming.offer(byteArrayOf()) }
        override fun close() { wakeup() }
    }

    private class Harness(config: DyltunConfig, connector: StreamConnector) : Closeable {
        val device = Device()
        private val clients = mutableMapOf<Int, Client>()
        val session = ManagedSession(Closeable {}) { emit ->
            IpPacketRuntime(device, config, DatagramConnector { error("Unexpected UDP") }, emit, connector)
        }.also { it.start() }

        fun connect(destination: Int = 0xc6336409.toInt()): Client {
            val client = Client(this, destination)
            clients[destination] = client
            client.transmit(1000, 0, SYN)
            until { client.established }
            return client
        }

        fun until(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!condition()) {
                check(System.nanoTime() < deadline) { "TCP test timed out; state=${session.state.value}, events=${session.events.replayCache}" }
                val bytes = device.outgoing.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val packet = Ipv4Packet.parse(bytes)
                clients[packet.source]?.receive(packet.tcp()) ?: error("Reply has the wrong logical tuple")
            }
        }
        override fun close() {
            session.close(); runBlocking { withTimeout(3000) { session.awaitClosed() } }
            assertEquals(DyltunState.Stopped, session.state.value)
        }
    }

    private class Client(private val harness: Harness, val destination: Int) {
        var established = false
        var reset = false
        var finished = false
        var position = 1001L
        private var acknowledgement = 0L
        private val pending = TreeMap<Long, TcpPacket>()
        val body = ByteArrayOutputStream()

        fun transmit(seq: Long, ack: Long, flags: Int, bytes: ByteArray = byteArrayOf()) {
            harness.device.incoming.offer(PacketWriter.tcp(0x0a000002, destination, 12345, 80,
                wireSequence(seq), wireSequence(ack), flags, 65535, bytes, mss = if (flags and SYN != 0) 1200 else null))
        }
        fun sendAt(offset: Int, bytes: ByteArray) = transmit(position + offset, acknowledgement, ACK, bytes)
        fun finish() { transmit(position, acknowledgement, FIN or ACK); position++ }
        fun readUntilFinished() { harness.until { finished || reset }; assertFalse("Unexpected RST", reset) }

        fun receive(packet: TcpPacket) {
            assertEquals(12345, packet.destinationPort)
            assertEquals(0x0a000002, packet.ip.destination)
            if (packet.flags and RST != 0) { reset = true; return }
            if (packet.flags and SYN != 0) {
                if (!established) { acknowledgement = packet.sequence + 1; established = true }
                transmit(position, acknowledgement, ACK)
                return
            }
            if (packet.payloadLength == 0 && packet.flags and FIN == 0) return
            val sequence = unwrapSequence(packet.sequence, acknowledgement)
            if (sequence >= acknowledgement) pending[sequence] = packet
            while (true) {
                val next = pending.remove(acknowledgement) ?: break
                body.write(next.ip.bytes, next.payloadOffset, next.payloadLength)
                acknowledgement += next.payloadLength
                if (next.flags and FIN != 0) { acknowledgement++; finished = true }
            }
            transmit(position, acknowledgement, ACK)
        }
    }

    private fun clientHello(host: String): ByteArray {
        fun wire(block: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { DataOutputStream(it).apply(block) }.toByteArray()
        val name = host.toByteArray()
        val body = wire {
            writeShort(0x0303); write(ByteArray(32)); writeByte(0); writeShort(2); writeShort(0x1301); writeByte(1); writeByte(0)
            writeShort(name.size + 9); writeShort(0); writeShort(name.size + 5); writeShort(name.size + 3)
            writeByte(0); writeShort(name.size); write(name)
        }
        val handshake = wire { writeByte(1); writeByte(0); writeShort(body.size); write(body) }
        fun record(bytes: ByteArray) = wire { writeByte(22); writeShort(0x0303); writeShort(bytes.size); write(bytes) }
        return record(handshake.copyOfRange(0, 19)) + record(handshake.copyOfRange(19, handshake.size))
    }
}
