package com.example.meshdevicesearch

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object WebManagementClient {
    private const val KEY = "enableWebManagement"

    suspend fun request(device: DiscoveredDevice, enabled: Boolean? = null): Boolean? =
        withContext(Dispatchers.IO) {
            val url = URL("http", device.ipAddress, "/config?content=$KEY")
            val network = device.discoveryNetwork ?: throw IOException("Run discovery again to select a network")
            val connection = network.openConnection(url, Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                if (enabled != null) {
                    val body = JSONObject().put(KEY, enabled).toString().toByteArray(Charsets.UTF_8)
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw IOException("HTTP $status")
                if (enabled != null) return@withContext null
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(4097)
                    var size = 0
                    while (size < buffer.size) {
                        val count = reader.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        size += count
                    }
                    if (size == buffer.size) throw IOException("Response too large")
                    String(buffer, 0, size)
                }
                JSONObject(body).opt(KEY) as? Boolean ?: throw IOException("Expected a JSON boolean for $KEY")
            } finally {
                connection.disconnect()
            }
        }
}
