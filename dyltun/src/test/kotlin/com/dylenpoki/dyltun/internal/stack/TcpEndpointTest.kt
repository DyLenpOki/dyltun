package com.dylenpoki.dyltun.internal.stack

import com.dylenpoki.dyltun.internal.packet.Ipv4Packet
import com.dylenpoki.dyltun.internal.packet.PacketWriter
import com.dylenpoki.dyltun.internal.packet.TcpPacket
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint.Companion.ACK
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint.Companion.FIN
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint.Companion.RST
import com.dylenpoki.dyltun.internal.stack.TcpEndpoint.Companion.SYN
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpEndpointTest {
    private class Clock(var value: Long = 0) : MonotonicClock { override fun now() = value }
    private class Fixture(val client: Long = 100, val server: Long = 900, mss: Int = 1460, window: Int = 65535) {
        val clock = Clock()
        val output = mutableListOf<TcpOutput>()
        val releases = mutableListOf<Int>()
        val closes = mutableListOf<Throwable?>()
        val tcp = TcpEndpoint(packet(client, 0, SYN, window = window, mss = mss), server, clock,
            { output += it }, { releases += it }, { closes += it })
        init { tcp.start() }
        fun establish(window: Int = 65535) { tcp.accept(packet(client + 1, server + 1, ACK, window = window)); output.clear() }
        fun accept(seq: Long, ack: Long = server + 1, flags: Int = ACK, content: String = "", window: Int = 65535) {
            tcp.accept(packet(seq, ack, flags, content.toByteArray(), window))
        }
        fun advance(seconds: Long) { clock.value += seconds * 1_000_000_000; tcp.tick() }
    }

    @Test fun handshakeValidatesAckAndAdvertisesMssWithoutScaling() {
        val f = Fixture(mss = 1200)
        assertEquals(SYN or ACK, f.output.single().flags)
        assertEquals(101L, f.output.single().acknowledgement)
        assertEquals(1460, f.output.single().mss)
        f.accept(101, ack = 900)
        assertFalse(f.tcp.handshakeComplete)
        f.accept(101, ack = 9999, content = "ignored")
        assertFalse(f.tcp.handshakeComplete)
        f.establish()
        assertEquals(TcpState.ESTABLISHED, f.tcp.state)
        assertEquals(1200, f.tcp.mss)
    }

    @Test fun duplicateSynReplaysOriginalSynAckWithoutCreatingAnotherSequence() {
        val f = Fixture()
        val original = f.output.single()
        f.tcp.accept(packet(100, 0, SYN))
        assertEquals(original, f.output.last())
        f.establish()
        f.tcp.offerServer("hello".toByteArray())
        assertEquals(901L, f.output.single().sequence)
    }

    @Test fun handshakeCanCarryFirstApplicationBytes() {
        val f = Fixture()
        f.accept(101, content = "first")
        assertTrue(f.tcp.handshakeComplete)
        assertArrayEquals("first".toByteArray(), f.tcp.peekClient(100))
        assertEquals(106L, f.output.last().acknowledgement)
    }

    @Test fun reorderedOverlappingAndDuplicateBytesAreDeliveredExactlyOnce() {
        val f = Fixture(); f.establish()
        f.accept(104, content = "def")
        assertEquals(0, f.tcp.clientAvailable)
        assertEquals(101L, f.output.last().acknowledgement)
        f.accept(103, content = "cde")
        f.accept(101, content = "ab")
        f.accept(101, content = "abcdef")
        assertEquals(107L, f.output.last().acknowledgement)
        assertArrayEquals("abcdef".toByteArray(), f.tcp.peekClient(100))
        f.tcp.commitClient(6)
        assertEquals(65529, f.tcp.receiveWindow)
        f.tcp.consumedClient(6)
        assertEquals(65535, f.tcp.receiveWindow)
    }

    @Test fun receiveWindowClosesUntilActualConsumerReleasesCredit() {
        val f = Fixture(); f.establish()
        f.tcp.accept(packet(101, 901, ACK, ByteArray(32768) { 1 }))
        f.tcp.accept(packet(32869, 901, ACK, ByteArray(32767) { 2 }))
        assertEquals(0, f.tcp.receiveWindow)
        f.tcp.commitClient(100)
        assertEquals(0, f.tcp.receiveWindow)
        f.accept(65636, content = "not accepted")
        assertEquals(65435, f.tcp.clientAvailable)
        f.tcp.consumedClient(100)
        assertEquals(100, f.tcp.receiveWindow)
        assertThrows(IllegalArgumentException::class.java) { f.tcp.consumedClient(1) }
    }

    @Test fun sequenceAndAckWrapAcrossUnsignedBoundary() {
        val f = Fixture(client = 0xffff_fffeL, server = 0xffff_fffeL); f.establish()
        f.accept(0xffff_ffffL, ack = 0xffff_ffffL, content = "abc")
        assertEquals(2L, f.output.last().acknowledgement)
        f.output.clear()
        f.tcp.offerServer("abcdef".toByteArray())
        assertEquals(0xffff_ffffL, f.output.single().sequence)
        f.accept(2, ack = 5)
        assertEquals(listOf(6), f.releases)
        assertEquals(65536, f.tcp.availableSendBytes)
    }

    @Test fun partialAckReleasesOnlyAcknowledgedBytesAndRetransmitsRemainder() {
        val f = Fixture(); f.establish()
        f.tcp.offerServer("abcdef".toByteArray())
        f.accept(101, ack = 904)
        assertEquals(listOf(3), f.releases)
        f.output.clear(); f.advance(1)
        assertEquals(904L, f.output.single().sequence)
        assertArrayEquals("def".toByteArray(), f.output.single().payload)
        f.accept(101, ack = 907)
        assertEquals(listOf(3, 3), f.releases)
    }

    @Test fun invalidFutureAckCannotReleaseBuffersOrDeliverItsPayload() {
        val f = Fixture(); f.establish()
        f.tcp.offerServer("abc".toByteArray())
        f.accept(101, ack = 9999, content = "invalid")
        assertTrue(f.releases.isEmpty())
        assertEquals(0, f.tcp.clientAvailable)
        assertEquals(65533, f.tcp.availableSendBytes)
    }

    @Test fun retransmissionUsesBackoffAndHandshakeTimeout() {
        val f = Fixture()
        f.output.clear(); f.advance(1)
        assertEquals(1, f.output.size)
        f.advance(1); assertEquals(1, f.output.size)
        f.advance(1); assertEquals(2, f.output.size)
        f.advance(300)
        assertEquals(TcpState.CLOSED, f.tcp.state)
        assertNotNull(f.closes.single())
    }

    @Test fun threeDuplicateAcksCauseFastRetransmission() {
        val f = Fixture(mss = 100); f.establish()
        f.tcp.offerServer(ByteArray(400) { it.toByte() })
        assertEquals(4, f.output.size)
        val first = f.output.first()
        repeat(3) { f.accept(101) }
        assertEquals(5, f.output.size)
        assertEquals(first.sequence, f.output.last().sequence)
        assertArrayEquals(first.payload, f.output.last().payload)
    }

    @Test fun sendBufferIsBoundedAndPeerWindowGatesNewTransmission() {
        val f = Fixture(); f.establish(window = 10)
        assertTrue(f.tcp.offerServer(ByteArray(65536)))
        assertEquals(10, f.output.sumOf { it.payload.size })
        assertFalse(f.tcp.offerServer(byteArrayOf(1)))
        f.accept(101, ack = 911, window = 20)
        assertEquals(30, f.output.sumOf { it.payload.size })
        assertEquals(10, f.tcp.availableSendBytes)
    }

    @Test fun zeroWindowProbesAreTimedAndReopeningDoesNotLoseBytes() {
        val f = Fixture(); f.establish(window = 0)
        f.tcp.offerServer("pending".toByteArray())
        assertTrue(f.output.isEmpty())
        f.tcp.tick(); f.advance(1)
        assertEquals(900L, f.output.single().sequence)
        assertArrayEquals("p".toByteArray(), f.output.single().payload)
        f.output.clear()
        f.accept(101, window = 65535)
        assertEquals(901L, f.output.single().sequence)
        assertArrayEquals("pending".toByteArray(), f.output.single().payload)
    }

    @Test fun clientHalfCloseRetainsTailUntilConsumedAndAllowsServerReply() {
        val f = Fixture(); f.establish()
        f.accept(101, flags = FIN or ACK, content = "tail")
        assertTrue(f.tcp.clientFinished)
        assertEquals(TcpState.CLOSE_WAIT, f.tcp.state)
        assertArrayEquals("tail".toByteArray(), f.tcp.peekClient(100))
        f.tcp.offerServer("reply".toByteArray()); f.tcp.finishServer()
        assertEquals(TcpState.LAST_ACK, f.tcp.state)
        f.accept(106, ack = 907)
        assertEquals(TcpState.CLOSED, f.tcp.state)
        assertTrue(f.closes.isEmpty())
        f.tcp.commitClient(4); f.tcp.consumedClient(4)
        assertEquals(listOf<Throwable?>(null), f.closes)
    }

    @Test fun serverHalfCloseAllowsFinalUploadBeforeTimeWaitCleanup() {
        val f = Fixture(); f.establish()
        f.tcp.finishServer()
        f.accept(101, ack = 902)
        assertEquals(TcpState.FIN_WAIT_2, f.tcp.state)
        f.accept(101, ack = 902, flags = FIN or ACK, content = "tail")
        assertEquals(TcpState.TIME_WAIT, f.tcp.state)
        assertArrayEquals("tail".toByteArray(), f.tcp.peekClient(100))
        assertTrue(f.closes.isEmpty())
        f.tcp.commitClient(4); f.tcp.consumedClient(4)
        assertEquals(listOf<Throwable?>(null), f.closes)
        f.advance(60)
        assertEquals(TcpState.CLOSED, f.tcp.state)
    }

    @Test fun outOfOrderFinWaitsForGapAndDoesNotDeliverBeyondFin() {
        val f = Fixture(); f.establish()
        f.accept(103, content = "cdefgh")
        f.accept(106, flags = FIN or ACK)
        assertFalse(f.tcp.clientFinished)
        f.accept(101, content = "ab")
        assertTrue(f.tcp.clientFinished)
        assertEquals(107L, f.output.last().acknowledgement)
        assertArrayEquals("abcde".toByteArray(), f.tcp.peekClient(100))
    }

    @Test fun simultaneousCloseAndDuplicateFinKeepTimeWaitAndIgnoreReset() {
        val f = Fixture(); f.establish()
        f.tcp.finishServer()
        f.accept(101, flags = FIN or ACK)
        assertEquals(TcpState.CLOSING, f.tcp.state)
        f.accept(102, ack = 902)
        assertEquals(TcpState.TIME_WAIT, f.tcp.state)
        f.advance(50)
        f.accept(101, ack = 902, flags = FIN or ACK)
        f.accept(102, ack = 902, flags = RST)
        f.advance(50)
        assertEquals(TcpState.TIME_WAIT, f.tcp.state)
        f.advance(10)
        assertEquals(TcpState.CLOSED, f.tcp.state)
    }

    @Test fun inWindowNonExactResetGetsChallengeAckAndExactResetCloses() {
        val f = Fixture(); f.establish()
        f.accept(102, flags = RST)
        assertEquals(TcpState.ESTABLISHED, f.tcp.state)
        assertEquals(ACK, f.output.single().flags)
        f.accept(101, flags = RST)
        assertEquals(TcpState.CLOSED, f.tcp.state)
        assertEquals(1, f.closes.size)
    }

    @Test fun byteRingWrapPreservesStreamAndCapacity() {
        val ring = ByteRing(5)
        ring.append("abcd".toByteArray()); ring.discard(3); ring.append("efgh".toByteArray())
        assertArrayEquals("defgh".toByteArray(), ring.peek(100))
        assertThrows(IllegalStateException::class.java) { ring.append(byteArrayOf(1)) }
        ring.clear(); ring.append(byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), ring.peek(1))
    }

    companion object {
        private fun packet(seq: Long, ack: Long, flags: Int, payload: ByteArray = byteArrayOf(), window: Int = 65535, mss: Int? = null): TcpPacket =
            Ipv4Packet.parse(PacketWriter.tcp(0x0a000002, 0xc6336409.toInt(), 12345, 80,
                wireSequence(seq), wireSequence(ack), flags, window, payload, mss = mss)).tcp()
    }
}
