package com.dylenpoki.dyltun

import java.net.IDN
import java.net.InetAddress
import java.util.Locale

enum class TransportProtocol { TCP, UDP }

data class Endpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank() && host.length <= 253) { "Invalid endpoint host" }
        require(host.none { it.isWhitespace() || it.code < 33 || it in "/\\@?#" }) {
            "Invalid endpoint host"
        }
        require(port in 1..65535) { "Port must be within 1..65535" }
    }
}

data class ConnectionInfo(
    val id: Long,
    val transport: TransportProtocol,
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val destinationAddress: InetAddress,
    val destinationPort: Int
) {
    init {
        require(sourcePort in (if (transport == TransportProtocol.UDP) 0 else 1)..65535)
        require(destinationPort in 1..65535)
    }

    val originalDestination: Endpoint
        get() = Endpoint(requireNotNull(destinationAddress.hostAddress), destinationPort)
}

internal fun canonicalDomain(value: String): String {
    require(value.isNotBlank() && value.none { it.isWhitespace() || it.code < 33 }) {
        "Invalid domain"
    }
    val ascii = IDN.toASCII(value.removeSuffix("."), IDN.USE_STD3_ASCII_RULES)
        .lowercase(Locale.ROOT)
    require(ascii.length in 1..253 && ascii.split('.').all { it.length in 1..63 }) {
        "Invalid domain"
    }
    return ascii
}
