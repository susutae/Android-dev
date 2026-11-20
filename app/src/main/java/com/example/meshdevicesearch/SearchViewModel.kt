package com.example.meshdevicesearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(private val discovery: UdpDeviceDiscovery) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null

    fun search(request: SearchRequest) {
        searchJob?.cancel()
        mutableState.value = SearchUiState(
            isSearching = true,
            status = "Searching on ${request.network.label}...",
            phase = SearchPhase.SEARCHING,
        )
        searchJob = viewModelScope.launch {
            try {
                discovery.search(request) { device ->
                    mutableState.update { current -> current.copy(devices = current.devices + device) }
                }
                mutableState.update { current ->
                    current.copy(
                        isSearching = false,
                        status = when (current.devices.size) {
                            0 -> "Search finished. No devices replied."
                            1 -> "Search finished. 1 device found."
                            else -> "Search finished. ${current.devices.size} devices found."
                        },
                        phase = if (current.devices.isEmpty()) SearchPhase.EMPTY else SearchPhase.SUCCESS,
                    )
                }
            } catch (_: CancellationException) {
                mutableState.update { it.copy(isSearching = false, status = "Search stopped.", phase = SearchPhase.STOPPED) }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        isSearching = false,
                        status = "Search failed: ${error.message ?: error.javaClass.simpleName}",
                        phase = SearchPhase.ERROR,
                    )
                }
            }
        }
    }

    fun stop() {
        searchJob?.cancel()
    }
}
