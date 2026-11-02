package com.dylenpoki.dyltun

import java.util.Collections

sealed interface RoutingDecision {
    data object Continue : RoutingDecision
    data object Forward : RoutingDecision
    data class Redirect(val destination: Endpoint) : RoutingDecision
    data object Reset : RoutingDecision
    data object Drop : RoutingDecision
}

data class RoutingContext(
    val connection: ConnectionInfo,
    val metadata: ProtocolMetadata? = null,
    val destination: Endpoint = connection.originalDestination,
    val detectedProtocols: List<ProtocolMetadata> = listOfNotNull(metadata)
)

/** Rules must be deterministic and must not perform network I/O. */
interface ConnectionRule {
    val requiredProtocols: Set<ProtocolId> get() = emptySet()
    fun evaluate(context: RoutingContext): RoutingDecision
}

@DyltunDsl
class TcpActionBuilder internal constructor() {
    private var decision: RoutingDecision? = null

    fun forward() = set(RoutingDecision.Forward)
    fun reset() = set(RoutingDecision.Reset)
    fun redirect(host: String, port: Int) = set(RoutingDecision.Redirect(Endpoint(host, port)))

    private fun set(value: RoutingDecision) {
        check(decision == null) { "Each routing rule must specify exactly one action" }
        decision = value
    }

    internal fun build(): RoutingDecision = checkNotNull(decision) { "Routing action is missing" }
}

class RoutingConfig private constructor(
    val rules: List<ConnectionRule>,
    internal val defaultInspectors: Set<ProtocolId>
) {
    @DyltunDsl
    class Builder internal constructor() {
        private val rules = mutableListOf<ConnectionRule>()
        private val defaultInspectors = linkedSetOf<ProtocolId>()

        fun tlsSni(
            domain: String,
            includeSubdomains: Boolean = false,
            action: TcpActionBuilder.() -> Unit
        ) = tlsSni(listOf(domain), includeSubdomains, action)

        /** Copies and validates domains before installing a rule; an empty group is invalid. */
        fun tlsSni(
            domains: Iterable<String>,
            includeSubdomains: Boolean = false,
            action: TcpActionBuilder.() -> Unit
        ) = addTlsRule(domains, includeSubdomains) { TcpActionBuilder().apply(action).build() }

        /** Value-action shorthand; installs the default TLS inspector only when none was supplied. */
        fun tlsSni(
            domains: Iterable<String>,
            includeSubdomains: Boolean = false,
            action: RoutingDecision
        ) {
            addTlsRule(domains, includeSubdomains) { requireTcpAction(action) }
            defaultInspectors += ProtocolId.TLS
        }

        private fun addTlsRule(
            domains: Iterable<String>, includeSubdomains: Boolean, action: () -> RoutingDecision
        ) {
            val expected = domains.map(::canonicalDomain).toSet()
            require(expected.isNotEmpty()) { "At least one TLS SNI domain is required" }
            val suffixes = if (includeSubdomains) expected.map { ".$it" } else emptyList()
            val decision = action()
            rule(object : ConnectionRule {
                override val requiredProtocols = setOf(ProtocolId.TLS)
                override fun evaluate(context: RoutingContext): RoutingDecision {
                    if (context.connection.transport != TransportProtocol.TCP) return RoutingDecision.Continue
                    val actual = (context.metadata as? TlsClientHello)?.serverName
                        ?: return RoutingDecision.Continue
                    return if (actual in expected || suffixes.any { actual.endsWith(it) }) {
                        decision
                    } else RoutingDecision.Continue
                }
            })
        }

        fun mqtt(action: TcpActionBuilder.() -> Unit) =
            addMqttRule(TcpActionBuilder().apply(action).build())

        /** Value-action shorthand; installs the default MQTT inspector only when none was supplied. */
        fun mqtt(action: RoutingDecision) {
            addMqttRule(requireTcpAction(action))
            defaultInspectors += ProtocolId.MQTT
        }

        private fun addMqttRule(decision: RoutingDecision) {
            rule(object : ConnectionRule {
                override val requiredProtocols = setOf(ProtocolId.MQTT)
                override fun evaluate(context: RoutingContext): RoutingDecision =
                    if (context.connection.transport == TransportProtocol.TCP && context.metadata is MqttConnect) {
                        decision
                    } else RoutingDecision.Continue
            })
        }

        fun rule(rule: ConnectionRule) {
            val dependencies = Collections.unmodifiableSet(HashSet(rule.requiredProtocols))
            rules += object : ConnectionRule {
                override val requiredProtocols: Set<ProtocolId> = dependencies
                override fun evaluate(context: RoutingContext) = rule.evaluate(context)
            }
        }

        fun rule(
            requiredProtocols: Set<ProtocolId> = emptySet(),
            evaluate: (RoutingContext) -> RoutingDecision
        ) {
            val dependencies = requiredProtocols
            rule(object : ConnectionRule {
                override val requiredProtocols = dependencies
                override fun evaluate(context: RoutingContext): RoutingDecision = evaluate.invoke(context)
            })
        }

        private fun requireTcpAction(action: RoutingDecision): RoutingDecision {
            require(action == RoutingDecision.Forward || action == RoutingDecision.Reset || action is RoutingDecision.Redirect) {
                "A TCP action must be Forward, Reset or Redirect"
            }
            return action
        }

        internal fun build(): RoutingConfig = RoutingConfig(
            Collections.unmodifiableList(ArrayList(rules)),
            Collections.unmodifiableSet(LinkedHashSet(defaultInspectors))
        )
    }
}

internal object RoutingEvaluator {
    fun evaluate(config: RoutingConfig, context: RoutingContext): RoutingDecision {
        var selected: RoutingDecision = RoutingDecision.Continue
        for (rule in config.rules) {
            if (!rule.requiredProtocols.all { id -> context.detectedProtocols.any { it.protocolId == id } }) continue
            val metadata = if (rule.requiredProtocols.isEmpty()) context.metadata else
                context.detectedProtocols.firstOrNull { it.protocolId in rule.requiredProtocols }
            val decision = rule.evaluate(context.copy(metadata = metadata))
            require(!(context.connection.transport == TransportProtocol.UDP && decision == RoutingDecision.Reset)) {
                "A UDP rule cannot reset TCP"
            }
            require(!(context.connection.transport == TransportProtocol.TCP && decision == RoutingDecision.Drop)) {
                "A TCP rule must use Reset rather than Drop"
            }
            if (decision == RoutingDecision.Reset || decision == RoutingDecision.Drop) return decision
            if (selected == RoutingDecision.Continue && decision != RoutingDecision.Continue) selected = decision
        }
        return if (selected == RoutingDecision.Continue) RoutingDecision.Forward else selected
    }
}
