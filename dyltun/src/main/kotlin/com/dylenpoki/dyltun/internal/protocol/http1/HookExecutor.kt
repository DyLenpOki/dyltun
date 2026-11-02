package com.dylenpoki.dyltun.internal.protocol.http1

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Bounded callback concurrency and backlog. Cancellation discards results; user code is never force-stopped. */
internal class HookExecutor : Closeable {
    private val executor = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "dyltun-http-hook").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    suspend fun execute(block: () -> Unit) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        // Keep the transaction's bodies owned until a running callback actually returns.
        withContext(NonCancellable) {
            suspendCoroutine<Unit> { continuation ->
                val task = Runnable {
                    try {
                        if (caller.isActive) block()
                        continuation.resume(Unit)
                    } catch (error: Throwable) { continuation.resumeWithException(error) }
                }
                try { executor.execute(task) } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        }
        caller.ensureActive()
    }

    fun requestStop() { executor.shutdown() }
    override fun close() {
        requestStop()
        var interrupted = false
        while (!executor.isTerminated) try { executor.awaitTermination(1, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
