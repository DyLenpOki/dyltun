package com.dylenpoki.dyltun.internal.inspection

import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.InspectionContext
import com.dylenpoki.dyltun.InspectionResult
import com.dylenpoki.dyltun.ProtocolConfig
import com.dylenpoki.dyltun.ProtocolMetadata
import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import com.dylenpoki.dyltun.isHttpToken
import java.nio.ByteBuffer
import java.util.Collections

/** The endpoint retains the original prefix; probing never consumes stream bytes. */
internal class ProtocolProbe(
    config: ProtocolConfig,
    connection: ConnectionInfo,
    private val clock: MonotonicClock,
    includeHttp: Boolean = false
) {
    private val pending = config.inspectors.toMutableList()
    private val context = InspectionContext(connection)
    private val found = mutableListOf<ProtocolMetadata>()
    private val started = clock.now()
    private var inspectedBytes = -1
    private var httpPending = includeHttp
    var httpMatched = false
        private set
    val metadata: List<ProtocolMetadata> get() = Collections.unmodifiableList(ArrayList(found))

    fun advance(prefix: ByteArray, endOfInput: Boolean): Boolean {
        require(prefix.size <= MAX_PREFIX)
        if (prefix.size != inspectedBytes) {
            if (httpPending) {
                val lineEnd = prefix.indices.firstOrNull { it > 0 && prefix[it - 1] == 13.toByte() && prefix[it] == 10.toByte() }
                val lineLength = lineEnd?.let { it - 1 } ?: prefix.size
                if ((0 until lineLength).any { prefix[it].toInt() and 255 !in 32..126 && prefix[it] != 13.toByte() } ||
                    lineLength > 8192) httpPending = false
                else if (lineEnd != null) {
                    val parts = prefix.copyOfRange(0, lineEnd - 1).toString(Charsets.US_ASCII).split(' ')
                    httpMatched = parts.size == 3 && isHttpToken(parts[0]) && parts[0] != "CONNECT" && parts[2] == "HTTP/1.1"
                    httpPending = false
                }
            }
            val input = ByteBuffer.wrap(prefix).asReadOnlyBuffer()
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val inspector = iterator.next()
                when (val result = inspector.inspect(context, input.asReadOnlyBuffer())) {
                    InspectionResult.NeedMore -> Unit
                    InspectionResult.NoMatch -> iterator.remove()
                    is InspectionResult.Matched -> {
                        check(result.metadata.protocolId == inspector.protocolId) { "Inspector returned another protocol's metadata" }
                        found += result.metadata
                        iterator.remove()
                    }
                }
            }
            inspectedBytes = prefix.size
        }
        return pending.isEmpty() && !httpPending || prefix.size == MAX_PREFIX || endOfInput || clock.now() - started >= 1_000_000_000L
    }

    companion object { const val MAX_PREFIX = 65535 }
}
