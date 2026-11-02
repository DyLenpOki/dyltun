package com.dylenpoki.dyltun.internal.stream

import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.internal.inspection.ProtocolDetection
import java.io.Closeable

/** Session-scoped instance, shared across connections. Matching must be deterministic and free of I/O. */
internal interface StreamHandler : Closeable {
    val probeHttp1: Boolean get() = false
    fun matches(detection: ProtocolDetection): Boolean
    suspend fun handle(context: StreamContext)
    fun requestStop() {}
    override fun close() {}
}

/**
 * One ordered client stream. Its source and sink support concurrent reading/writing, one coroutine per direction.
 * Handlers keep children in their caller's structured scope; all opened peers belong to this connection.
 * finishOutput ends only the client-facing output; returning from handle ends the whole processor.
 */
internal class StreamContext(
    val connection: ConnectionInfo,
    val destination: Endpoint,
    val detection: ProtocolDetection,
    val source: ByteSource,
    val sink: ByteSink,
    val finishOutput: () -> Unit,
    val upstream: StreamConnector,
    val report: (Throwable) -> Unit
)

/** Packet-loop port. All methods are nonblocking; only the runtime task uses suspend I/O. */
internal interface StreamSession : Closeable {
    val failure: Throwable?
    val completed: Boolean
    val outputDrained: Boolean
    fun accept(bytes: ByteArray): Boolean
    fun finishInput()
    /** Upload bytes consumed since the last call; the packet loop releases their TCP receive credit. */
    fun takeConsumed(): Int
    fun peekOutput(): ByteArray?
    fun commitOutput()
    /** Downstream bytes acknowledged by the application. Raw forwarding returns upstream read credit here. */
    fun acknowledgeOutput(bytes: Int)
}
