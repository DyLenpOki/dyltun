package com.dylenpoki.dyltun.internal.android

import android.net.VpnService
import android.os.Build
import com.dylenpoki.dyltun.Endpoint
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

internal fun interface DatagramConnector {
    fun connect(destination: Endpoint): DatagramChannel
}

internal class ProtectedDatagramConnector(private val service: VpnService) : DatagramConnector {
    override fun connect(destination: Endpoint): DatagramChannel {
        val network = physicalNetwork(service)
        val address = numericV4(destination.host) ?: network.getAllByName(destination.host).firstOrNull()
            ?: throw IOException("Destination did not resolve")
        val channel = DatagramChannel.open()
        try {
            if (!service.protect(channel.socket())) throw IOException("VpnService.protect() failed")
            if (Build.VERSION.SDK_INT >= 22) network.bindSocket(channel.socket())
            channel.configureBlocking(false)
            channel.connect(InetSocketAddress(address, destination.port))
            return channel
        } catch (error: Throwable) {
            try { channel.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

}
