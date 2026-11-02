package com.dylenpoki.dyltun.internal.android

import android.os.ParcelFileDescriptor
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal interface PacketDevice : Closeable {
    fun read(buffer: ByteArray): Int
    fun read(buffer: ByteArray, timeoutMillis: Int): Int = read(buffer)
    fun write(packet: ByteArray)
    fun wakeup()
    fun requestStop() { wakeup() }
}

/** FD ownership stays with ManagedSession. API 21-29 callers must retain Builder's nonblocking default. */
internal class TunDevice(private val descriptor: ParcelFileDescriptor) : PacketDevice {
    private val closed = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val pipe = arrayOf(FileDescriptor(), FileDescriptor()).also {
        // Linux socket type flags share O_NONBLOCK/O_CLOEXEC values, also on Android 21.
        val closeOnExec = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM or OsConstants.O_NONBLOCK or closeOnExec, 0, it[0], it[1])
    }
    private val signal = byteArrayOf(1)
    private val drain = ByteArray(64)

    init {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val fd = descriptor.fileDescriptor
                Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun read(buffer: ByteArray): Int = read(buffer, 500)

    override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
        require(timeoutMillis >= 0)
        if (closed.get() || stopped.get()) return -1
        val tun = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        val wake = StructPollfd().apply {
            fd = pipe[0]
            events = OsConstants.POLLIN.toShort()
        }
        try {
            Os.poll(arrayOf(tun, wake), timeoutMillis)
            if (wake.revents.toInt() and OsConstants.POLLIN != 0) {
                try { Os.read(pipe[0], drain, 0, drain.size) } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN) throw error
                }
            }
            if (closed.get() || stopped.get()) return -1
            if (tun.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL or OsConstants.POLLHUP) != 0) {
                throw IOException("TUN descriptor closed or revoked")
            }
            if (tun.revents.toInt() and OsConstants.POLLIN == 0) return 0
            val count = Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
            return if (count == 0) -1 else count
        } catch (error: ErrnoException) {
            if (closed.get() || stopped.get()) return -1
            if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) return 0
            throw IOException("TUN read failed", error)
        }
    }

    override fun write(packet: ByteArray) {
        while (!closed.get() && !stopped.get()) {
            try {
                val written = Os.write(descriptor.fileDescriptor, packet, 0, packet.size)
                if (written != packet.size) throw IOException("Partial TUN packet write")
                return
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINTR) continue
                if (error.errno != OsConstants.EAGAIN) throw IOException("TUN write failed", error)
                val writable = StructPollfd().apply {
                    fd = descriptor.fileDescriptor
                    events = OsConstants.POLLOUT.toShort()
                }
                Os.poll(arrayOf(writable), 100)
            }
        }
        throw IOException("TUN device stopped")
    }

    override fun wakeup() {
        if (!closed.get()) try { Os.write(pipe[1], signal, 0, 1) } catch (_: ErrnoException) {
            // A full socket already carries a wakeup; stop can also close it concurrently.
        }
    }

    override fun requestStop() {
        stopped.set(true)
        wakeup()
    }

    override fun close() {
        requestStop()
        if (closed.compareAndSet(false, true)) {
            for (fd in pipe) try { Os.close(fd) } catch (_: ErrnoException) { }
        }
    }
}
