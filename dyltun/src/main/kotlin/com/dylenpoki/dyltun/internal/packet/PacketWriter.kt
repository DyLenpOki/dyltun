package com.dylenpoki.dyltun.internal.packet

internal object PacketWriter {
    fun tcp(
        source: Int, destination: Int, sourcePort: Int, destinationPort: Int,
        sequence: Long, acknowledgement: Long, flags: Int, window: Int,
        payload: ByteArray = byteArrayOf(), id: Int = 0, mss: Int? = null
    ): ByteArray {
        require(window in 0..65535 && (mss == null || mss in 1..65535))
        val header = if (mss == null) 20 else 24
        require(payload.size <= 65535 - 20 - header)
        val packet = ip(source, destination, 6, header + payload.size, id)
        put16(packet, 20, sourcePort); put16(packet, 22, destinationPort)
        put32(packet, 24, sequence.toInt()); put32(packet, 28, acknowledgement.toInt())
        packet[32] = ((header / 4) shl 4).toByte()
        packet[33] = flags.toByte()
        put16(packet, 34, window)
        if (mss != null) { packet[40] = 2; packet[41] = 4; put16(packet, 42, mss) }
        payload.copyInto(packet, 20 + header)
        put16(packet, 36, transportChecksum(packet, 20, header + payload.size, 6))
        return packet
    }

    fun udp(source: Int, destination: Int, sourcePort: Int, destinationPort: Int, payload: ByteArray, id: Int): ByteArray {
        require(payload.size <= 65507)
        val packet = ip(source, destination, 17, payload.size + 8, id)
        put16(packet, 20, sourcePort)
        put16(packet, 22, destinationPort)
        put16(packet, 24, payload.size + 8)
        payload.copyInto(packet, 28)
        val value = transportChecksum(packet, 20, payload.size + 8, 17)
        put16(packet, 26, if (value == 0) 65535 else value)
        return packet
    }

    fun reset(request: TcpPacket, id: Int): ByteArray? {
        if (request.flags and 4 != 0) return null
        val result = ip(request.ip.destination, request.ip.source, 6, 20, id)
        put16(result, 20, request.destinationPort)
        put16(result, 22, request.sourcePort)
        result[32] = 0x50
        if (request.flags and 16 != 0) {
            put32(result, 24, request.acknowledgement.toInt())
            result[33] = 4
        } else {
            val consumed = request.payloadLength + (if (request.flags and 2 != 0) 1 else 0) +
                (if (request.flags and 1 != 0) 1 else 0)
            put32(result, 28, (request.sequence + consumed).toInt())
            result[33] = 0x14
        }
        put16(result, 36, transportChecksum(result, 20, 20, 6))
        return result
    }

    fun fragments(packet: ByteArray, mtu: Int): List<ByteArray> {
        require(mtu in 68..65535)
        if (packet.size <= mtu) return listOf(packet)
        require(packet[0].toInt() and 15 == 5)
        val chunk = ((mtu - 20) / 8) * 8
        val result = mutableListOf<ByteArray>()
        var offset = 20
        while (offset < packet.size) {
            val size = minOf(chunk, packet.size - offset)
            val fragment = ByteArray(20 + size)
            packet.copyInto(fragment, 0, 0, 20)
            packet.copyInto(fragment, 20, offset, offset + size)
            put16(fragment, 2, fragment.size)
            put16(fragment, 6, ((offset - 20) / 8) or if (offset + size < packet.size) 0x2000 else 0)
            put16(fragment, 10, 0)
            put16(fragment, 10, checksum(fragment, 0, 20))
            result += fragment
            offset += size
        }
        return result
    }

    private fun ip(source: Int, destination: Int, protocol: Int, payloadLength: Int, id: Int): ByteArray {
        val result = ByteArray(20 + payloadLength)
        result[0] = 0x45
        put16(result, 2, result.size)
        put16(result, 4, id)
        result[8] = 64
        result[9] = protocol.toByte()
        put32(result, 12, source)
        put32(result, 16, destination)
        put16(result, 10, checksum(result, 0, 20))
        return result
    }
}
