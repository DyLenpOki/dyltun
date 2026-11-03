package com.dylenpoki.dyltun.internal.diagnostics

import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsNamesTest {
    @Test fun compressedCnameChainUsesTheShortestTtlAndExpires() {
        var now = 0L
        val cache = DnsNames(MonotonicClock { now })
        cache.query(1, DnsPackets.query("accs.test"))
        cache.response(1, DnsPackets.response("accs.test", alias = "edge.test", ttl = 2))
        assertEquals(listOf("accs.test"), cache.candidates("198.51.100.9"))
        now = 2_000_000_000L
        assertTrue(cache.candidates("198.51.100.9").isEmpty())
    }

    @Test fun unrelatedFlowQuestionIdAndExpiredQueryCannotCreateCandidates() {
        var now = 0L
        val cache = DnsNames(MonotonicClock { now })
        cache.response(1, DnsPackets.response("accs.test"))
        cache.query(1, DnsPackets.query("accs.test"))
        cache.response(2, DnsPackets.response("accs.test"))
        cache.response(1, DnsPackets.response("other.test"))
        cache.response(1, DnsPackets.response("accs.test").also { it[1] = 2 })
        assertTrue(cache.candidates("198.51.100.9").isEmpty())
        now = 10_000_000_000L
        cache.response(1, DnsPackets.response("accs.test"))
        assertTrue(cache.candidates("198.51.100.9").isEmpty())
    }

    @Test fun truncatedOrCyclicNamesAreIgnoredWithoutLosingThePendingQuery() {
        val cache = DnsNames()
        val response = DnsPackets.response("accs.test")
        cache.query(1, DnsPackets.query("accs.test"))
        for (size in response.indices) cache.response(1, response.copyOf(size))
        cache.response(1, response.copyOf().also { it[12] = 0xc0.toByte(); it[13] = 12 })
        assertTrue(cache.candidates("198.51.100.9").isEmpty())
        cache.response(1, response)
        assertEquals(listOf("accs.test"), cache.candidates("198.51.100.9"))
    }

    @Test fun zeroTtlAndTruncatedDnsFlagAreNotCached() {
        val cache = DnsNames()
        cache.query(1, DnsPackets.query("accs.test"))
        cache.response(1, DnsPackets.response("accs.test").also { it[2] = 0x83.toByte() })
        assertTrue(cache.candidates("198.51.100.9").isEmpty())
        cache.response(1, DnsPackets.response("accs.test"))
        assertEquals(listOf("accs.test"), cache.candidates("198.51.100.9"))
        cache.query(1, DnsPackets.query("accs.test"))
        cache.response(1, DnsPackets.response("accs.test", ttl = 0))
        assertTrue(cache.candidates("198.51.100.9").isEmpty())
    }

    @Test fun sharedIpCandidatesAreBoundedAndOldCacheEntriesAreEvicted() {
        val cache = DnsNames()
        repeat(257) {
            val name = "host$it.test"
            cache.query(1, DnsPackets.query(name))
            cache.response(1, DnsPackets.response(name))
        }
        assertEquals(listOf("host1.test", "host2.test", "host3.test", "host4.test"), cache.candidates("198.51.100.9"))
        cache.clear()
        assertTrue(cache.candidates("198.51.100.9").isEmpty())
    }

    @Test fun pendingQueriesAreBoundedAndTtlIsCappedAtOneHour() {
        var now = 0L
        val cache = DnsNames(MonotonicClock { now })
        repeat(129) { cache.query(it.toLong(), DnsPackets.query("accs.test")) }
        cache.response(0, DnsPackets.response("accs.test"))
        assertTrue(cache.candidates("198.51.100.9").isEmpty())
        cache.response(128, DnsPackets.response("accs.test", ttl = 10000))
        assertEquals(listOf("accs.test"), cache.candidates("198.51.100.9"))
        now = 3_600_000_000_000L
        assertTrue(cache.candidates("198.51.100.9").isEmpty())
    }
}

internal object DnsPackets {
    fun query(host: String): ByteArray = wire {
        writeShort(1); writeShort(0x0100); writeShort(1); writeShort(0); writeInt(0)
        name(host); writeShort(1); writeShort(1)
    }

    fun response(host: String, alias: String? = null, ttl: Int = 60): ByteArray = wire {
        writeShort(1); writeShort(0x8180); writeShort(1); writeShort(if (alias == null) 1 else 2); writeInt(0)
        name(host); writeShort(1); writeShort(1)
        if (alias != null) {
            val data = wire { name(alias) }
            writeShort(0xc00c); writeShort(5); writeShort(1); writeInt(ttl); writeShort(data.size); write(data)
            name(alias)
        } else writeShort(0xc00c)
        writeShort(1); writeShort(1); writeInt(if (alias == null) ttl else 600); writeShort(4)
        write(byteArrayOf(198.toByte(), 51, 100, 9))
    }

    private fun DataOutputStream.name(host: String) {
        host.split('.').forEach { writeByte(it.length); writeBytes(it) }
        writeByte(0)
    }
    private fun wire(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray()
}
