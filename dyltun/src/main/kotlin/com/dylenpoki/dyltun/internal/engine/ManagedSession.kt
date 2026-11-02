package com.dylenpoki.dyltun.internal.engine

import com.dylenpoki.dyltun.DyltunEvent
import com.dylenpoki.dyltun.DyltunSession
import com.dylenpoki.dyltun.DyltunState
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface SessionRuntime : Closeable {
    fun run(onReady: () -> Unit)
    fun requestStop()
}

internal class ManagedSession(
    private val descriptor: Closeable,
    private val createRuntime: ((DyltunEvent) -> Unit) -> SessionRuntime
) : DyltunSession {
    private val mutableState = MutableStateFlow<DyltunState>(DyltunState.Starting)
    private val mutableEvents = MutableSharedFlow<DyltunEvent>(16, 48, BufferOverflow.DROP_OLDEST)
    override val state = mutableState.asStateFlow()
    override val events = mutableEvents.asSharedFlow()
    private val stopped = AtomicBoolean()
    private val runtime = AtomicReference<SessionRuntime?>()
    private val completed = CompletableDeferred<Unit>()
    private val stopFailure = AtomicReference<Throwable?>()
    private val lock = Any()
    private val worker = Thread(::execute, "dyltun-packets").apply { isDaemon = true }

    fun start() { worker.start() }

    private fun execute() {
        var failure: Throwable? = null
        try {
            if (!stopped.get()) {
                val current = createRuntime { mutableEvents.tryEmit(it) }
                runtime.set(current)
                if (!stopped.get()) current.run {
                    synchronized(lock) {
                        if (!stopped.get()) mutableState.value = DyltunState.Running
                    }
                }
            }
        } catch (error: Throwable) {
            if (!stopped.get()) failure = error
        } finally {
            for (resource in listOfNotNull(runtime.get(), descriptor)) {
                try { resource.close() } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
                }
            }
            synchronized(lock) {
                stopFailure.get()?.let { error ->
                    val previous = failure
                    if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
                }
                mutableState.value = failure?.let { DyltunState.Failed(it) } ?: DyltunState.Stopped
            }
            completed.complete(Unit)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (stopped.compareAndSet(false, true)) {
                if (mutableState.value == DyltunState.Starting || mutableState.value == DyltunState.Running) {
                    mutableState.value = DyltunState.Stopping
                    try { runtime.get()?.requestStop() } catch (error: Throwable) {
                        stopFailure.compareAndSet(null, error)
                    } finally { worker.interrupt() }
                }
            }
        }
    }

    override suspend fun awaitClosed() { completed.await() }
}
