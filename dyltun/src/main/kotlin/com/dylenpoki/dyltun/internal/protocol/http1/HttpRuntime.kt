package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.ProtocolMetadata
import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import com.dylenpoki.dyltun.internal.transport.TcpForwarder
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Session-owned coroutine processors. TCP state remains exclusively on the packet loop. */
internal class HttpRuntime(
    private val config: DyltunConfig,
    forwarder: TcpForwarder,
    private val wakeup: () -> Unit,
    private val report: (ConnectionInfo, Throwable) -> Unit
) : Closeable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val hooks = HookExecutor()
    private val connector = ForwarderHttpConnector(forwarder)
    private val budget = MemoryBudget(config.bodyLimits.maxTotalBufferedBytes)
    private val flows = Collections.newSetFromMap(ConcurrentHashMap<Bridge, Boolean>())
    private val ids = AtomicLong()

    inner class Bridge internal constructor(connection: ConnectionInfo, metadata: List<ProtocolMetadata>) : Closeable {
        private val input = Channel<ByteArray>(4)
        private val output = Channel<ByteArray>(4)
        private val consumed = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        @Volatile var completed = false
            private set
        private var pending: ByteArray? = null // Packet-loop owner only.
        var outputDrained = false
            private set
        private val processor = scope.launch(start = CoroutineStart.LAZY) {
            try {
                Http1Connection(config, connection, metadata, budget, hooks, connector,
                    ByteSource { input.receiveCatching().getOrNull()?.also { consumed.addAndGet(it.size); wakeup() } },
                    ByteSink { bytes -> output.send(bytes); wakeup() },
                    { output.close(); wakeup() }, ids, { error -> report(connection, error) }).run()
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { completed = true; input.cancel(); output.close(); flows.remove(this@Bridge); wakeup() }
        }
        internal fun start() { processor.start() }
        fun accept(bytes: ByteArray): Boolean = !completed && input.trySend(bytes).isSuccess
        fun finishInput() { input.close() }
        fun takeConsumed(): Int = consumed.getAndSet(0)
        fun peekOutput(): ByteArray? {
            if (pending == null) {
                val result = output.tryReceive()
                pending = result.getOrNull()
                if (result.isClosed) outputDrained = true
            }
            return pending
        }
        fun commitOutput() { pending = null }
        override fun close() { input.cancel(); output.cancel(); processor.cancel() }
    }

    fun open(connection: ConnectionInfo, metadata: List<ProtocolMetadata>): Bridge {
        check(job.isActive)
        check(flows.size < 64) { "HTTP connection limit reached" }
        val bridge = Bridge(connection, metadata)
        flows += bridge
        bridge.start()
        return bridge
    }

    fun requestStop() { job.cancel(); flows.forEach { it.close() }; hooks.requestStop() }
    override fun close() {
        requestStop()
        // A synchronous user Hook may delay this join; the network has already been stopped.
        var interrupted = false
        while (true) try { runBlocking { job.join() }; break }
        catch (_: InterruptedException) { interrupted = true }
        hooks.close()
        if (interrupted) Thread.currentThread().interrupt()
    }
}
