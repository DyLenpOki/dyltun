package com.dylenpoki.dyltun.internal.stack

/** Single-owner bounded stream buffer. Storage is allocated only when data arrives. */
internal class ByteRing(private val capacity: Int) {
    private var bytes: ByteArray? = null
    private var head = 0
    var size = 0
        private set

    fun append(source: ByteArray, offset: Int = 0, length: Int = source.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= source.size)
        check(length <= capacity - size)
        if (length == 0) return
        val storage = bytes ?: ByteArray(capacity).also { bytes = it }
        val tail = (head + size) % capacity
        val first = minOf(length, capacity - tail)
        source.copyInto(storage, tail, offset, offset + first)
        source.copyInto(storage, 0, offset + first, offset + length)
        size += length
    }

    fun peek(maximum: Int): ByteArray {
        require(maximum >= 0)
        val length = minOf(size, maximum)
        val result = ByteArray(length)
        if (length == 0) return result
        val first = minOf(length, capacity - head)
        bytes!!.copyInto(result, 0, head, head + first)
        bytes!!.copyInto(result, first, 0, length - first)
        return result
    }

    fun discard(length: Int) {
        require(length in 0..size)
        head = (head + length) % capacity
        size -= length
    }

    fun clear() { bytes = null; head = 0; size = 0 }
}
