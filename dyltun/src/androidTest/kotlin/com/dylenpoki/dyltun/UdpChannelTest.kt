package com.dylenpoki.dyltun

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenpoki.dyltun.internal.android.DatagramConnector
import com.dylenpoki.dyltun.internal.transport.UdpForwarder
import com.dylenpoki.dyltun.internal.transport.UdpReply
import com.dylenpoki.dyltun.internal.transport.UdpRequest
import com.dylenpoki.dyltun.internal.transport.UdpTuple
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UdpChannelTest {
    @Test fun androidChannelsForwardEmptyDatagramsAndFilterUnexpectedPeers() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        DatagramSocket(0, loopback).use { server ->
            server.soTimeout = 2000
            val replies = LinkedBlockingQueue<UdpReply>()
            val errors = LinkedBlockingQueue<Any>()
            val connector = DatagramConnector { endpoint ->
                DatagramChannel.open().apply { connect(InetSocketAddress(endpoint.host, endpoint.port)) }
            }
            val forwarder = UdpForwarder(connector, { replies.offer(it) }, { errors.offer(it) }, { errors.offer(it) })
            val tuple = UdpTuple(0x0a000002, 0x7f000001, 12345, server.localPort)
            val info = ConnectionInfo(1, TransportProtocol.UDP, loopback, 12345, loopback, server.localPort)
            forwarder.start()
            try {
                forwarder.send(UdpRequest(tuple, info, Endpoint("127.0.0.1", server.localPort), byteArrayOf()))
                val packet = DatagramPacket(ByteArray(1), 1)
                server.receive(packet)
                assertEquals(0, packet.length)
                DatagramSocket(0, loopback).use { stranger ->
                    stranger.send(DatagramPacket(byteArrayOf(99), 1, packet.socketAddress))
                }
                server.send(DatagramPacket(byteArrayOf(), 0, packet.socketAddress))
                val reply = replies.poll(2, TimeUnit.SECONDS) ?: error("Empty reply was lost")
                assertEquals(tuple, reply.tuple)
                assertEquals(0, reply.payload.size)
                server.send(DatagramPacket(byteArrayOf(1, 2), 2, packet.socketAddress))
                assertArrayEquals(byteArrayOf(1, 2), replies.poll(2, TimeUnit.SECONDS)!!.payload)
                assertTrue(errors.isEmpty())
            } finally { forwarder.close() }
        }
    }
}
