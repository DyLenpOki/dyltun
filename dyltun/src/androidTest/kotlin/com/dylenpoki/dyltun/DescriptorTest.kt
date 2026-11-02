package com.dylenpoki.dyltun

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenpoki.dyltun.internal.android.TunDevice
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DescriptorTest {
    @Test fun packetReadWriteAndDeviceCloseDoNotCloseCallerDescriptor() = withPair { descriptor, peer ->
        TunDevice(descriptor).use { device ->
            val request = byteArrayOf(1, 2, 3)
            Os.write(peer.fileDescriptor, request, 0, request.size)
            val input = ByteArray(65535)
            assertEquals(3, device.read(input))
            assertArrayEquals(request, input.copyOf(3))
            device.write(byteArrayOf(4, 5))
            assertEquals(2, Os.read(peer.fileDescriptor, input, 0, input.size))
            assertArrayEquals(byteArrayOf(4, 5), input.copyOf(2))
        }
        assertTrue(descriptor.fileDescriptor.valid())
    }

    @Test fun wakeupAndStopUnblockPendingRead() = withPair { descriptor, _ ->
        TunDevice(descriptor).use { device ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val started = CountDownLatch(1)
                val read = worker.submit<Int> { started.countDown(); device.read(ByteArray(1500)) }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                device.wakeup()
                assertEquals(0, read.get(1, TimeUnit.SECONDS).toInt())
                device.requestStop()
                assertEquals(-1, device.read(ByteArray(1500)))
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun stopInterruptsBackpressuredPacketWrite() = withPair { descriptor, _ ->
        TunDevice(descriptor).use { device ->
            val bytes = ByteArray(1500)
            var full = false
            for (index in 0 until 10000) {
                try { Os.write(descriptor.fileDescriptor, bytes, 0, bytes.size) }
                catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN) throw error
                    full = true
                    break
                }
            }
            assertTrue(full)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val started = CountDownLatch(1)
                val write = worker.submit<Boolean> {
                    started.countDown()
                    try { device.write(bytes); false } catch (_: IOException) { true }
                }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                device.requestStop()
                assertTrue(write.get(1, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun invalidConfigurationDoesNotTransferFdOwnership() = withPair { descriptor, _ ->
        assertThrows(IllegalArgumentException::class.java) {
            VpnService().dyltun(descriptor) { routing { mqtt { forward() } } }
        }
        assertTrue(descriptor.fileDescriptor.valid())
    }

    @Test fun hooksConfigurationStartsAndClosesOwnedFd() = withPair { descriptor, _ ->
        val session = VpnService().dyltun(descriptor, object : HttpHooks {})
        try {
            runBlocking { withTimeout(3000) { session.state.first { it == DyltunState.Running || it is DyltunState.Failed } } }
            assertEquals(DyltunState.Running, session.state.value)
            assertTrue(descriptor.fileDescriptor.valid())
        } finally { session.close(); runBlocking { withTimeout(3000) { session.awaitClosed() } } }
        assertEquals(DyltunState.Stopped, session.state.value)
        assertFalse(descriptor.fileDescriptor.valid())
    }

    @Test fun inspectorConfigurationStartsAndOwnsDescriptorUntilStopped() = withPair { descriptor, _ ->
        val session = VpnService().dyltun(descriptor) {
            protocols { install(TlsClientHelloInspector()); install(MqttInspector()) }
            routing { tlsSni("example.com") { reset() }; mqtt { redirect("127.0.0.1", 1883) } }
        }
        try {
            runBlocking { withTimeout(3000) { session.state.first { it == DyltunState.Running || it is DyltunState.Failed } } }
            assertEquals(DyltunState.Running, session.state.value)
            assertTrue(descriptor.fileDescriptor.valid())
        } finally { session.close(); runBlocking { withTimeout(3000) { session.awaitClosed() } } }
        assertEquals(DyltunState.Stopped, session.state.value)
        assertFalse(descriptor.fileDescriptor.valid())
    }

    private fun withPair(block: (ParcelFileDescriptor, ParcelFileDescriptor) -> Unit) {
        val first = FileDescriptor()
        val second = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, first, second)
        try { ParcelFileDescriptor.dup(first).use { a -> ParcelFileDescriptor.dup(second).use { b -> block(a, b) } } }
        finally { Os.close(first); Os.close(second) }
    }
}
