package com.mahmoud.gymledger

import android.content.Context
import android.net.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.URL

class WifiGate(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(false)
    val ready = state.asStateFlow()
    @Volatile private var activeNetwork: Network? = null
    @Volatile var foreground = false
        set(value) { field = value; state.value = value && activeNetwork != null }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            activeNetwork = if (wifi && validated) network else null
            state.value = UploadPolicy.allowed(foreground, wifi, validated)
        }
        override fun onLost(network: Network) {
            if (activeNetwork == network) { activeNetwork = null; state.value = false }
        }
    }
    init { manager.registerDefaultNetworkCallback(callback) }
    fun allowed() = ready.value && foreground
    fun open(url: String): java.net.URLConnection {
        if (!allowed()) throw IOException("Waiting for Wi-Fi. Open the app on Wi-Fi to sync.")
        val wifi = activeNetwork ?: throw IOException("Wi-Fi disconnected. Your workout is saved locally.")
        // Bind each request to the validated Wi-Fi network so Android cannot fall back to cellular.
        return wifi.openConnection(URL(url))
    }
}
