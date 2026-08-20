package ro.e92.launcher.can

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Fallback de viteză când CAN nu livrează nimic. Livrează DOAR viteza — restul
 * câmpurilor rămân null, ca merge-ul din [VehicleRepository] să nu suprascrie
 * date CAN mai bune.
 *
 * LocationManager direct, nu FusedLocationProvider: fără Google Play Services ca
 * dependență (nu e garantat prezent pe unitățile astea) și fără ~4 MB în APK.
 */
class GpsSpeedDataSource(private val context: Context) : CanDataSource {

    override val id = "gps"
    override val description = "Viteză din GPS (fallback)"

    private val _state = MutableStateFlow(VehicleState.EMPTY)
    override val vehicleState: StateFlow<VehicleState> = _state.asStateFlow()

    override val isAvailable: Boolean
        get() = listening &&
            _state.value.hasData &&
            SystemClock.elapsedRealtime() - _state.value.timestamp < GPS_STALE_MS

    private var listening = false

    private val lm: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // hasSpeed() e false pe fix-uri fără doppler; nu inventăm o valoare.
            if (!location.hasSpeed()) return
            _state.value = VehicleState(
                speedKmh = (location.speed * 3.6f).roundToInt().coerceIn(0, 320),
                timestamp = SystemClock.elapsedRealtime()
            )
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            _state.value = VehicleState.EMPTY
        }
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun start() {
        if (listening) return
        val manager = lm ?: return
        if (!hasPermission()) {
            Log.i(TAG, "Fără permisiune de locație — sursa GPS rămâne inactivă")
            return
        }
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper()
            )
            listening = true
        } catch (e: SecurityException) {
            Log.w(TAG, "requestLocationUpdates refuzat", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "GPS_PROVIDER indisponibil", e)
        }
    }

    override fun stop() {
        if (!listening) return
        runCatching { lm?.removeUpdates(listener) }
        listening = false
        _state.value = VehicleState.EMPTY
    }

    private companion object {
        const val TAG = "GpsSpeed"
        const val MIN_INTERVAL_MS = 1_000L
        // GPS e mai lent decât CAN; îi dăm o fereastră de staleness mai generoasă.
        const val GPS_STALE_MS = 5_000L
    }
}
