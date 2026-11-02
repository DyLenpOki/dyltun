package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import com.dylenpoki.dyltun.internal.inspection.ProtocolDetection
import com.dylenpoki.dyltun.internal.stream.StreamContext
import com.dylenpoki.dyltun.internal.stream.StreamHandler
import java.util.concurrent.atomic.AtomicLong

/** HTTP owns message semantics and its budget; the stream runtime owns connection tasks and I/O. */
internal class HttpStreamHandler(private val config: DyltunConfig) : StreamHandler {
    private val hooks = HookExecutor()
    private val budget = MemoryBudget(config.bodyLimits.maxTotalBufferedBytes)
    private val ids = AtomicLong()
    override val probeHttp1 = true
    override fun matches(detection: ProtocolDetection): Boolean = detection.http1
    override suspend fun handle(context: StreamContext) {
        Http1Connection(config, context.connection, context.detection.metadata, budget, hooks, context.upstream,
            context.source, context.sink, context.finishOutput, ids, context.report).run()
    }
    override fun requestStop() { hooks.requestStop() }
    override fun close() { hooks.close() }
}
