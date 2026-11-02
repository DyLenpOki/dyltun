package com.dylenpoki.dyltun.internal.stack

internal fun interface MonotonicClock {
    fun now(): Long
    companion object { val SYSTEM = MonotonicClock(System::nanoTime) }
}

internal fun unwrapSequence(wire: Long, reference: Long): Long = reference + (wire - reference).toInt()
internal fun wireSequence(absolute: Long): Long = absolute and 0xffff_ffffL
