package com.dylenpoki.dyltun.internal.stream

import com.dylenpoki.dyltun.Endpoint
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedStreamConnectorTest {
    private val target = Endpoint("127.0.0.1", 1234)

    @Test fun closeRejectsAndClosesPeerWhoseOpenWasAlreadyInFlight() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val connector = OwnedStreamConnector(StreamConnector {
            opens.incrementAndGet()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            peer { closes.incrementAndGet() }
        })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val opening = worker.submit<StreamPeer> { connector.open(target) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            connector.close()
            release.countDown()
            val thrown = assertThrows(ExecutionException::class.java) { opening.get(3, TimeUnit.SECONDS) }
            assertTrue(thrown.cause is IOException)
            assertEquals(1, closes.get())
            assertThrows(IOException::class.java) { connector.open(target) }
            assertEquals(1, opens.get())
            connector.close()
            assertEquals(1, closes.get())
        } finally {
            release.countDown()
            connector.close()
            worker.shutdownNow()
        }
    }

    @Test fun peerCleanupFailuresDoNotSkipPeersOrSelfSuppressRepeatedException() {
        val same = IOException("same cleanup failure")
        val closes = AtomicInteger()
        val connector = OwnedStreamConnector(StreamConnector { peer { closes.incrementAndGet(); throw same } })
        repeat(3) { connector.open(target) }
        assertSame(same, assertThrows(IOException::class.java) { connector.close() })
        assertEquals(3, closes.get())
        assertTrue(same.suppressed.isEmpty())
        connector.close()
        assertEquals(3, closes.get())
    }

    private fun peer(close: () -> Unit): StreamPeer = object : StreamPeer {
        override val source = ByteSource { null }
        override val sink = ByteSink {}
        override fun finish() {}
        override fun close() { close.invoke() }
    }
}
