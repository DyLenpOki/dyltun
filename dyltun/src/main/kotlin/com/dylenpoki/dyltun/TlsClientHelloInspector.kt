package com.dylenpoki.dyltun

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

data class TlsClientHello(val serverName: String?) : ProtocolMetadata {
    override val protocolId: ProtocolId get() = ProtocolId.TLS
}

/** Reads only bounded plaintext ClientHello metadata; it does not decrypt TLS. */
class TlsClientHelloInspector : ProtocolInspector {
    override val protocolId: ProtocolId get() = ProtocolId.TLS

    override fun inspect(context: InspectionContext, prefix: ByteBuffer): InspectionResult {
        val input = prefix.asReadOnlyBuffer()
        if (!input.hasRemaining()) return InspectionResult.NeedMore
        if (input.get(input.position()).toInt() and 255 != 22) return InspectionResult.NoMatch
        val handshake = ByteArrayOutputStream(minOf(input.remaining(), MAX_HELLO))
        while (input.hasRemaining()) {
            if (input.remaining() < 5) return InspectionResult.NeedMore
            if (input.u8() != 22 || input.u8() != 3) return InspectionResult.NoMatch
            input.u8()
            val recordSize = input.u16()
            if (recordSize > MAX_RECORD || handshake.size() + recordSize > MAX_HELLO) return InspectionResult.NoMatch
            if (input.remaining() < recordSize) return InspectionResult.NeedMore
            repeat(recordSize) { handshake.write(input.u8()) }
            val bytes = handshake.toByteArray()
            if (bytes.isNotEmpty() && bytes[0].toInt() != 1) return InspectionResult.NoMatch
            if (bytes.size < 4) continue
            val length = ((bytes[1].toInt() and 255) shl 16) or
                ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
            if (length > MAX_HELLO - 4) return InspectionResult.NoMatch
            if (bytes.size < length + 4) continue
            return parseHello(ByteBuffer.wrap(bytes, 4, length).slice())
        }
        return InspectionResult.NeedMore
    }

    private fun parseHello(body: ByteBuffer): InspectionResult = try {
        body.skip(34)
        body.skip(body.u8())
        val ciphers = body.u16()
        require(ciphers >= 2 && ciphers % 2 == 0)
        body.skip(ciphers)
        val compression = body.u8()
        require(compression > 0)
        body.skip(compression)
        if (!body.hasRemaining()) {
            InspectionResult.Matched(TlsClientHello(null))
        } else {
            val extensions = body.take(body.u16())
            require(!body.hasRemaining())
            var serverName: String? = null
            var seenSni = false
            while (extensions.hasRemaining()) {
                val type = extensions.u16()
                val value = extensions.take(extensions.u16())
                if (type == 0) {
                    require(!seenSni)
                    seenSni = true
                    val names = value.take(value.u16())
                    require(!value.hasRemaining())
                    while (names.hasRemaining()) {
                        val nameType = names.u8()
                        val name = names.take(names.u16())
                        if (nameType == 0) {
                            require(serverName == null && name.hasRemaining())
                            val bytes = ByteArray(name.remaining()).also(name::get)
                            require(bytes.all { (it.toInt() and 255) in 33..126 })
                            serverName = canonicalDomain(String(bytes, Charsets.US_ASCII))
                        }
                    }
                }
            }
            InspectionResult.Matched(TlsClientHello(serverName))
        }
    } catch (_: IllegalArgumentException) {
        InspectionResult.NoMatch
    } catch (_: java.nio.BufferUnderflowException) {
        InspectionResult.NoMatch
    }

    private companion object {
        const val MAX_HELLO = 65_536
        const val MAX_RECORD = 18_432
    }
}

internal fun ByteBuffer.u8(): Int = get().toInt() and 255
internal fun ByteBuffer.u16(): Int = (u8() shl 8) or u8()
internal fun ByteBuffer.skip(count: Int) {
    require(count >= 0 && count <= remaining())
    position(position() + count)
}
internal fun ByteBuffer.take(count: Int): ByteBuffer {
    require(count >= 0 && count <= remaining())
    val view = slice().asReadOnlyBuffer()
    view.limit(count)
    skip(count)
    return view
}
