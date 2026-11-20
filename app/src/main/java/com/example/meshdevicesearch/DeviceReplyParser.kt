package com.example.meshdevicesearch

import org.json.JSONObject

object DeviceReplyParser {
    fun parse(payload: String): DiscoveredDevice? = runCatching {
        val json = JSONObject(payload)
        if (json.optString("device") != "mesh" || json.optString("method") != "response") return null
        val ipAddress = json.getString("ipAddr")
        val subnetMask = json.getString("nwMask")
        if (!isIpv4(ipAddress) || !isContiguousMask(subnetMask)) return null
        DiscoveredDevice(ipAddress, subnetMask)
    }.getOrNull()

    private fun octets(value: String): List<Int>? {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) }) return null
        return parts.map { it.toIntOrNull() ?: return null }.takeIf { values -> values.all { it in 0..255 } }
    }

    private fun isIpv4(value: String) = octets(value) != null

    private fun isContiguousMask(value: String): Boolean {
        val values = octets(value) ?: return false
        val mask = values.fold(0L) { result, octet -> (result shl 8) or octet.toLong() }
        val inverse = mask.inv() and 0xFFFF_FFFFL
        return (inverse and (inverse + 1)) == 0L
    }
}
