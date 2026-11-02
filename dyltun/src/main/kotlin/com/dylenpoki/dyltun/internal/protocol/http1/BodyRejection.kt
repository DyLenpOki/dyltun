package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.BodyLimitExceededException
import java.io.IOException

internal enum class BodyRejectionReason { ENCODED_SIZE, DECODED_SIZE, BUDGET, TIMEOUT }

/** Only incoming-body aggregation uses this type. Hook/local-response contract failures still abort. */
internal class BodyRejection(val reason: BodyRejectionReason, message: String) :
    IOException(message, BodyLimitExceededException(message)) {
    fun status(request: Boolean): Int = when (reason) {
        BodyRejectionReason.ENCODED_SIZE, BodyRejectionReason.DECODED_SIZE -> if (request) 413 else 502
        BodyRejectionReason.BUDGET -> 503
        BodyRejectionReason.TIMEOUT -> if (request) 408 else 504
    }
}
