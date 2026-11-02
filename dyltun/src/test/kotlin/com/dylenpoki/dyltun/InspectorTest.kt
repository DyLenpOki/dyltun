package com.dylenpoki.dyltun

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

private fun wire(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
    DataOutputStream(it).apply(block)
}.toByteArray()

class InspectorTest {
    private val context = InspectionContext(connection())

    @Test fun clientHelloAcrossRecordsAndEveryPrefixBoundary() {
        val hello = clientHello("WWW.Example.com")
        val records = record(hello.copyOfRange(0, 17)) + record(hello.copyOfRange(17, hello.size))
        val inspector = TlsClientHelloInspector()
        for (length in 0 until records.size) {
            assertEquals("prefix $length", InspectionResult.NeedMore, inspector.inspect(context, ByteBuffer.wrap(records, 0, length)))
        }
        val input = ByteBuffer.wrap(byteArrayOf(99) + records).apply { position(1) }
        val result = inspector.inspect(context, input) as InspectionResult.Matched
        assertEquals(TlsClientHello("www.example.com"), result.metadata)
        assertEquals(1, input.position())
    }

    @Test fun tlsWithoutSniAndMalformedLengths() {
        val inspector = TlsClientHelloInspector()
        assertEquals(InspectionResult.Matched(TlsClientHello(null)), inspector.inspect(context, ByteBuffer.wrap(record(clientHello(null)))))
        val malformed = record(clientHello("a.test")).also { it[it.lastIndex - 7] = 127 }
        assertEquals(InspectionResult.NoMatch, inspector.inspect(context, ByteBuffer.wrap(malformed)))
        assertEquals(InspectionResult.NoMatch, inspector.inspect(context, ByteBuffer.wrap("GET / HTTP/1.1".toByteArray())))
        assertEquals(InspectionResult.NoMatch, inspector.inspect(context, ByteBuffer.wrap(byteArrayOf(22, 3, 3, 127, -1))))
    }

    @Test fun mqttVersionsAndPrefixBoundaries() {
        for (version in 3..5) {
            val bytes = mqtt(version, "client-1")
            val inspector = MqttInspector()
            for (length in 0 until bytes.size) {
                assertEquals(InspectionResult.NeedMore, inspector.inspect(context, ByteBuffer.wrap(bytes, 0, length)))
            }
            assertEquals(InspectionResult.Matched(MqttConnect(version, "client-1")), inspector.inspect(context, ByteBuffer.wrap(bytes)))
        }
    }

    @Test fun mqttRejectsMalformedFlagsUtf8AndNonMinimalLength() {
        val inspector = MqttInspector()
        val invalidFlags = mqtt(4, "client").also { it[9] = 1 }
        val invalidUtf8 = mqtt(4, "client").also { it[it.lastIndex] = 0xff.toByte() }
        for (bytes in listOf(invalidFlags, invalidUtf8, byteArrayOf(0x10, 0x80.toByte(), 0), byteArrayOf(0x10, -1, -1, -1, -1))) {
            assertEquals(InspectionResult.NoMatch, inspector.inspect(context, ByteBuffer.wrap(bytes)))
        }
        assertEquals(InspectionResult.NoMatch, inspector.inspect(context, ByteBuffer.wrap(mqtt(3, ""))))
        assertEquals(InspectionResult.Matched(MqttConnect(5, "")), inspector.inspect(context, ByteBuffer.wrap(mqtt(5, ""))))
    }

    private fun record(bytes: ByteArray) = wire { writeByte(22); writeShort(0x0303); writeShort(bytes.size); write(bytes) }

    private fun clientHello(host: String?): ByteArray {
        val body = wire {
            writeShort(0x0303); write(ByteArray(32)); writeByte(0)
            writeShort(2); writeShort(0x1301); writeByte(1); writeByte(0)
            if (host != null) {
                val name = host.toByteArray(Charsets.US_ASCII)
                val extension = wire {
                    writeShort(0); writeShort(name.size + 5); writeShort(name.size + 3)
                    writeByte(0); writeShort(name.size); write(name)
                }
                writeShort(extension.size); write(extension)
            }
        }
        return wire { writeByte(1); writeByte(body.size ushr 16); writeShort(body.size); write(body) }
    }

    private fun mqtt(version: Int, clientId: String): ByteArray {
        val body = wire {
            val name = if (version == 3) "MQIsdp" else "MQTT"
            writeShort(name.length); writeBytes(name); writeByte(version); writeByte(2); writeShort(60)
            if (version == 5) writeByte(0)
            val id = clientId.toByteArray()
            writeShort(id.size); write(id)
        }
        return byteArrayOf(0x10, body.size.toByte()) + body
    }
}
