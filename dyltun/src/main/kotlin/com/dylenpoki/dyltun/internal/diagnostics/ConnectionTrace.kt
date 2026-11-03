package com.dylenpoki.dyltun.internal.diagnostics

import com.dylenpoki.dyltun.ConnectionDiagnostics
import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.ConnectionStage
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.HostSource
import com.dylenpoki.dyltun.TlsClientHello
import com.dylenpoki.dyltun.internal.inspection.ProtocolDetection
import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Observability only: no packet ownership, networking, reverse DNS or routing decisions. */
internal class ConnectionTrace(
    connection: ConnectionInfo,
    private val dnsCandidates: () -> List<String> = { emptyList() },
    private val clock: MonotonicClock = MonotonicClock.SYSTEM
) {
    private data class State(val details: ConnectionDiagnostics, val attempt: Any? = null)
    private val started = clock.now()
    private val state = AtomicReference(State(ConnectionDiagnostics(protocol = connection.transport.name.lowercase(Locale.ROOT))))
    private val received = AtomicLong()
    private val sent = AtomicLong()

    fun stage(value: ConnectionStage) = update { if (it.details.stage == value) it else it.copy(details = it.details.copy(stage = value)) }
    fun tcpState(value: String) = update { if (it.details.tcpState == value) it else it.copy(details = it.details.copy(tcpState = value)) }
    fun clientBytes(count: Int) { received.addAndGet(count.toLong()) }
    fun serverBytes(count: Int) { sent.addAndGet(count.toLong()) }

    fun detected(detection: ProtocolDetection) = update { current ->
        val tls = detection.metadata.filterIsInstance<TlsClientHello>().firstOrNull()
        current.copy(details = current.details.copy(
            protocol = if (detection.http1) "http/1.1" else
                detection.metadata.firstOrNull()?.protocolId?.value ?: current.details.protocol,
            host = tls?.serverName,
            hostSource = if (tls?.serverName != null) HostSource.TLS_SNI else null
        ))
    }

    fun httpRequest(method: String, url: String) {
        val uri = URI(url)
        update { current -> State(current.details.copy(
            protocol = "http/1.1", host = uri.host, hostSource = HostSource.HTTP_AUTHORITY,
            httpMethod = method, httpPath = uri.rawPath?.ifEmpty { "/" } ?: "/", httpFlowId = null,
            upstream = null, resolvedUpstream = null, stage = ConnectionStage.PROCESSING
        )) }
    }

    fun httpFlow(id: Long) = update { it.copy(details = it.details.copy(httpFlowId = id)) }

    /** A late callback from an older HTTP transaction must not overwrite the current transaction. */
    fun beginUpstream(destination: Endpoint): (Endpoint?) -> Unit {
        val attempt = Any()
        update { it.copy(attempt = attempt, details = it.details.copy(
            upstream = destination, resolvedUpstream = null, stage = ConnectionStage.CONNECTING
        )) }
        return { resolved -> update { current ->
            if (current.attempt !== attempt) current else current.copy(details = current.details.copy(
                resolvedUpstream = resolved, stage = ConnectionStage.FORWARDING
            ))
        } }
    }

    fun snapshot(): ConnectionDiagnostics = state.get().details.copy(
        dnsCandidates = Collections.unmodifiableList(ArrayList(dnsCandidates())),
        durationMillis = ((clock.now() - started) / 1_000_000L).coerceAtLeast(0),
        bytesFromClient = received.get(), bytesToClient = sent.get()
    )

    private inline fun update(change: (State) -> State) {
        while (true) {
            val previous = state.get()
            val next = change(previous)
            if (next === previous || state.compareAndSet(previous, next)) return
        }
    }
}
