package com.dylenpoki.dyltun.internal.packet

import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import java.io.Closeable
import java.util.TreeMap

/** Single-owner fragment table. Overlap poisons the whole datagram until its original deadline. */
internal class Ipv4Reassembler(
    private val budget: MemoryBudget = MemoryBudget(4L * 1024 * 1024),
    private val maxDatagrams: Int = 64,
    private val timeoutNanos: Long = 15_000_000_000L
) : Closeable {
    private data class Key(val source: Int, val destination: Int, val protocol: Int, val id: Int)
    private class Part(val bytes: ByteArray, val lease: MemoryBudget.Lease)
    private class Datagram(val created: Long) {
        var header: ByteArray? = null
        var length: Int? = null
        val parts = TreeMap<Int, Part>()
    }
    private val pending = linkedMapOf<Key, Datagram>()
    private val rejected = linkedMapOf<Key, Long>()

    init { require(maxDatagrams > 0 && timeoutNanos > 0) }

    fun accept(packet: Ipv4Packet, now: Long): Ipv4Packet? {
        expire(now)
        if (!packet.fragmented) return packet
        val key = Key(packet.source, packet.destination, packet.protocol, u16(packet.bytes, 4))
        checkPacket(key !in rejected, "Rejected fragment datagram")
        val datagram = pending[key] ?: run {
            checkPacket(pending.size < maxDatagrams, "IPv4 fragment table is full")
            Datagram(now).also { pending[key] = it }
        }
        try {
            val flags = u16(packet.bytes, 6)
            val offset = (flags and 0x1fff) * 8
            val size = packet.length - packet.headerLength
            val end = offset + size
            val more = flags and 0x2000 != 0
            checkPacket(size > 0 && (!more || size % 8 == 0), "Invalid fragment length")
            checkPacket(end <= 65515, "Reassembled packet is too large")
            checkPacket(datagram.parts.size < 256, "Too many IPv4 fragments")
            checkPacket(datagram.parts.floorEntry(offset)?.let { it.key + it.value.bytes.size > offset } != true &&
                datagram.parts.ceilingKey(offset)?.let { it < end } != true, "Overlapping IPv4 fragments")
            val knownEnd = datagram.length
            checkPacket(knownEnd == null || end <= knownEnd && (more || end == knownEnd), "Conflicting fragment end")
            if (!more) {
                checkPacket(datagram.parts.lastEntry()?.let { it.key + it.value.bytes.size <= end } != false,
                    "Fragment past datagram end")
                datagram.length = end
            }
            if (offset == 0) datagram.header = packet.bytes.copyOfRange(0, packet.headerLength)
            val lease = budget.acquire(size.toLong()) ?: throw PacketException("IPv4 fragment budget exhausted")
            try { datagram.parts[offset] = Part(packet.bytes.copyOfRange(packet.headerLength, packet.length), lease) }
            catch (error: Throwable) { lease.close(); throw error }

            val header = datagram.header ?: return null
            val total = datagram.length ?: return null
            checkPacket(total + header.size <= 65535, "Reassembled packet is too large")
            var next = 0
            for ((start, part) in datagram.parts) {
                if (start != next) return null
                next += part.bytes.size
            }
            if (next != total) return null
            // At most one transient 64 KiB assembly buffer; charge it before allocation as well.
            val outputLease = budget.acquire((header.size + total).toLong())
                ?: throw PacketException("IPv4 assembly budget exhausted")
            val result = try {
                ByteArray(header.size + total).also { bytes ->
                    header.copyInto(bytes)
                    datagram.parts.forEach { (start, part) -> part.bytes.copyInto(bytes, header.size + start) }
                    put16(bytes, 2, bytes.size)
                    put16(bytes, 6, 0)
                    put16(bytes, 10, 0)
                    put16(bytes, 10, checksum(bytes, 0, header.size))
                }
            } finally { outputLease.close() }
            remove(key)
            return Ipv4Packet.parse(result)
        } catch (error: PacketException) {
            remove(key)
            if (rejected.size >= maxDatagrams * 2) rejected.remove(rejected.keys.first())
            rejected[key] = datagram.created
            throw error
        }
    }

    fun expire(now: Long) {
        pending.filterValues { now - it.created >= timeoutNanos }.keys.toList().forEach(::remove)
        rejected.entries.removeAll { now - it.value >= timeoutNanos }
    }

    private fun remove(key: Key) { pending.remove(key)?.parts?.values?.forEach { it.lease.close() } }

    override fun close() {
        pending.keys.toList().forEach(::remove)
        rejected.clear()
    }
}
