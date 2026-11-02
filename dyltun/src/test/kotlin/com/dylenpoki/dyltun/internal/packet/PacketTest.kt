package com.dylenpoki.dyltun.internal.packet

import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTest {
    private val source = 0x0a000002
    private val target = 0xc6336409.toInt()

    @Test fun rfc1071ChecksumVector() {
        assertEquals(0x220d, checksum(byteArrayOf(0, 1, 0xf2.toByte(), 3, 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte()), 0, 8))
    }

    @Test fun udpPreservesTupleAndOddLengthPayload() {
        val bytes = PacketWriter.udp(source, target, 12345, 53, "hello".toByteArray(), 42)
        val packet = Ipv4Packet.parse(bytes)
        val udp = packet.udp()
        assertEquals(source, packet.source)
        assertEquals(target, packet.destination)
        assertEquals(12345, udp.sourcePort)
        assertEquals(53, udp.destinationPort)
        assertArrayEquals("hello".toByteArray(), bytes.copyOfRange(udp.payloadOffset, packet.length))
        assertEquals(0, checksum(bytes, 0, 20))
        assertEquals(0, transportChecksum(bytes, 20, bytes.size - 20, 17))
    }

    @Test fun malformedLengthsAndChecksumAreRejected() {
        val bytes = PacketWriter.udp(source, target, 1, 2, byteArrayOf(7), 1)
        assertThrows(PacketException::class.java) { Ipv4Packet.parse(bytes, bytes.size - 1) }
        val corrupt = bytes.copyOf().also { it[it.lastIndex] = 8 }
        assertThrows(PacketException::class.java) { Ipv4Packet.parse(corrupt).udp() }
        val badHeader = bytes.copyOf().also { it[8] = 0 }
        assertThrows(PacketException::class.java) { Ipv4Packet.parse(badHeader) }
        val omittedUdpChecksum = bytes.copyOf().also { put16(it, 26, 0) }
        Ipv4Packet.parse(omittedUdpChecksum).udp()
    }

    @Test fun fragmentReassemblyAcceptsReverseOrderAndChecksTransportChecksum() {
        val bytes = PacketWriter.udp(source, target, 12345, 53, ByteArray(4097) { it.toByte() }, 9)
        val pieces = PacketWriter.fragments(bytes, 1280)
        assertEquals(4, pieces.size)
        assertTrue(pieces.all { it.size <= 1280 && checksum(it, 0, 20) == 0 })
        val budget = MemoryBudget(16384)
        Ipv4Reassembler(budget).use { assembler ->
            var complete: Ipv4Packet? = null
            pieces.reversed().forEach { complete = assembler.accept(Ipv4Packet.parse(it), 0) ?: complete }
            assertArrayEquals(bytes, complete!!.bytes)
            complete!!.udp()
            assertEquals(0, budget.used)
        }
    }

    @Test fun overlappingFragmentsPoisonDatagramAndReleaseMemory() {
        val bytes = PacketWriter.udp(source, target, 1, 2, ByteArray(500), 9)
        val parts = PacketWriter.fragments(bytes, 200)
        val budget = MemoryBudget(4096)
        Ipv4Reassembler(budget, timeoutNanos = 100).use { assembler ->
            val first = Ipv4Packet.parse(parts[0])
            assertNull(assembler.accept(first, 0))
            assertTrue(budget.used > 0)
            assertThrows(PacketException::class.java) { assembler.accept(first, 1) }
            assertEquals(0, budget.used)
            assertThrows(PacketException::class.java) { assembler.accept(Ipv4Packet.parse(parts[1]), 2) }
            assertNull(assembler.accept(first, 100))
        }
        assertEquals(0, budget.used)
    }

    @Test fun fragmentsExpireAndCannotExceedBudgetOrCount() {
        val budget = MemoryBudget(200)
        val first = Ipv4Packet.parse(PacketWriter.fragments(PacketWriter.udp(source, target, 1, 2, ByteArray(500), 1), 200)[0])
        Ipv4Reassembler(budget, 1, 100).use { assembler ->
            assertNull(assembler.accept(first, 0))
            val other = Ipv4Packet.parse(PacketWriter.fragments(PacketWriter.udp(source, target, 1, 2, ByteArray(500), 2), 200)[0])
            assertThrows(PacketException::class.java) { assembler.accept(other, 1) }
            assembler.expire(100)
            assertEquals(0, budget.used)
            assertNull(assembler.accept(other, 101))
        }
        assertEquals(0, budget.used)
    }

    @Test fun resetAcknowledgesSynAndWrapsSequenceNumber() {
        val request = tcp(sequence = 0xffff_ffffL, acknowledgement = 0, flags = 2)
        val rst = Ipv4Packet.parse(PacketWriter.reset(request, 1)!!).tcp()
        assertEquals(0x14, rst.flags)
        assertEquals(0, rst.acknowledgement)
        assertEquals(request.sourcePort, rst.destinationPort)
        assertEquals(request.ip.source, rst.ip.destination)
    }

    @Test fun resetOfAcknowledgedPacketUsesAckAndNeverAnswersReset() {
        val rst = Ipv4Packet.parse(PacketWriter.reset(tcp(100, 123456, 0x10), 1)!!).tcp()
        assertEquals(4, rst.flags)
        assertEquals(123456, rst.sequence)
        assertEquals(0, rst.acknowledgement)
        assertNull(PacketWriter.reset(tcp(100, 0, 4), 1))
    }

    private fun tcp(sequence: Long, acknowledgement: Long, flags: Int): TcpPacket {
        val bytes = ByteArray(40)
        bytes[0] = 0x45; put16(bytes, 2, 40); bytes[8] = 64; bytes[9] = 6
        put32(bytes, 12, source); put32(bytes, 16, target)
        put16(bytes, 20, 12345); put16(bytes, 22, 80)
        put32(bytes, 24, sequence.toInt()); put32(bytes, 28, acknowledgement.toInt())
        bytes[32] = 0x50; bytes[33] = flags.toByte()
        put16(bytes, 10, checksum(bytes, 0, 20)); put16(bytes, 36, transportChecksum(bytes, 20, 20, 6))
        return Ipv4Packet.parse(bytes).tcp()
    }
}
