package ro.e92.launcher.net

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rezolvă frustrarea concretă: Waze fără internet pentru că hotspot-ul telefonului
 * nu s-a reconectat. Starea trebuie să fie VIZIBILĂ permanent pe home, iar
 * reconectarea să se încerce singură la pornire.
 *
 * AVERTISMENT (Open Question 8): pe Android 8.1 `enableNetwork()` pentru o aplicație
 * non-system funcționează doar pentru rețelele pe care aplicația LE-A ADĂUGAT EA.
 * Pentru un hotspot salvat de utilizator din Setări, apelul poate întoarce false.
 * Codul tratează asta ca pe un caz normal: dacă auto-connect nu prinde, rămâne
 * shortcut-ul focusabil către setările Wi-Fi. Verifică empiric în mașină și
 * uită-te după "auto-connect a eșuat" în logcat.
 */
class ConnectivityMonitor(private val context: Context) {

    enum class Status { WIFI_OFF, DISCONNECTED, CONNECTING, CONNECTED_NO_INTERNET, ONLINE }

    data class NetState(val status: Status = Status.DISCONNECTED, val ssid: String? = null)

    private val _state = MutableStateFlow(NetState())
    val state: StateFlow<NetState> = _state.asStateFlow()

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var registered = false
    private var autoConnectJob: Job? = null

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
            registered = true
        }.onFailure { Log.w(TAG, "registerNetworkCallback a eșuat", it) }
        refresh()
    }

    fun stop() {
        autoConnectJob?.cancel()
        if (!registered) return
        runCatching { cm?.unregisterNetworkCallback(callback) }
        registered = false
    }

    fun refresh() {
        val manager = cm
        val wifiEnabled = runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false)
        if (!wifiEnabled) {
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
        _state.value = NetState(status, if (onWifi) currentSsid() else null)
    }

    /**
     * Încearcă reconectarea la rețelele salvate, cu backoff. Pornit la boot și
     * la revenirea pe home cu Wi-Fi deconectat.
     */
    fun startAutoConnect(scope: CoroutineScope) {
        if (autoConnectJob?.isActive == true) return
        autoConnectJob = scope.launch {
            var delayMs = FIRST_RETRY_MS
            repeat(MAX_ATTEMPTS) { attempt ->
                refresh()
                if (_state.value.status == Status.ONLINE) return@launch

                withContext(Dispatchers.IO) { attemptReconnect() }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
                Log.d(TAG, "auto-connect, încercarea ${attempt + 1}")
            }
        }
    }

    private fun attemptReconnect() {
        val manager = wifi ?: return
        try {
            if (!manager.isWifiEnabled) {
                @Suppress("DEPRECATION")
                manager.setWifiEnabled(true)
                return // lasă driverul să pornească; reîncercăm la următorul tur
            }
            @Suppress("DEPRECATION")
            val saved = manager.configuredNetworks
            if (saved.isNullOrEmpty()) {
                Log.i(TAG, "auto-connect a eșuat: nicio rețea salvată vizibilă " +
                    "(lipsă permisiune de locație sau restricție de system app)")
                return
            }
            @Suppress("DEPRECATION")
            val ok = saved.any { manager.enableNetwork(it.networkId, true) }
            if (!ok) Log.i(TAG, "auto-connect a eșuat: enableNetwork() refuzat")
            @Suppress("DEPRECATION")
            manager.reconnect()
        } catch (e: SecurityException) {
            Log.i(TAG, "auto-connect a eșuat: permisiuni insuficiente", e)
        }
    }

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
        const val FIRST_RETRY_MS = 3_000L
        const val MAX_RETRY_MS = 30_000L
        const val MAX_ATTEMPTS = 8
    }
}
