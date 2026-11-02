package com.dylenpoki.dyltun

enum class BodyOverflow { STREAM, REJECT }

data class BodyLimit(
    val maxBufferedBytes: Long,
    val maxDecodedBytes: Long,
    val maxBufferingMillis: Long = 2_000,
    val overflow: BodyOverflow = BodyOverflow.STREAM
) {
    init {
        require(maxBufferedBytes in 1..Int.MAX_VALUE.toLong()) { "Invalid maxBufferedBytes" }
        require(maxDecodedBytes in 1..Int.MAX_VALUE.toLong()) { "Invalid maxDecodedBytes" }
        require(maxBufferingMillis in 1..Long.MAX_VALUE / 1_000_000) { "Invalid maxBufferingMillis" }
    }
}

data class BodyLimits(
    val request: BodyLimit = BodyLimit(1L * 1024 * 1024, 4L * 1024 * 1024),
    val response: BodyLimit = BodyLimit(2L * 1024 * 1024, 8L * 1024 * 1024),
    val maxTotalBufferedBytes: Long = 32L * 1024 * 1024
) {
    init {
        require(maxTotalBufferedBytes > 0) { "maxTotalBufferedBytes must be positive" }
    }
}
