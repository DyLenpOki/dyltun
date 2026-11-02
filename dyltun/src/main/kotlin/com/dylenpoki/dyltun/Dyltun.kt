package com.dylenpoki.dyltun

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.dylenpoki.dyltun.internal.android.ProtectedDatagramConnector
import com.dylenpoki.dyltun.internal.android.ProtectedStreamConnector
import com.dylenpoki.dyltun.internal.android.TunDevice
import com.dylenpoki.dyltun.internal.engine.ManagedSession
import com.dylenpoki.dyltun.internal.engine.IpPacketRuntime

/**
 * Builds configuration synchronously, then transfers descriptor ownership to the returned session.
 * Runs bounded IPv4 TCP/UDP forwarding, optional plaintext HTTP/1.1 hooks and protocol routing.
 */
fun VpnService.dyltun(
    fd: ParcelFileDescriptor,
    configure: DyltunConfig.Builder.() -> Unit = {}
): DyltunSession {
    val config = DyltunConfig.Builder().apply(configure).build()
    return ManagedSession(fd) { emit ->
        IpPacketRuntime(TunDevice(fd), config, ProtectedDatagramConnector(this), emit, ProtectedStreamConnector(this))
    }.also { it.start() }
}

fun VpnService.dyltun(fd: ParcelFileDescriptor, hooks: HttpHooks?): DyltunSession {
    val configuredHooks = hooks
    return dyltun(fd) { this.hooks = configuredHooks }
}
