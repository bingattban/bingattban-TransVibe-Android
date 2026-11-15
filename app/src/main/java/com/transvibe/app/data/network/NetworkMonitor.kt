package com.transvibe.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Application-scoped owner of one ConnectivityManager callback. */
class NetworkMonitor(context: Context) : Closeable {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val registered = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val _online = MutableStateFlow(currentValidatedState())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish()
        override fun onLost(network: Network) = publish()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = publish()
        override fun onUnavailable() = publish()
    }

    init {
        val didRegister = runCatching {
            cm.registerDefaultNetworkCallback(callback)
            true
        }.recoverCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
            true
        }.getOrDefault(false)
        registered.set(didRegister)
        publish()
    }

    fun isOnline(): Boolean = !closed.get() && currentValidatedState()

    fun isWifiConnected(): Boolean {
        if (closed.get()) return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (registered.getAndSet(false)) runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun publish() {
        if (!closed.get()) _online.value = currentValidatedState()
    }

    private fun currentValidatedState(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
