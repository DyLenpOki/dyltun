package com.dylenpoki.dyltun.internal.packet

internal class PacketException(message: String) : IllegalArgumentException(message)

/** A validated view. The caller owns the bytes and must not reuse them while this view is retained. */
internal class Ipv4Packet private constructor(
    val bytes: ByteArray,
    val length: Int,
    val headerLength: Int
) {
    val protocol: Int get() = bytes[9].toInt() and 255
    val source: Int get() = i32(bytes, 12)
    val destination: Int get() = i32(bytes, 16)
    val fragmented: Boolean get() = u16(bytes, 6) and 0x3fff != 0

    fun udp(): UdpPacket {
        checkPacket(protocol == 17 && !fragmented, "Not an unfragmented UDP packet")
        val size = length - headerLength
        checkPacket(size >= 8 && u16(bytes, headerLength + 4) == size, "Invalid UDP length")
        val checksum = u16(bytes, headerLength + 6)
        checkPacket(checksum == 0 || transportChecksum(bytes, headerLength, size, 17) == 0, "Invalid UDP checksum")
        return UdpPacket(this, u16(bytes, headerLength), u16(bytes, headerLength + 2))
    }

    fun tcp(): TcpPacket {
        checkPacket(protocol == 6 && !fragmented, "Not an unfragmented TCP packet")
        val size = length - headerLength
        checkPacket(size >= 20, "Truncated TCP header")
        val tcpHeader = ((bytes[headerLength + 12].toInt() ushr 4) and 15) * 4
        checkPacket(tcpHeader in 20..size, "Invalid TCP data offset")
        checkPacket(transportChecksum(bytes, headerLength, size, 6) == 0, "Invalid TCP checksum")
        var option = headerLength + 20
        while (option < headerLength + tcpHeader) {
            when (bytes[option].toInt() and 255) {
                0 -> break
                1 -> option++
                else -> {
                    checkPacket(option + 1 < headerLength + tcpHeader, "Truncated TCP option")
                    val optionLength = bytes[option + 1].toInt() and 255
                    checkPacket(optionLength >= 2 && option + optionLength <= headerLength + tcpHeader, "Invalid TCP option")
                    option += optionLength
                }
            }
        }
        return TcpPacket(this, tcpHeader)
    }

    companion object {
        fun parse(bytes: ByteArray, length: Int = bytes.size): Ipv4Packet {
            checkPacket(length in 20..bytes.size, "Truncated IP packet")
            checkPacket((bytes[0].toInt() ushr 4) and 15 == 4, "Unsupported IP version")
            val ihl = (bytes[0].toInt() and 15) * 4
            checkPacket(ihl in 20..length && u16(bytes, 2) == length, "Invalid IPv4 length")
            checkPacket(checksum(bytes, 0, ihl) == 0, "Invalid IPv4 checksum")
            var option = 20
            while (option < ihl) {
                when (bytes[option].toInt() and 255) {
                    0 -> break
                    1 -> option++
                    else -> {
                        checkPacket(option + 1 < ihl, "Truncated IPv4 option")
                        val size = bytes[option + 1].toInt() and 255
                        checkPacket(size >= 2 && option + size <= ihl, "Invalid IPv4 option")
                        option += size
                    }
                }
            }
            val flags = u16(bytes, 6)
            checkPacket(flags and 0x8000 == 0 && !(flags and 0x4000 != 0 && flags and 0x3fff != 0), "Invalid IPv4 flags")
            return Ipv4Packet(bytes, length, ihl)
        }
    }
}

internal data class UdpPacket(val ip: Ipv4Packet, val sourcePort: Int, val destinationPort: Int) {
    val payloadOffset: Int get() = ip.headerLength + 8
    val payloadLength: Int get() = ip.length - payloadOffset
}

internal data class TcpPacket(val ip: Ipv4Packet, val headerLength: Int) {
    val sourcePort: Int get() = u16(ip.bytes, ip.headerLength)
    val destinationPort: Int get() = u16(ip.bytes, ip.headerLength + 2)
    val sequence: Long get() = i32(ip.bytes, ip.headerLength + 4).toLong() and 0xffff_ffffL
    val acknowledgement: Long get() = i32(ip.bytes, ip.headerLength + 8).toLong() and 0xffff_ffffL
    val flags: Int get() = ip.bytes[ip.headerLength + 13].toInt() and 255
    val window: Int get() = u16(ip.bytes, ip.headerLength + 14)
    val payloadOffset: Int get() = ip.headerLength + headerLength
    val payloadLength: Int get() = ip.length - ip.headerLength - headerLength

    fun maximumSegmentSize(): Int? {
        var offset = ip.headerLength + 20
        while (offset < payloadOffset) {
            when (ip.bytes[offset].toInt() and 255) {
                0 -> return null
                1 -> offset++
                2 -> {
                    checkPacket(ip.bytes[offset + 1].toInt() == 4, "Invalid TCP MSS option")
                    return u16(ip.bytes, offset + 2).also { checkPacket(it > 0, "Invalid TCP MSS") }
                }
                else -> offset += ip.bytes[offset + 1].toInt() and 255
            }
        }
        return null
    }
}

internal fun checkPacket(condition: Boolean, message: String) {
    if (!condition) throw PacketException(message)
}

internal fun u16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

internal fun i32(bytes: ByteArray, offset: Int): Int =
    (u16(bytes, offset) shl 16) or u16(bytes, offset + 2)

internal fun put16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 8).toByte()
    bytes[offset + 1] = value.toByte()
}

internal fun put32(bytes: ByteArray, offset: Int, value: Int) {
    put16(bytes, offset, value ushr 16)
    put16(bytes, offset + 2, value)
}

internal fun checksum(bytes: ByteArray, offset: Int, length: Int, initial: Long = 0): Int {
    var sum = initial
    var index = offset
    val end = offset + length
    while (index + 1 < end) {
        sum += u16(bytes, index)
        index += 2
    }
    if (index < end) sum += (bytes[index].toInt() and 255) shl 8
    while (sum ushr 16 != 0L) sum = (sum and 65535) + (sum ushr 16)
    return sum.toInt().inv() and 65535
}

internal fun transportChecksum(bytes: ByteArray, offset: Int, length: Int, protocol: Int): Int {
    val pseudo = u16(bytes, 12).toLong() + u16(bytes, 14) + u16(bytes, 16) + u16(bytes, 18) + protocol + length
    return checksum(bytes, offset, length, pseudo)
}
