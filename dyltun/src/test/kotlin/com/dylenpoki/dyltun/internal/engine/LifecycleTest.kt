package com.dylenpoki.dyltun.internal.engine

import com.dylenpoki.dyltun.DyltunState
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleTest {
    private fun await(session: ManagedSession) = runBlocking { withTimeout(3000) { session.awaitClosed() } }

    @Test fun repeatedExceptionInstanceDuringCleanupCannotPreventCompletion() {
        val same = IOException("same failure")
        val session = ManagedSession(Closeable { throw same }) {
            object : SessionRuntime {
                override fun run(onReady: () -> Unit) { throw same }
                override fun requestStop() {}
                override fun close() { throw same }
            }
        }
        session.start(); await(session)
        assertSame(same, (session.state.value as DyltunState.Failed).cause)
    }

    @Test fun startupFailureClosesOwnedDescriptorAndPreservesCause() {
        val closes = AtomicInteger()
        val failure = IOException("startup failed")
        val session = ManagedSession(Closeable { closes.incrementAndGet() }) { throw failure }
        session.start()
        await(session)
        assertSame(failure, (session.state.value as DyltunState.Failed).cause)
        session.close(); session.close()
        assertEquals(1, closes.get())
    }

    @Test fun closeBeforeStartSkipsFactoryAndIsIdempotent() {
        val closes = AtomicInteger()
        val session = ManagedSession(Closeable { closes.incrementAndGet() }) { error("Must not initialize after close") }
        session.close(); session.close(); session.start()
        await(session)
        assertEquals(DyltunState.Stopped, session.state.value)
        assertEquals(1, closes.get())
    }

    @Test fun runningSessionStopsAndReleasesRuntimeOnce() {
        val ready = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val closes = AtomicInteger()
        val session = ManagedSession(Closeable {}) {
            object : SessionRuntime {
                override fun run(onReady: () -> Unit) { onReady(); ready.countDown(); stop.await() }
                override fun requestStop() { stop.countDown() }
                override fun close() { closes.incrementAndGet() }
            }
        }
        session.start()
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertEquals(DyltunState.Running, session.state.value)
        } finally { session.close(); await(session) }
        assertEquals(DyltunState.Stopped, session.state.value)
        assertEquals(1, closes.get())
    }

    @Test fun stopFailureStillInterruptsAndCleanupErrorsAreNotLost() {
        val ready = CountDownLatch(1)
        val stopFailure = IOException("stop")
        val cleanupFailure = IOException("cleanup")
        val session = ManagedSession(Closeable { throw cleanupFailure }) {
            object : SessionRuntime {
                override fun run(onReady: () -> Unit) { onReady(); ready.countDown(); CountDownLatch(1).await() }
                override fun requestStop() { throw stopFailure }
                override fun close() {}
            }
        }
        session.start()
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        session.close()
        await(session)
        val error = (session.state.value as DyltunState.Failed).cause
        assertSame(cleanupFailure, error)
        assertTrue(error.suppressed.contains(stopFailure))
    }

    @Test fun normalFailureRetainsBothCleanupErrors() {
        val original = IOException("run")
        val session = ManagedSession(Closeable { throw IOException("descriptor") }) {
            object : SessionRuntime {
                override fun run(onReady: () -> Unit) { throw original }
                override fun requestStop() {}
                override fun close() { throw IOException("runtime") }
            }
        }
        session.start(); await(session)
        assertSame(original, (session.state.value as DyltunState.Failed).cause)
        assertEquals(listOf("runtime", "descriptor"), original.suppressed.map { it.message })
    }

    @Test fun memoryBudgetCannotOverflowAndLeaseCloseIsIdempotent() {
        val budget = MemoryBudget(Long.MAX_VALUE)
        val lease = budget.acquire(Long.MAX_VALUE)!!
        assertNull(budget.acquire(1))
        lease.close(); lease.close()
        assertEquals(0, budget.used)
        assertThrows(IllegalArgumentException::class.java) { budget.acquire(-1) }
    }

    @Test fun concurrentBudgetUseNeverExceedsLimitOrLeaks() {
        val budget = MemoryBudget(20)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map {
                pool.submit {
                    repeat(1000) {
                        budget.acquire(5)?.use { assertTrue(budget.used in 0..20) }
                    }
                }
            }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(0, budget.used)
        } finally { pool.shutdownNow() }
    }
}
