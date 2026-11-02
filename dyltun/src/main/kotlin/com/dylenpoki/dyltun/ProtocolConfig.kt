package com.dylenpoki.dyltun

import java.nio.ByteBuffer
import java.util.Collections

@JvmInline
value class ProtocolId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid protocol ID" }
    }

    companion object {
        val TLS = ProtocolId("tls")
        val MQTT = ProtocolId("mqtt")
    }
}

interface ProtocolMetadata {
    val protocolId: ProtocolId
}

data class InspectionContext(val connection: ConnectionInfo)

sealed interface InspectionResult {
    data object NeedMore : InspectionResult
    data object NoMatch : InspectionResult
    data class Matched(val metadata: ProtocolMetadata) : InspectionResult
}

/** Implementations must not retain the prefix view or perform blocking I/O. */
interface ProtocolInspector {
    val protocolId: ProtocolId
    fun inspect(context: InspectionContext, prefix: ByteBuffer): InspectionResult
}

class ProtocolConfig private constructor(val inspectors: List<ProtocolInspector>) {
    @DyltunDsl
    class Builder internal constructor() {
        private val inspectors = mutableListOf<ProtocolInspector>()

        fun install(inspector: ProtocolInspector) {
            require(inspectors.none { it.protocolId == inspector.protocolId }) {
                "Duplicate protocol: ${inspector.protocolId.value}"
            }
            inspectors += inspector
        }

        internal fun build(): ProtocolConfig = ProtocolConfig(
            Collections.unmodifiableList(ArrayList(inspectors))
        )
    }
}
