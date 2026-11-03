package com.dylenpoki.dyltun.internal.protocol.http1

import com.dylenpoki.dyltun.internal.stream.ByteSink
import com.dylenpoki.dyltun.internal.stream.ByteSource
import com.dylenpoki.dyltun.internal.stream.StreamConnector
import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.DyltunConfig
import com.dylenpoki.dyltun.ProtocolMetadata
import com.dylenpoki.dyltun.internal.diagnostics.ConnectionTrace
import com.dylenpoki.dyltun.internal.engine.MemoryBudget
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal class HttpClient(
    val input: HttpInput,
    val sink: ByteSink,
    val finishOutput: () -> Unit
)

/** Owns the ordered connection loop and each transaction's resource lifetime. */
internal class Http1Connection(
    private val config: DyltunConfig,
    private val connection: ConnectionInfo,
    private val metadata: List<ProtocolMetadata>,
    private val budget: MemoryBudget,
    private val executor: HookExecutor,
    private val connector: StreamConnector,
    clientSource: ByteSource,
    clientSink: ByteSink,
    finishOutput: () -> Unit = {},
    private val flowIds: AtomicLong = AtomicLong(),
    private val report: (Throwable) -> Unit = {},
    private val trace: ConnectionTrace? = null
) {
    private val client = HttpClient(HttpInput(clientSource), clientSink, finishOutput)
    private val responses = HttpResponseWriter(config.bodyLimits.response, checkNotNull(config.hooks), executor, clientSink)

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val head = HttpFraming.request(client.input) ?: return
            trace?.httpRequest(head.method, head.url)
            when (exchange(head)) {
                ExchangeResult.KEEP_ALIVE -> Unit
                ExchangeResult.CLOSE, ExchangeResult.UPGRADED -> return
            }
        }
    }

    private suspend fun exchange(head: RequestHead): ExchangeResult = coroutineScope {
        BodyAccount(budget).use { account ->
            try {
                HttpExchange(config, connection, metadata, executor, connector, client, responses, flowIds, report, trace)
                    .run(head, account, this)
            } finally {
                coroutineContext.cancelChildren()
            }
        }
    }
}
