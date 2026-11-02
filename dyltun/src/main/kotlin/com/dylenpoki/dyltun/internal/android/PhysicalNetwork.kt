package com.dylenpoki.dyltun.internal.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.InetAddress

@Suppress("DEPRECATION")
internal fun physicalNetwork(context: Context): Network {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return manager.allNetworks.firstOrNull {
        val capabilities = manager.getNetworkCapabilities(it)
        capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } ?: throw IOException("No physical network is available")
}

internal fun numericV4(host: String): InetAddress? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for (index in 0..3) {
        val part = parts[index]
        if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
        val value = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        bytes[index] = value.toByte()
    }
    return InetAddress.getByAddress(bytes)
}
