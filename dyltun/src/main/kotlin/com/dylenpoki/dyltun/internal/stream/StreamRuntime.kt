package com.dylenpoki.dyltun.internal.stream

import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.ProtocolConfig
import com.dylenpoki.dyltun.internal.inspection.ProtocolDetection
import com.dylenpoki.dyltun.internal.inspection.ProtocolProbe
import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import com.dylenpoki.dyltun.internal.transport.TcpForwarder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Selects processors and owns their tasks/resources. TCP state belongs to the packet loop. */
internal class StreamRuntime(
    private val protocols: ProtocolConfig,
    private val forwarder: TcpForwarder,
    private val wakeup: () -> Unit,
    private val report: (ConnectionInfo, Throwable) -> Unit,
    handlers: List<StreamHandler> = emptyList(),
    private val connector: StreamConnector = ForwarderStreamConnector(forwarder)
) : Closeable {
    private val handlers = handlers.toList()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val lock = Any()
    private val sessions = linkedSetOf<StreamSession>() // Registration, removal and snapshots share lock.
    private var stopped = false
    private var closed = false
    private val stopFailure = AtomicReference<Throwable?>()
    private val stopFinished = CompletableDeferred<Unit>()

    fun probe(connection: ConnectionInfo, clock: MonotonicClock): ProtocolProbe =
        ProtocolProbe(protocols, connection, clock, handlers.any { it.probeHttp1 })

    /** Registration order determines the first matching processor; unmatched traffic stays raw. */
    fun open(connection: ConnectionInfo, destination: Endpoint, detection: ProtocolDetection): StreamSession =
        synchronized(lock) {
            check(!stopped) { "Stream runtime is stopped" }
            check(sessions.size < 64) { "Stream connection limit reached" }
            val handler = handlers.firstOrNull { it.matches(detection) }
            val session = if (handler == null) {
                RawStreamSession(forwarder.open(connection.id, destination), ::release)
            } else ProcessedSession(connection, destination, detection, handler)
            sessions += session
            if (session is ProcessedSession) session.start()
            session
        }

    private fun release(session: StreamSession) { synchronized(lock) { sessions.remove(session) } }

    private inner class ProcessedSession(
        connection: ConnectionInfo, destination: Endpoint, detection: ProtocolDetection, handler: StreamHandler
    ) : StreamSession {
        private val input = Channel<ByteArray>(4)
        private val output = Channel<ByteArray>(4)
        private val consumed = AtomicInteger()
        private val error = AtomicReference<Throwable?>()
        private val upstream = OwnedStreamConnector(connector)
        private val closing = AtomicBoolean()
        private val entered = AtomicBoolean()
        private var pending: ByteArray? = null // Packet-loop owner only.
        override val failure: Throwable? get() = error.get()
        @Volatile override var completed = false
            private set
        override var outputDrained = false
            private set
        private val context = StreamContext(connection, destination, detection,
            ByteSource { input.receiveCatching().getOrNull()?.also { consumed.addAndGet(it.size); wakeup() } },
            ByteSink { bytes ->
                require(bytes.size <= CHUNK_BYTES) { "Stream output exceeded its chunk bound" }
                output.send(bytes)
                wakeup()
            },
            { output.close(); wakeup() }, upstream, { report(connection, it) })
        private val processor = scope.launch(start = CoroutineStart.LAZY) {
            entered.set(true)
            try { coroutineScope { handler.handle(context) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { recordFailure(failure) }
            finally { finish() }
        }.also { task ->
            task.invokeOnCompletion {
                // A task cancelled before dispatch never enters its try/finally.
                if (!entered.get()) finish()
                if (closing.get()) release(this)
            }
        }

        fun start() { processor.start() }
        override fun accept(bytes: ByteArray): Boolean {
            require(bytes.size <= CHUNK_BYTES)
            return !completed && input.trySend(bytes).isSuccess
        }
        override fun finishInput() { input.close() }
        override fun takeConsumed(): Int = consumed.getAndSet(0)
        override fun peekOutput(): ByteArray? {
            if (pending == null) {
                val result = output.tryReceive()
                pending = result.getOrNull()
                if (result.isClosed) outputDrained = true
            }
            return pending
        }
        override fun commitOutput() { pending = null }
        override fun acknowledgeOutput(bytes: Int) {}
        override fun close() {
            if (!closing.compareAndSet(false, true)) return
            input.cancel()
            output.cancel()
            processor.cancel()
            closeUpstream()
            // Non-cancellable work still owns its slot until its task really completes.
            if (processor.isCompleted) release(this)
        }

        private fun finish() {
            closeUpstream()
            completed = true
            input.cancel()
            output.close()
            wakeup()
        }
        private fun closeUpstream() {
            try { upstream.close() } catch (failure: Throwable) { recordFailure(failure) }
        }
        private fun recordFailure(failure: Throwable) {
            if (!error.compareAndSet(null, failure)) {
                val previous = error.get()
                if (previous !== failure) previous?.addSuppressed(failure)
            }
        }
    }

    fun requestStop() {
        val active = synchronized(lock) {
            if (stopped) return
            stopped = true
            sessions.toList()
        }
        try {
            job.cancel()
            for (session in active) stop { session.close() }
            for (handler in handlers) stop { handler.requestStop() }
        } finally { stopFinished.complete(Unit) }
    }

    private inline fun stop(action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            if (!stopFailure.compareAndSet(null, failure)) {
                val previous = stopFailure.get()
                if (previous !== failure) previous?.addSuppressed(failure)
            }
        }
    }

    override fun close() {
        requestStop()
        synchronized(lock) { if (closed) return; closed = true }
        var interrupted = false
        // A running synchronous Hook retains its bodies until it returns; network I/O is already stopped.
        while (true) try { runBlocking { stopFinished.await(); job.join() }; break }
        catch (_: InterruptedException) { interrupted = true }
        for (handler in handlers) stop { handler.close() }
        synchronized(lock) { sessions.clear() }
        if (interrupted) Thread.currentThread().interrupt()
        stopFailure.get()?.let { throw it }
    }
}
