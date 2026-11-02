package com.dylenpoki.dyltun

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleConfigTest {
    @Test fun factoryDefaultsAndSnapshotsDoNotStartAService() {
        val defaults = dyltunConfig()
        assertNull(defaults.hooks)
        assertTrue(defaults.protocols.inspectors.isEmpty())
        lateinit var escaped: DyltunConfig.Builder
        val hooks = object : HttpHooks {}
        val config = dyltunConfig {
            escaped = this
            this.hooks = hooks
            routing { mqtt(action = RoutingDecision.Forward) }
        }
        escaped.hooks = null
        escaped.routing { tlsSni(listOf("later.example"), action = RoutingDecision.Reset) }
        assertSame(hooks, config.hooks)
        assertEquals(listOf(ProtocolId.MQTT), config.protocols.inspectors.map { it.protocolId })
        assertEquals(1, config.routing.rules.size)
    }

    @Test fun valueRulesSupplyEachDefaultInspectorOnlyOnce() {
        val broker = Endpoint("127.0.0.1", 1883)
        val config = dyltunConfig {
            routing {
                tlsSni(listOf("EXAMPLE.com.", "second.example"), true, RoutingDecision.Reset)
                tlsSni(setOf("other.example"), action = RoutingDecision.Forward)
                mqtt(action = RoutingDecision.Redirect(broker))
                mqtt(action = RoutingDecision.Forward)
            }
        }
        assertEquals(setOf(ProtocolId.TLS, ProtocolId.MQTT), config.protocols.inspectors.map { it.protocolId }.toSet())
        assertEquals(2, config.protocols.inspectors.size)
        assertTrue(config.protocols.inspectors.any { it is TlsClientHelloInspector })
        assertTrue(config.protocols.inspectors.any { it is MqttInspector })
        assertEquals(RoutingDecision.Reset, decision(config, TlsClientHello("www.second.example")))
        assertEquals(RoutingDecision.Forward, decision(config, TlsClientHello("notexample.com")))
        assertEquals(RoutingDecision.Redirect(broker), decision(config, MqttConnect(4, "client")))
    }

    @Test fun explicitInspectorsWinRegardlessOfConfigurationOrder() {
        for (installFirst in listOf(true, false)) {
            val tls = inspector(ProtocolId.TLS)
            val mqtt = inspector(ProtocolId.MQTT)
            val config = dyltunConfig {
                if (installFirst) protocols { install(tls); install(mqtt) }
                routing {
                    tlsSni(listOf("example.com"), action = RoutingDecision.Reset)
                    mqtt(action = RoutingDecision.Forward)
                }
                if (!installFirst) protocols { install(tls); install(mqtt) }
            }
            assertSame(tls, config.protocols.inspectors.first { it.protocolId == ProtocolId.TLS })
            assertSame(mqtt, config.protocols.inspectors.first { it.protocolId == ProtocolId.MQTT })
            assertEquals(2, config.protocols.inspectors.size)
        }
    }

    @Test fun legacyAndCustomRulesStillRequireExplicitInspectors() {
        assertThrows(IllegalArgumentException::class.java) {
            dyltunConfig { routing { mqtt { forward() } } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            dyltunConfig {
                routing { rule(setOf(ProtocolId.TLS)) { RoutingDecision.Reset } }
            }
        }
        val unknown = ProtocolId("custom")
        assertThrows(IllegalArgumentException::class.java) {
            dyltunConfig {
                routing {
                    mqtt(action = RoutingDecision.Forward)
                    rule(setOf(unknown)) { RoutingDecision.Forward }
                }
            }
        }
    }

    @Test fun valueActionsRejectContinueAndDropWithoutInstallingPartialRules() {
        val builder = DyltunConfig.Builder()
        for (action in listOf(RoutingDecision.Continue, RoutingDecision.Drop)) {
            assertThrows(IllegalArgumentException::class.java) {
                builder.routing { tlsSni(listOf("example.com"), action = action) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                builder.routing { mqtt(action = action) }
            }
        }
        assertTrue(builder.build().routing.rules.isEmpty())
        assertTrue(builder.build().protocols.inspectors.isEmpty())
    }

    @Test fun newDomainRulesValidateAndSnapshotCollections() {
        val domains = mutableListOf("example.com")
        val config = dyltunConfig {
            routing { tlsSni(domains, action = RoutingDecision.Reset) }
        }
        domains.clear()
        domains += "changed.example"
        assertEquals(RoutingDecision.Reset, decision(config, TlsClientHello("example.com")))
        assertEquals(RoutingDecision.Forward, decision(config, TlsClientHello("changed.example")))
        assertEquals(RoutingDecision.Forward, decision(config, TlsClientHello("www.example.com")))
        for (invalid in listOf(emptyList(), listOf("good.example", "*.bad.example"))) {
            assertThrows(IllegalArgumentException::class.java) {
                dyltunConfig { routing { tlsSni(invalid, action = RoutingDecision.Reset) } }
            }
            var actionCalled = false
            assertThrows(IllegalArgumentException::class.java) {
                dyltunConfig {
                    routing { tlsSni(invalid) { actionCalled = true; reset() } }
                }
            }
            assertEquals(false, actionCalled)
        }
    }

    @Test fun lambdaRuleDependenciesAreCopiedAndDenialsKeepPriority() {
        val dependencies = mutableSetOf(ProtocolId.MQTT)
        val config = dyltunConfig {
            routing {
                mqtt(action = RoutingDecision.Forward)
                rule(dependencies) { RoutingDecision.Reset }
            }
        }
        dependencies.clear()
        dependencies += ProtocolId("later")
        assertEquals(setOf(ProtocolId.MQTT), config.routing.rules.last().requiredProtocols)
        assertEquals(RoutingDecision.Reset, decision(config, MqttConnect(4, "client")))
        assertEquals(RoutingDecision.Forward, RoutingEvaluator.evaluate(config.routing, RoutingContext(connection())))
    }

    @Test fun lambdaRulesKeepTransportActionChecks() {
        val config = dyltunConfig { routing { rule { RoutingDecision.Drop } } }
        assertEquals(RoutingDecision.Drop,
            RoutingEvaluator.evaluate(config.routing, RoutingContext(connection(TransportProtocol.UDP))))
        assertThrows(IllegalArgumentException::class.java) {
            RoutingEvaluator.evaluate(config.routing, RoutingContext(connection()))
        }
    }

    private fun decision(config: DyltunConfig, metadata: ProtocolMetadata): RoutingDecision =
        RoutingEvaluator.evaluate(config.routing, RoutingContext(connection(), metadata))

    private fun inspector(id: ProtocolId): ProtocolInspector = object : ProtocolInspector {
        override val protocolId = id
        override fun inspect(context: InspectionContext, prefix: ByteBuffer) = InspectionResult.NoMatch
    }
}
