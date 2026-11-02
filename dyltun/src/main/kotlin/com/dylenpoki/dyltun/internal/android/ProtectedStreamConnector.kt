package com.dylenpoki.dyltun.internal.android

import android.net.VpnService
import com.dylenpoki.dyltun.Endpoint
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel

internal fun interface StreamConnector {
    /** Returns a protected nonblocking channel that is connected or has a pending connect. */
    fun connect(destination: Endpoint): SocketChannel
}

internal class ProtectedStreamConnector(private val service: VpnService) : StreamConnector {
    override fun connect(destination: Endpoint): SocketChannel {
        val network = physicalNetwork(service)
        val address = numericV4(destination.host) ?: network.getAllByName(destination.host).firstOrNull()
            ?: throw IOException("Destination did not resolve")
        val channel = SocketChannel.open()
        try {
            if (!service.protect(channel.socket())) throw IOException("VpnService.protect() failed")
            network.bindSocket(channel.socket())
            channel.socket().tcpNoDelay = true
            channel.configureBlocking(false)
            channel.connect(InetSocketAddress(address, destination.port))
            return channel
        } catch (error: Throwable) {
            try { channel.close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }
}
