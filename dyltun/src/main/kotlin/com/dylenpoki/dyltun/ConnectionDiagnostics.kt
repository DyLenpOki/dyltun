package com.dylenpoki.dyltun

/** The most recently reached connection stage, preserved when an error is reported. */
enum class ConnectionStage { HANDSHAKE, INSPECTION, ROUTING, PROCESSING, CONNECTING, FORWARDING }

enum class HostSource { HTTP_AUTHORITY, TLS_SNI }

/** Best-effort immutable event snapshot. DNS names are candidates, never authoritative host identities. */
data class ConnectionDiagnostics(
    val protocol: String? = null,
    val host: String? = null,
    val hostSource: HostSource? = null,
    val dnsCandidates: List<String> = emptyList(),
    val upstream: Endpoint? = null,
    val resolvedUpstream: Endpoint? = null,
    val stage: ConnectionStage? = null,
    val tcpState: String? = null,
    val httpMethod: String? = null,
    val httpPath: String? = null,
    val httpFlowId: Long? = null,
    val durationMillis: Long = 0,
    val bytesFromClient: Long = 0,
    val bytesToClient: Long = 0
)
