package com.dylenpoki.dyltun.internal.diagnostics

import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.ConnectionStage
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.HostSource
import com.dylenpoki.dyltun.TlsClientHello
import com.dylenpoki.dyltun.TransportProtocol
import com.dylenpoki.dyltun.internal.inspection.ProtocolDetection
import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTraceTest {
    private val info = ConnectionInfo(1, TransportProtocol.TCP, InetAddress.getByName("10.0.0.2"), 1234,
        InetAddress.getByName("198.51.100.9"), 80)

    @Test fun httpSnapshotsOmitQueryAndRemainStableAfterTheNextRequest() {
        var now = 0L
        val candidates = mutableListOf("candidate.test")
        val trace = ConnectionTrace(info, { candidates }, MonotonicClock { now })
        trace.httpRequest("POST", "http://example.com/upload?key=secret#fragment")
        trace.httpFlow(7)
        trace.clientBytes(10); trace.serverBytes(20)
        now = 123_000_000
        val first = trace.snapshot()
        trace.httpRequest("GET", "http://next.test/")
        candidates.clear()
        assertEquals("example.com", first.host)
        assertEquals(HostSource.HTTP_AUTHORITY, first.hostSource)
        assertEquals("/upload", first.httpPath)
        assertEquals(7L, first.httpFlowId)
        assertEquals(123L, first.durationMillis)
        assertEquals(10L, first.bytesFromClient); assertEquals(20L, first.bytesToClient)
        assertEquals(listOf("candidate.test"), first.dnsCandidates)
        assertEquals("next.test", trace.snapshot().host)
        assertNull(trace.snapshot().httpFlowId)
    }

    @Test fun staleUpstreamCallbackCannotOverwriteANewerTransaction() {
        val trace = ConnectionTrace(info)
        trace.httpRequest("GET", "http://first.test/")
        val first = trace.beginUpstream(Endpoint("first.test", 80))
        trace.httpRequest("GET", "http://second.test/")
        first(Endpoint("192.0.2.1", 80))
        assertNull(trace.snapshot().upstream)
        assertEquals(ConnectionStage.PROCESSING, trace.snapshot().stage)
        val second = trace.beginUpstream(Endpoint("second.test", 8080))
        first(Endpoint("192.0.2.1", 80))
        assertEquals(ConnectionStage.CONNECTING, trace.snapshot().stage)
        second(Endpoint("192.0.2.2", 8080))
        assertEquals(Endpoint("192.0.2.2", 8080), trace.snapshot().resolvedUpstream)
        assertEquals(ConnectionStage.FORWARDING, trace.snapshot().stage)
    }

    @Test fun tlsIdentityAndDnsCandidatesHaveSeparateProvenance() {
        val trace = ConnectionTrace(info, { listOf("candidate.test") })
        trace.detected(ProtocolDetection(listOf(TlsClientHello("sni.test"))))
        assertEquals("tls", trace.snapshot().protocol)
        assertEquals("sni.test", trace.snapshot().host)
        assertEquals(HostSource.TLS_SNI, trace.snapshot().hostSource)
        trace.detected(ProtocolDetection(emptyList()))
        assertNull(trace.snapshot().host)
        assertNull(trace.snapshot().hostSource)
        assertEquals(listOf("candidate.test"), trace.snapshot().dnsCandidates)
    }
}
