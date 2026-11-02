package com.dylenpoki.dyltun.internal.stream

import com.dylenpoki.dyltun.internal.transport.TcpForwarder
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps raw TCP on its direct path: upstream read credit is released only by client ACKs. */
internal class RawStreamSession(
    private val upstream: TcpForwarder.Handle,
    private val onClose: (StreamSession) -> Unit
) : StreamSession {
    private val closed = AtomicBoolean()
    private var pending: ByteArray? = null
    override val failure: Throwable? get() = upstream.failure.get()
    override val completed: Boolean get() = false
    override val outputDrained: Boolean get() = pending == null && upstream.drained()
    override fun accept(bytes: ByteArray): Boolean = upstream.send(bytes)
    override fun finishInput() { upstream.finish() }
    override fun takeConsumed(): Int = upstream.takeWritten()
    override fun peekOutput(): ByteArray? {
        if (pending == null) pending = upstream.poll()
        return pending
    }
    override fun commitOutput() { pending = null }
    override fun acknowledgeOutput(bytes: Int) { upstream.acknowledge(bytes) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { upstream.close() } finally { onClose(this) }
    }
}
