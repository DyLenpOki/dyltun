package com.dylenpoki.dyltun

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun connection(transport: TransportProtocol = TransportProtocol.TCP) = ConnectionInfo(
    1, transport, InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2)), 12345,
    InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)), 80
)

class ConfigTest {
    @Test fun defaultsAndAllFourEntries() {
        val defaults = DyltunConfig.Builder().build()
        assertNull(defaults.hooks)
        assertTrue(defaults.protocols.inspectors.isEmpty())
        assertTrue(defaults.routing.rules.isEmpty())
        assertEquals(32L * 1024 * 1024, defaults.bodyLimits.maxTotalBufferedBytes)
        val hooks = object : HttpHooks {}
        val full = DyltunConfig.Builder().apply {
            this.hooks = hooks
            bodyLimits = BodyLimits(request = BodyLimit(512L * 1024, 2L * 1024 * 1024))
            // Dependency validation must not depend on DSL block order.
            routing {
                tlsSni("blocked.example.com", includeSubdomains = true) { reset() }
                mqtt { redirect("127.0.0.1", 1883) }
            }
            protocols {
                install(TlsClientHelloInspector())
                install(MqttInspector())
            }
        }.build()
        assertSame(hooks, full.hooks)
        assertEquals(2, full.protocols.inspectors.size)
        assertEquals(2, full.routing.rules.size)
    }

    @Test fun missingAndDuplicateInspectorsFail() {
        assertThrows(IllegalArgumentException::class.java) {
            DyltunConfig.Builder().apply { routing { mqtt { forward() } } }.build()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DyltunConfig.Builder().apply { protocols { install(MqttInspector()); install(MqttInspector()) } }
        }
    }

    @Test fun snapshotsResistEscapedBuilderMutation() {
        val builder = DyltunConfig.Builder()
        val original = builder.build()
        builder.protocols { install(MqttInspector()) }
        builder.routing { mqtt { forward() } }
        assertTrue(original.protocols.inspectors.isEmpty())
        assertTrue(original.routing.rules.isEmpty())
        val full = builder.build()
        assertThrows(UnsupportedOperationException::class.java) {
            (full.protocols.inspectors as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) { (full.routing.rules as MutableList).clear() }
    }

    @Test fun actionMustExistAndBeUnique() {
        assertThrows(IllegalStateException::class.java) { TcpActionBuilder().build() }
        assertThrows(IllegalStateException::class.java) { TcpActionBuilder().apply { forward(); reset() } }
        assertThrows(IllegalArgumentException::class.java) { TcpActionBuilder().redirect("127.0.0.1", 0) }
    }

    @Test fun domainMatchesLabelsAndDenyWins() {
        val routing = RoutingConfig.Builder().apply {
            rule(object : ConnectionRule {
                override fun evaluate(context: RoutingContext) = RoutingDecision.Redirect(Endpoint("127.0.0.1", 80))
            })
            tlsSni("EXAMPLE.com.", true) { reset() }
        }.build()
        fun decision(host: String) = RoutingEvaluator.evaluate(routing, RoutingContext(connection(), TlsClientHello(host)))
        assertEquals(RoutingDecision.Reset, decision("example.com"))
        assertEquals(RoutingDecision.Reset, decision("www.example.com"))
        assertTrue(decision("notexample.com") is RoutingDecision.Redirect)
        assertTrue(decision("example.com.attacker.test") is RoutingDecision.Redirect)
    }

    @Test fun udpUsesDropAndCustomRuleDependenciesAreCopied() {
        val dependencies = mutableSetOf<ProtocolId>()
        val rule = object : ConnectionRule {
            override val requiredProtocols = dependencies
            override fun evaluate(context: RoutingContext) = RoutingDecision.Drop
        }
        val config = DyltunConfig.Builder().apply { routing { rule(rule) } }.build()
        dependencies += ProtocolId.MQTT
        assertTrue(config.routing.rules.single().requiredProtocols.isEmpty())
        assertEquals(RoutingDecision.Drop, RoutingEvaluator.evaluate(config.routing, RoutingContext(connection(TransportProtocol.UDP))))
        assertThrows(IllegalArgumentException::class.java) {
            RoutingEvaluator.evaluate(config.routing, RoutingContext(connection()))
        }
    }

    @Test fun multipleTlsDomainsMatchAnyNormalizedDomainAndItsSubdomains() {
        val routing = RoutingConfig.Builder().apply {
            rule(object : ConnectionRule {
                override fun evaluate(context: RoutingContext) = RoutingDecision.Forward
            })
            tlsSni(listOf("EXAMPLE.com.", "bücher.example", "example.com"), includeSubdomains = true) { reset() }
        }.build()
        fun decision(host: String) = RoutingEvaluator.evaluate(routing, RoutingContext(connection(), TlsClientHello(host)))
        for (host in listOf("example.com", "a.b.example.com", "xn--bcher-kva.example", "www.xn--bcher-kva.example")) {
            assertEquals(host, RoutingDecision.Reset, decision(host))
        }
        for (host in listOf("notexample.com", "example.com.attacker.test", "notxn--bcher-kva.example", "other.test")) {
            assertEquals(host, RoutingDecision.Forward, decision(host))
        }
    }

    @Test fun multipleTlsDomainsMatchExactlyByDefault() {
        val destination = Endpoint("127.0.0.1", 8443)
        val routing = RoutingConfig.Builder().apply {
            tlsSni(listOf("first.example", "second.example")) { redirect(destination.host, destination.port) }
        }.build()
        fun decision(host: String) = RoutingEvaluator.evaluate(routing, RoutingContext(connection(), TlsClientHello(host)))
        assertEquals(RoutingDecision.Redirect(destination), decision("first.example"))
        assertEquals(RoutingDecision.Redirect(destination), decision("second.example"))
        assertEquals(RoutingDecision.Forward, decision("www.second.example"))
        assertEquals(RoutingDecision.Forward, decision("notfirst.example"))
    }

    @Test fun tlsDomainCollectionsAreSnapshottedAndSetsAreSupported() {
        val domains = mutableListOf("first.example", "second.example")
        val routing = RoutingConfig.Builder().apply {
            tlsSni(domains, includeSubdomains = true) { reset() }
            tlsSni(setOf("third.example", "fourth.example")) { reset() }
        }.build()
        domains.clear()
        domains += "changed.example"
        fun decision(host: String) = RoutingEvaluator.evaluate(routing, RoutingContext(connection(), TlsClientHello(host)))
        for (host in listOf("first.example", "www.second.example", "third.example", "fourth.example")) {
            assertEquals(host, RoutingDecision.Reset, decision(host))
        }
        assertEquals(RoutingDecision.Forward, decision("changed.example"))
        assertEquals(RoutingDecision.Forward, decision("www.third.example"))
    }

    @Test fun emptyOrInvalidTlsDomainGroupsDoNotInstallPartialRules() {
        val builder = RoutingConfig.Builder()
        assertThrows(IllegalArgumentException::class.java) { builder.tlsSni(emptyList()) { reset() } }
        for (invalid in listOf("", "*.example.com", "example.com\r\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                builder.tlsSni(listOf("valid.example", invalid)) { reset() }
            }
        }
        assertTrue(builder.build().rules.isEmpty())
    }

    @Test fun multiDomainTlsRulesKeepProtocolAndActionValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            DyltunConfig.Builder().apply {
                routing { tlsSni(listOf("first.example", "second.example")) { reset() } }
            }.build()
        }
        assertThrows(IllegalStateException::class.java) {
            RoutingConfig.Builder().tlsSni(listOf("first.example", "second.example")) {}
        }
        assertThrows(IllegalStateException::class.java) {
            RoutingConfig.Builder().tlsSni(listOf("first.example", "second.example")) { forward(); reset() }
        }
        val routing = RoutingConfig.Builder().apply {
            tlsSni(listOf("first.example", "second.example")) { reset() }
        }.build()
        for (context in listOf(
            RoutingContext(connection()),
            RoutingContext(connection(), TlsClientHello(null)),
            RoutingContext(connection(TransportProtocol.UDP), TlsClientHello("second.example"))
        )) {
            assertEquals(RoutingDecision.Forward, RoutingEvaluator.evaluate(routing, context))
        }
    }

    @Test fun invalidLimitsAndDomainsFailEarly() {
        assertThrows(IllegalArgumentException::class.java) { BodyLimit(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { BodyLimit(1, Int.MAX_VALUE.toLong() + 1) }
        assertThrows(IllegalArgumentException::class.java) { BodyLimit(1, 1, Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { BodyLimits(maxTotalBufferedBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { canonicalDomain("example.com\r\n") }
        assertThrows(IllegalArgumentException::class.java) { canonicalDomain("*.example.com") }
        assertEquals("xn--bcher-kva.example", canonicalDomain("bücher.example"))
    }
}
