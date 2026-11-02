package com.dylenpoki.dyltun

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class MqttConnect(val version: Int, val clientId: String) : ProtocolMetadata {
    override val protocolId: ProtocolId get() = ProtocolId.MQTT
}

class MqttInspector : ProtocolInspector {
    override val protocolId: ProtocolId get() = ProtocolId.MQTT

    override fun inspect(context: InspectionContext, prefix: ByteBuffer): InspectionResult {
        val input = prefix.asReadOnlyBuffer()
        if (!input.hasRemaining()) return InspectionResult.NeedMore
        if (input.u8() != 0x10) return InspectionResult.NoMatch
        var length = 0
        var multiplier = 1
        var digits = 0
        while (true) {
            if (!input.hasRemaining()) return InspectionResult.NeedMore
            val digit = input.u8()
            digits++
            length += (digit and 127) * multiplier
            if (length > MAX_CONNECT || digits == 4 && digit and 128 != 0) return InspectionResult.NoMatch
            if (digit and 128 == 0) {
                if (digits > 1 && digit == 0) return InspectionResult.NoMatch
                break
            }
            multiplier *= 128
        }
        if (input.remaining() < length) return InspectionResult.NeedMore
        return parseConnect(input.take(length))
    }

    private fun parseConnect(body: ByteBuffer): InspectionResult = try {
        val name = readString(body)
        val version = body.u8()
        require(name == "MQTT" && version in 4..5 || name == "MQIsdp" && version == 3)
        val flags = body.u8()
        require(flags and 1 == 0)
        val will = flags and 4 != 0
        val qos = (flags ushr 3) and 3
        require(qos <= 2 && (will || flags and 0x38 == 0))
        require(version == 5 || flags and 0x40 == 0 || flags and 0x80 != 0)
        body.skip(2)
        if (version == 5) skipProperties(body)
        val clientId = readString(body)
        require(clientId.isNotEmpty() || version >= 4 && flags and 2 != 0)
        require(version != 3 || clientId.length <= 23)
        if (will) {
            if (version == 5) skipProperties(body)
            readString(body)
            body.skip(body.u16())
        }
        if (flags and 0x80 != 0) readString(body)
        if (flags and 0x40 != 0) body.skip(body.u16())
        require(!body.hasRemaining())
        InspectionResult.Matched(MqttConnect(version, clientId))
    } catch (_: IllegalArgumentException) {
        InspectionResult.NoMatch
    } catch (_: java.nio.BufferUnderflowException) {
        InspectionResult.NoMatch
    } catch (_: java.nio.charset.CharacterCodingException) {
        InspectionResult.NoMatch
    }

    private fun readString(input: ByteBuffer): String {
        val bytes = input.take(input.u16())
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString()
        require('\u0000' !in text)
        return text
    }

    private fun skipProperties(input: ByteBuffer) {
        var length = 0
        var multiplier = 1
        repeat(4) { index ->
            val digit = input.u8()
            length += (digit and 127) * multiplier
            if (digit and 128 == 0) {
                require(index == 0 || digit != 0)
                input.skip(length)
                return
            }
            multiplier *= 128
        }
        throw IllegalArgumentException("Malformed property length")
    }

    private companion object { const val MAX_CONNECT = 65_536 }
}
