package com.dylenpoki.dyltun.internal.engine

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class MemoryBudget(val limit: Long) {
    init { require(limit > 0) }

    @Volatile var used: Long = 0
        private set

    @Synchronized
    fun acquire(bytes: Long): Lease? {
        require(bytes >= 0)
        if (bytes > limit - used) return null
        used += bytes
        return Lease(this, bytes)
    }

    @Synchronized
    private fun release(bytes: Long) {
        check(bytes <= used)
        used -= bytes
    }

    class Lease internal constructor(private val budget: MemoryBudget, bytes: Long) : Closeable {
        var bytes: Long = bytes
            private set
        private val closed = AtomicBoolean()
        @Synchronized fun shrinkTo(value: Long) {
            check(!closed.get())
            require(value in 0..bytes)
            budget.release(bytes - value)
            bytes = value
        }
        @Synchronized override fun close() {
            if (closed.compareAndSet(false, true)) budget.release(bytes)
        }
    }
}
