package com.fluxtv.app.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkStatus { AVAILABLE, UNAVAILABLE }

/**
 * Tracks default-network availability so FluxTV can distinguish "no connectivity" from a failing
 * individual channel (REQ-NET-002) and retry automatically when the network returns (REQ-NET-004).
 */
class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _status = MutableStateFlow(currentStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _status.value = NetworkStatus.AVAILABLE
        }

        override fun onLost(network: Network) {
            _status.value = currentStatus()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                _status.value = NetworkStatus.AVAILABLE
            }
        }
    }

    fun start() {
        try {
            cm?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            cm?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    fun hasNetwork(): Boolean = currentStatus() == NetworkStatus.AVAILABLE

    private fun currentStatus(): NetworkStatus {
        val manager = cm ?: return NetworkStatus.UNAVAILABLE
        val network = manager.activeNetwork ?: return NetworkStatus.UNAVAILABLE
        val caps = manager.getNetworkCapabilities(network) ?: return NetworkStatus.UNAVAILABLE
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            NetworkStatus.AVAILABLE
        } else {
            NetworkStatus.UNAVAILABLE
        }
    }
}
