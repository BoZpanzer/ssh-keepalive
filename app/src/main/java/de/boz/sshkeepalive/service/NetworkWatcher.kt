package de.boz.sshkeepalive.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import de.boz.sshkeepalive.ssh.NetworkBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress
import java.net.Socket

/**
 * Beobachtet das Netzwerk, über das die SSH-Verbindung laufen soll.
 *
 * wifiOnly = true: Es wird gezielt ein WLAN angefordert und die Verbindung daran gebunden.
 *   Wichtig für Wohnheimnetze: Solange der Login noch nicht steht, hat das WLAN "kein Internet",
 *   Android würde normalen Verkehr dann über mobile Daten schicken. Durch requestNetwork()
 *   bleibt das WLAN außerdem verbunden, auch wenn Android es als "ohne Internet" einstuft.
 *
 * wifiOnly = false: Es wird immer das aktuelle Standardnetz (WLAN oder mobile Daten) benutzt;
 *   bei einem Wechsel wird automatisch neu verbunden.
 */
class NetworkWatcher(context: Context, private val wifiOnly: Boolean) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network

    private val wifiNetworks = LinkedHashSet<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (wifiOnly) {
                synchronized(wifiNetworks) { wifiNetworks.add(network); _network.value = wifiNetworks.last() }
            } else {
                _network.value = network
            }
        }

        override fun onLost(network: Network) {
            if (wifiOnly) {
                synchronized(wifiNetworks) {
                    wifiNetworks.remove(network)
                    _network.value = wifiNetworks.lastOrNull()
                }
            } else if (_network.value == network) {
                _network.value = null
            }
        }
    }

    private var registered = false

    fun start() {
        if (registered) return
        if (wifiOnly) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.requestNetwork(request, callback)
        } else {
            cm.registerDefaultNetworkCallback(callback)
        }
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        synchronized(wifiNetworks) { wifiNetworks.clear() }
        _network.value = null
    }

    fun label(network: Network?): String {
        val caps = network?.let { cm.getNetworkCapabilities(it) } ?: return "Netzwerk"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile Daten"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Netzwerk"
        }
    }

    /** Bindet den SSH-Socket und die DNS-Auflösung an genau dieses Netzwerk. */
    fun bindingFor(network: Network) = object : NetworkBinding {
        override fun bind(socket: Socket) = network.bindSocket(socket)
        override fun resolve(host: String): InetAddress = network.getByName(host)
    }
}
