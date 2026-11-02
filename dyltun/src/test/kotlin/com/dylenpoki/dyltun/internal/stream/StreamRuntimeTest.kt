package com.dylenpoki.dyltun.internal.stream

import com.dylenpoki.dyltun.ConnectionInfo
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.ProtocolId
import com.dylenpoki.dyltun.ProtocolMetadata
import com.dylenpoki.dyltun.TransportProtocol
import com.dylenpoki.dyltun.dyltunConfig
import com.dylenpoki.dyltun.internal.inspection.ProtocolDetection
import com.dylenpoki.dyltun.internal.transport.TcpForwarder
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRuntimeTest {
    private val target = Endpoint("127.0.0.1", 1883)
    private val detection = ProtocolDetection(listOf(object : ProtocolMetadata {
        override val protocolId = ProtocolId("test-stream")
    }))

    @Test fun selectionUsesDetectionRegistrationOrderAndSnapshotWithRawFallback() {
        val calls = AtomicInteger()
        val first = handler { context ->
            calls.incrementAndGet()
            assertEquals(target, context.destination)
            assertEquals(80, context.connection.destinationPort)
            assertEquals(detection.metadata, context.detection.metadata)
            context.sink.write("selected".toByteArray())
        }
        val second = handler { error("A later matching handler replaced the first") }
        val handlers = mutableListOf(first, second)
        Fixture(handlers).use { fixture ->
            handlers.clear()
            val selected = fixture.open()
            await { selected.completed }
            assertEquals("selected", drain(selected).toString(Charsets.UTF_8))
            assertEquals(1, calls.get())
            val raw = fixture.runtime.open(info(2), target, ProtocolDetection(emptyList()))
            assertTrue(raw is RawStreamSession)
            raw.close()
        }
    }

    @Test fun inputAndOutputBackpressureRetainChunksUntilCommitted() {
        val entered = CountDownLatch(1)
        val readGate = CompletableDeferred<Unit>()
        val fifthWrite = CountDownLatch(1)
        val written = AtomicInteger()
        val processor = handler { context ->
            entered.countDown()
            readGate.await()
            repeat(4) { assertArrayEquals(byteArrayOf(it.toByte()), context.source.read()) }
            repeat(5) {
                if (it == 4) fifthWrite.countDown()
                context.sink.write(byteArrayOf(it.toByte()))
                written.incrementAndGet()
            }
        }
        Fixture(listOf(processor)).use { fixture ->
            val session = fixture.open()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            repeat(4) { assertTrue(session.accept(byteArrayOf(it.toByte()))) }
            assertFalse(session.accept(byteArrayOf(99)))
            readGate.complete(Unit)
            assertTrue(fifthWrite.await(3, TimeUnit.SECONDS))
            assertEquals(4, written.get())
            assertFalse(session.completed)
            val pending = session.peekOutput()
            assertNotNull(pending)
            assertSame(pending, session.peekOutput())
            session.commitOutput()
            await { session.completed }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), drain(session))
            assertEquals(4, session.takeConsumed())
            assertEquals(0, session.takeConsumed())
            assertEquals(5, written.get())
        }
    }

    @Test fun outputHalfCloseKeepsInputAliveUntilClientEof() {
        val received = CompletableDeferred<ByteArray>()
        val processor = handler { context ->
            context.sink.write(byteArrayOf(7))
            context.finishOutput()
            received.complete(checkNotNull(context.source.read()))
            assertNull(context.source.read())
        }
        Fixture(listOf(processor)).use { fixture ->
            val session = fixture.open()
            await { session.peekOutput() != null }
            assertArrayEquals(byteArrayOf(7), session.peekOutput())
            session.commitOutput()
            await { session.peekOutput(); session.outputDrained }
            assertFalse(session.completed)
            assertTrue(session.accept(byteArrayOf(8, 9)))
            session.finishInput()
            await { session.completed }
            assertArrayEquals(byteArrayOf(8, 9), runBlocking { received.await() })
            assertEquals(2, session.takeConsumed())
            assertNull(session.failure)
        }
    }

    @Test fun failureClosesOwnedPeersPreservesCleanupCauseAndDoesNotCancelSibling() {
        val closes = AtomicInteger()
        val original = IOException("processor failed")
        val cleanup = IOException("peer cleanup failed")
        val processor = handler { context ->
            if (context.connection.id == 1L) {
                context.upstream.open(target) // Deliberately omit use/close to verify runtime ownership.
                throw original
            }
            context.sink.write(checkNotNull(context.source.read()))
        }
        val upstream = StreamConnector { peer { closes.incrementAndGet(); throw cleanup } }
        Fixture(listOf(processor), upstream).use { fixture ->
            val failed = fixture.open()
            val sibling = fixture.open(2)
            await { failed.completed }
            assertTrue(generateSequence(failed.failure) { it.cause }.any { it === original })
            assertTrue(checkNotNull(failed.failure).suppressed.contains(cleanup))
            assertEquals(1, closes.get())
            assertTrue(sibling.accept("alive".toByteArray()))
            await { sibling.completed }
            assertEquals("alive", drain(sibling).toString(Charsets.UTF_8))
            assertNull(sibling.failure)
            failed.close()
            assertEquals(1, closes.get())
        }
    }

    @Test fun normalReturnClosesEveryForgottenUpstreamPeerExactlyOnce() {
        val closes = AtomicInteger()
        val processor = handler { context ->
            context.upstream.open(target).close()
            context.upstream.open(target)
            context.upstream.open(target)
        }
        Fixture(listOf(processor), StreamConnector { peer { closes.incrementAndGet() } }).use { fixture ->
            val session = fixture.open()
            await { session.completed }
            assertEquals(3, closes.get())
            session.close(); session.close()
            assertEquals(3, closes.get())
        }
    }

    @Test fun stopCancelsBlockedIoAndJoinsProcessorChildren() {
        val started = CountDownLatch(2)
        val childStarted = CountDownLatch(2)
        val childStopped = CountDownLatch(2)
        val processor = handler { context ->
            coroutineScope {
                launch { try { childStarted.countDown(); awaitCancellation() } finally { childStopped.countDown() } }
                if (context.connection.id == 1L) {
                    started.countDown()
                    context.source.read()
                } else {
                    repeat(4) { context.sink.write(byteArrayOf(1)) }
                    started.countDown()
                    context.sink.write(byteArrayOf(2))
                }
            }
        }
        Fixture(listOf(processor)).use { fixture ->
            val reading = fixture.open()
            val writing = fixture.open(2)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertTrue(childStarted.await(3, TimeUnit.SECONDS))
            fixture.runtime.close()
            assertEquals(0L, childStopped.count)
            assertTrue(reading.completed)
            assertTrue(writing.completed)
            assertNull(reading.failure)
            assertNull(writing.failure)
            assertThrows(IllegalStateException::class.java) { fixture.open(3) }
        }
    }

    @Test fun stopClosesNetworkBeforeNonCancellableProcessorReturnsAndDiscardsLateOutput() {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val closes = AtomicInteger()
        val processor = handler { context ->
            context.upstream.open(target)
            withContext(NonCancellable) { entered.countDown(); release.await() }
            context.sink.write("late".toByteArray())
        }
        Fixture(listOf(processor), StreamConnector { peer { closes.incrementAndGet() } }).use { fixture ->
            val session = fixture.open()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                fixture.runtime.requestStop()
                assertEquals(1, closes.get())
            } finally { release.complete(Unit) }
            fixture.runtime.close()
            assertNull(session.peekOutput())
            assertEquals(1, closes.get())
        }
    }

    @Test fun cancelledNonCancellableTasksKeepTheirCapacityUntilTheyReturn() {
        val entered = CountDownLatch(64)
        val release = CompletableDeferred<Unit>()
        val processor = handler {
            withContext(NonCancellable) { entered.countDown(); release.await() }
        }
        Fixture(listOf(processor)).use { fixture ->
            val sessions = (1L..64L).map { fixture.open(it) }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                sessions.forEach { it.close() }
                assertThrows(IllegalStateException::class.java) { fixture.open(65) }
            } finally { release.complete(Unit) }
            await { sessions.all { it.completed } }
            // Task completion releases cancelled slots; subsequent work can start.
            await {
                try { fixture.open(65).also { it.close() }; true }
                catch (_: IllegalStateException) { false }
            }
        }
    }

    @Test fun stopAndCloseFailuresDoNotSkipOtherHandlersOrRepeatCleanup() {
        val failure = IOException("stop")
        val cleanup = IOException("cleanup")
        val stops = AtomicInteger()
        val closes = AtomicInteger()
        val faulty = object : StreamHandler {
            override fun matches(detection: ProtocolDetection) = false
            override suspend fun handle(context: StreamContext) {}
            override fun requestStop() { throw failure }
            override fun close() { throw cleanup }
        }
        val healthy = object : StreamHandler {
            override fun matches(detection: ProtocolDetection) = false
            override suspend fun handle(context: StreamContext) {}
            override fun requestStop() { stops.incrementAndGet() }
            override fun close() { closes.incrementAndGet() }
        }
        Fixture(listOf(faulty, healthy)).use { fixture ->
            assertSame(failure, assertThrows(IOException::class.java) { fixture.runtime.close() })
            assertTrue(failure.suppressed.contains(cleanup))
            fixture.runtime.close()
            assertEquals(1, stops.get())
            assertEquals(1, closes.get())
        }
    }

    private fun handler(block: suspend (StreamContext) -> Unit): StreamHandler = object : StreamHandler {
        override fun matches(detection: ProtocolDetection): Boolean = detection.metadata.any { it.protocolId.value == "test-stream" }
        override suspend fun handle(context: StreamContext) { block(context) }
    }

    private fun peer(close: () -> Unit): StreamPeer = object : StreamPeer {
        override val source = ByteSource { null }
        override val sink = ByteSink {}
        override fun finish() {}
        override fun close() { close.invoke() }
    }

    private fun info(id: Long): ConnectionInfo = ConnectionInfo(id, TransportProtocol.TCP,
        InetAddress.getByName("10.0.0.2"), 12345, InetAddress.getByName("198.51.100.1"), 80)

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(3000) { while (!condition()) delay(1) }
    }

    private fun drain(session: StreamSession): ByteArray {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val chunk = session.peekOutput() ?: break
            bytes += chunk.toList()
            session.commitOutput()
        }
        return bytes.toByteArray()
    }

    private inner class Fixture(
        handlers: List<StreamHandler>, upstream: StreamConnector = StreamConnector { error("Unexpected upstream") }
    ) : Closeable {
        private val forwarder = TcpForwarder(
            com.dylenpoki.dyltun.internal.android.StreamConnector { error("Unexpected transport connection") }, {}, { throw it })
        val runtime = StreamRuntime(dyltunConfig().protocols, forwarder, {}, { _, _ -> }, handlers, upstream)
        fun open(id: Long = 1): StreamSession = runtime.open(info(id), target, detection)
        override fun close() { try { runtime.close() } finally { forwarder.close() } }
    }
}
