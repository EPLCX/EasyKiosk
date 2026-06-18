package com.adscreen.kiosk.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class NetworkUtil(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun registerNetworkCallback(callback: NetworkCallbackAdapter) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }

    fun unregisterNetworkCallback(callback: NetworkCallbackAdapter) {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    class NetworkCallbackAdapter(
        val onAvailable: ((Network) -> Unit)? = null,
        val onLost: ((Network) -> Unit)? = null
    ) : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { onAvailable?.invoke(network) }
        override fun onLost(network: Network) { onLost?.invoke(network) }
    }
}
