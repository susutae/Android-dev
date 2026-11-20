package com.example.meshdevicesearch

import android.net.Network
import java.net.Inet4Address

data class NetworkInterfaceChoice(
    val label: String,
    val interfaceName: String,
    val address: Inet4Address,
    val network: Network,
)

data class DiscoveredDevice(
    val ipAddress: String,
    val subnetMask: String,
    val discoveryNetwork: Network? = null,
)

data class SearchRequest(
    val network: NetworkInterfaceChoice,
    val timeoutMillis: Long = 5_000,
)

enum class SearchPhase { IDLE, SEARCHING, SUCCESS, EMPTY, STOPPED, ERROR }

data class SearchUiState(
    val isSearching: Boolean = false,
    val status: String = "Ready",
    val phase: SearchPhase = SearchPhase.IDLE,
    val devices: List<DiscoveredDevice> = emptyList(),
)
