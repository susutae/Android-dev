package com.example.meshdevicesearch

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

class UdpDeviceDiscovery(context: Context) {
    private companion object {
        const val DISCOVERY_PORT = 7000
    }

    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    suspend fun search(request: SearchRequest, onDevice: (DiscoveredDevice) -> Unit) = withContext(Dispatchers.IO) {
        val lock = wifiManager?.createMulticastLock("mesh-device-search")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                // Binding to the selected local address already routes this limited broadcast
                // through that interface. Network.bindSocket() is unnecessary here and is
                // rejected with EPERM on some physical Ethernet and emulator networks.
                socket.bind(InetSocketAddress(request.network.address, 0))
                socket.broadcast = true
                socket.soTimeout = 250

                val payload = "{\"device\":\"mesh\",\"method\":\"discover\"}".toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))

                val seen = mutableSetOf<String>()
                val deadline = System.nanoTime() + request.timeoutMillis * 1_000_000
                val buffer = ByteArray(4096)
                while (System.nanoTime() < deadline) {
                    coroutineContext.ensureActive()
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val reply = DeviceReplyParser.parse(packet.data.decodeToString(0, packet.length))
                        if (reply != null && seen.add(reply.ipAddress)) {
                            onDevice(reply.copy(discoveryNetwork = request.network.network))
                        }
                    } catch (_: SocketTimeoutException) {
                        // Short timeout keeps coroutine cancellation responsive.
                    }
                }
            }
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }
}
