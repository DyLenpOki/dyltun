package com.dylenpoki.dyltun.internal.inspection

import com.dylenpoki.dyltun.ConnectionRule
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.InspectionContext
import com.dylenpoki.dyltun.InspectionResult
import com.dylenpoki.dyltun.ProtocolConfig
import com.dylenpoki.dyltun.ProtocolId
import com.dylenpoki.dyltun.ProtocolInspector
import com.dylenpoki.dyltun.ProtocolMetadata
import com.dylenpoki.dyltun.RoutingContext
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.RoutingEvaluator
import com.dylenpoki.dyltun.connection
import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolProbeTest {
    private class Metadata(override val protocolId: ProtocolId) : ProtocolMetadata
    private fun inspector(name: String, result: InspectionResult) = object : ProtocolInspector {
        override val protocolId = ProtocolId(name)
        override fun inspect(context: InspectionContext, prefix: ByteBuffer): InspectionResult {
            assertTrue(prefix.isReadOnly)
            return result
        }
    }

    @Test fun multipleMatchesCannotHideALaterDenyRule() {
        val allowed = Metadata(ProtocolId("allowed"))
        val blocked = Metadata(ProtocolId("blocked"))
        val config = DyltunConfig.Builder().apply {
            protocols {
                install(inspector("allowed", InspectionResult.Matched(allowed)))
                install(inspector("blocked", InspectionResult.Matched(blocked)))
            }
            routing {
                rule(object : ConnectionRule {
                    override val requiredProtocols = setOf(allowed.protocolId)
                    override fun evaluate(context: RoutingContext) = RoutingDecision.Forward
                })
                rule(object : ConnectionRule {
                    override val requiredProtocols = setOf(blocked.protocolId)
                    override fun evaluate(context: RoutingContext): RoutingDecision {
                        assertSame(blocked, context.metadata)
                        assertEquals(2, context.detectedProtocols.size)
                        return RoutingDecision.Reset
                    }
                })
            }
        }.build()
        val probe = ProtocolProbe(config.protocols, connection(), MonotonicClock { 0 })
        assertTrue(probe.advance(byteArrayOf(1), false))
        val context = RoutingContext(connection(), detectedProtocols = probe.metadata)
        assertEquals(RoutingDecision.Reset, RoutingEvaluator.evaluate(config.routing, context))
        assertThrows(UnsupportedOperationException::class.java) { (probe.metadata as MutableList).clear() }
    }

    @Test fun serverFirstProtocolFallsBackAtDeadlineWithoutConsumingPrefix() {
        var now = 0L
        val config = ProtocolConfig.Builder().apply { install(inspector("waiting", InspectionResult.NeedMore)) }.build()
        val probe = ProtocolProbe(config, connection(), MonotonicClock { now })
        val bytes = "partial".toByteArray()
        assertFalse(probe.advance(bytes, false))
        now = 999_999_999
        assertFalse(probe.advance(bytes, false))
        now++
        assertTrue(probe.advance(bytes, false))
        assertArrayEquals("partial".toByteArray(), bytes)
    }

    @Test fun sizeAndEofBoundIncompleteInspectors() {
        val config = ProtocolConfig.Builder().apply { install(inspector("waiting", InspectionResult.NeedMore)) }.build()
        assertTrue(ProtocolProbe(config, connection(), MonotonicClock { 0 }).advance(ByteArray(65535), false))
        assertTrue(ProtocolProbe(config, connection(), MonotonicClock { 0 }).advance(byteArrayOf(), true))
    }

    @Test fun inspectorCannotReturnAnotherIdOrMutatePrefix() {
        val config = ProtocolConfig.Builder().apply {
            install(inspector("first", InspectionResult.Matched(Metadata(ProtocolId("second")))))
        }.build()
        assertThrows(IllegalStateException::class.java) {
            ProtocolProbe(config, connection(), MonotonicClock { 0 }).advance(byteArrayOf(1), false)
        }
    }
}
