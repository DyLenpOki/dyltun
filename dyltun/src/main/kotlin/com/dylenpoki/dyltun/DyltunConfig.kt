package com.dylenpoki.dyltun

class DyltunConfig private constructor(
    val hooks: HttpHooks?,
    val bodyLimits: BodyLimits,
    val protocols: ProtocolConfig,
    val routing: RoutingConfig
) {
    @DyltunDsl
    class Builder internal constructor() {
        var hooks: HttpHooks? = null
        var bodyLimits: BodyLimits = BodyLimits()
        private val protocolBuilder = ProtocolConfig.Builder()
        private val routingBuilder = RoutingConfig.Builder()

        fun protocols(configure: ProtocolConfig.Builder.() -> Unit) {
            protocolBuilder.apply(configure)
        }

        fun routing(configure: RoutingConfig.Builder.() -> Unit) {
            routingBuilder.apply(configure)
        }

        internal fun build(): DyltunConfig {
            val protocols = protocolBuilder.build()
            val routing = routingBuilder.build()
            val available = protocols.inspectors.map { it.protocolId }.toSet()
            val missing = routing.rules.flatMap { it.requiredProtocols }.toSet() - available
            require(missing.isEmpty()) { "Missing protocol inspectors: ${missing.joinToString { it.value }}" }
            return DyltunConfig(hooks, bodyLimits, protocols, routing)
        }
    }
}
