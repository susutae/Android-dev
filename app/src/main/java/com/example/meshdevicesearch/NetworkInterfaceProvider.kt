package com.example.meshdevicesearch

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

class NetworkInterfaceProvider(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    fun activeIpv4Interfaces(): List<NetworkInterfaceChoice> = connectivityManager.allNetworks
        .mapNotNull { network ->
            val properties = connectivityManager.getLinkProperties(network) ?: return@mapNotNull null
            val address = properties.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?: return@mapNotNull null
            val name = properties.interfaceName ?: "network-${network.hashCode()}"
            NetworkInterfaceChoice("$name - ${address.hostAddress}", name, address, network)
        }
        .distinctBy { it.interfaceName to it.address.hostAddress }
        .sortedBy { it.label }
}
