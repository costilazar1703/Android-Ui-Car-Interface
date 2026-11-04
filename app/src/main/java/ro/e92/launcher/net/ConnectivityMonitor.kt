package ro.e92.launcher.net

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Starea Wi-Fi-ului, pentru bara de sus. DOAR citește — nu se atinge de conexiune.
 *
 * A existat aici o reconectare automată, gândită pentru hotspot-ul telefonului.
 * A fost scoasă: pe unitatea asta Wi-Fi-ul e legat de adaptorul OBD, care nu dă
 * internet, deci tableta nu era niciodată „online" și reconectarea rula la fiecare
 * întoarcere în meniu, cu `enableNetwork(..., disableOthers = true)` pe prima
 * rețea salvată. Putea dezactiva exact rețeaua OBD. Android se reconectează
 * singur la rețelele salvate; un launcher n-are ce căuta în controlul ei.
 */
class ConnectivityMonitor(private val context: Context) {

    enum class Status { WIFI_OFF, DISCONNECTED, CONNECTING, CONNECTED_NO_INTERNET, ONLINE }

    /** [level]: -1 fără conexiune, altfel 0 (slab) ... 3 (plin). */
    data class NetState(
        val status: Status = Status.DISCONNECTED,
        val ssid: String? = null,
        val level: Int = -1
    )

    private val _state = MutableStateFlow(NetState())
    val state: StateFlow<NetState> = _state.asStateFlow()

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var registered = false

    /** Doar din /sim, ca să se poată verifica iconița fără să muți antena. */
    @Volatile
    var simulatedLevel: Int? = null

    private val rssiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) = refresh()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    fun start() {
        if (registered) return
        val manager = cm ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            manager.registerNetworkCallback(request, callback)
            context.registerReceiver(rssiReceiver, IntentFilter(WifiManager.RSSI_CHANGED_ACTION))
            registered = true
        }.onFailure { Log.w(TAG, "registerNetworkCallback a eșuat", it) }
        refresh()
    }

    fun stop() {
        if (!registered) return
        runCatching { cm?.unregisterNetworkCallback(callback) }
        runCatching { context.unregisterReceiver(rssiReceiver) }
        registered = false
    }

    fun refresh() {
        val manager = cm
        val wifiEnabled = runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false)
        if (!wifiEnabled && simulatedLevel == null) {
            _state.value = NetState(Status.WIFI_OFF, null)
            return
        }

        if (manager == null) {
            _state.value = NetState(Status.DISCONNECTED, null)
            return
        }
        val active = manager.activeNetwork
        val caps = if (active != null) manager.getNetworkCapabilities(active) else null
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val status = when {
            !onWifi -> Status.DISCONNECTED
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true -> Status.ONLINE
            else -> Status.CONNECTED_NO_INTERNET
        }
        _state.value = NetState(status, if (onWifi) currentSsid() else null, signalLevel())
    }

    /**
     * Din WifiManager, nu din rețeaua activă: adaptorul OBD nu dă internet, iar o
     * asemenea rețea poate să nu fie cea implicită deși suntem conectați la ea.
     */
    private fun signalLevel(): Int {
        simulatedLevel?.let { return it }
        return runCatching {
            val info = wifi?.connectionInfo
            if (info == null || info.networkId == -1) -1
            else WifiManager.calculateSignalLevel(info.rssi, SIGNAL_LEVELS)
        }.getOrDefault(-1)
    }

    /**
     * Încearcă reconectarea la rețelele salvate, cu backoff. Pornit la boot și
     * la revenirea pe home cu Wi-Fi deconectat.
     */

    private fun currentSsid(): String? {
        if (!hasLocationPermission()) return null
        return try {
            @Suppress("DEPRECATION")
            val raw = wifi?.connectionInfo?.ssid ?: return null
            val clean = raw.trim('"')
            if (clean.isEmpty() || clean == UNKNOWN_SSID) null else clean
        } catch (e: SecurityException) {
            null
        }
    }

    /** Pe 8.1 SSID-ul rețelei conectate e ascuns fără permisiune de locație. */
    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun openWifiSettings() {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val TAG = "Connectivity"
        const val UNKNOWN_SSID = "<unknown ssid>"
        const val SIGNAL_LEVELS = 4
    }
}
