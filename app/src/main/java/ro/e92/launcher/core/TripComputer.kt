package ro.e92.launcher.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.e92.launcher.can.CanDataSource
import ro.e92.launcher.can.VehicleRepository
import ro.e92.launcher.can.VehicleState

/**
 * Calculatorul de bord: două contoare din viteza și combustibilul trimise de
 * unitate.
 *
 *  - „Drumul acesta" pornește de la zero la fiecare contact.
 *  - „Drum lung" se adună peste mai multe contacte până îl resetezi - pentru
 *    plecările lungi, cu opriri pe drum. E salvat pe disc, deci supraviețuiește
 *    și unei porniri la rece.
 *
 * Distanța e integrală vitezei (mașina nu trimite kilometrajul). Combustibilul
 * consumat e scăderea nivelului din rezervor; o creștere bruscă înseamnă că ai
 * alimentat, iar nivelul nou devine punctul de plecare.
 */
class TripComputer(private val context: Context, private val scope: CoroutineScope) {

    data class Trip(
        val distanceKm: Double = 0.0,
        val drivingMs: Long = 0L,
        val maxSpeedKmh: Int = 0,
        val fuelUsedL: Int = 0
    ) {
        val avgSpeedKmh: Int?
            get() = if (drivingMs < MIN_AVG_MS) null else (distanceKm / (drivingMs / 3_600_000.0)).toInt()

        /** l/100 km, doar când e destul drum ca cifra să însemne ceva. */
        val consumption: Double?
            get() = if (distanceKm < MIN_CONSUMPTION_KM || fuelUsedL <= 0) null else fuelUsedL * 100.0 / distanceKm
    }

    private val _drive = MutableStateFlow(Trip())
    val drive: StateFlow<Trip> = _drive.asStateFlow()

    private val _longTrip = MutableStateFlow(loadLongTrip())
    val longTrip: StateFlow<Trip> = _longTrip.asStateFlow()

    /** Ultimul rezumat de drum încheiat, pentru ecranul de rămas bun. */
    var lastFinishedDrive: Trip? = null
        private set

    private var lastSampleAt = 0L
    private var lastFuel: Int? = null
    private var lastSavedAt = 0L
    private var started = false

    /** Contactul pornit / oprit, anunțat de unitate; true = oprit. */
    private val _ignitionOff = MutableStateFlow(false)
    val ignitionOff: StateFlow<Boolean> = _ignitionOff.asStateFlow()

    fun start(vehicle: () -> VehicleRepository) {
        if (started) return
        started = true
        context.registerReceiver(accReceiver, IntentFilter(ACTION_ACC))
        // Dacă sursele se reconstruiesc din Setări, contorul se reia de la
        // următoarea pornire a aplicației - se întâmplă o dată, la configurare.
        scope.launch { vehicle().state.collect { sample(it) } }
    }

    fun resetLongTrip() {
        _longTrip.value = Trip()
        saveLongTrip()
    }

    private fun sample(state: VehicleState) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastSampleAt == 0L) 0L else (now - lastSampleAt).coerceAtMost(MAX_STEP_MS)
        lastSampleAt = now

        val fresh = state.hasData && now - state.timestamp < CanDataSource.STALE_AFTER_MS
        val speed = if (fresh) state.speedKmh ?: 0 else 0
        val km = speed * dt / 3_600_000.0
        val driving = speed >= MOVING_KMH

        var fuelDelta = 0
        state.fuel?.let { fuel ->
            val prev = lastFuel
            if (prev != null && fuel < prev && prev - fuel <= MAX_FUEL_STEP) fuelDelta = prev - fuel
            lastFuel = fuel
        }

        if (km == 0.0 && !driving && fuelDelta == 0) return
        _drive.value = _drive.value.add(km, if (driving) dt else 0L, speed, fuelDelta)
        _longTrip.value = _longTrip.value.add(km, if (driving) dt else 0L, speed, fuelDelta)

        if (now - lastSavedAt > SAVE_EVERY_MS) {
            lastSavedAt = now
            saveLongTrip()
        }
    }

    private fun Trip.add(km: Double, ms: Long, speed: Int, fuel: Int) = copy(
        distanceKm = distanceKm + km,
        drivingMs = drivingMs + ms,
        maxSpeedKmh = maxOf(maxSpeedKmh, speed),
        fuelUsedL = fuelUsedL + fuel
    )

    private val accReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val off = intent?.getBooleanExtra(EXTRA_ACC_OFF, false) ?: return
            if (off == _ignitionOff.value) return
            if (off) {
                lastFinishedDrive = _drive.value
                saveLongTrip()
            } else {
                _drive.value = Trip()
                lastFuel = null
            }
            _ignitionOff.value = off
        }
    }

    // ------------------------------------------------------------- persistență

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadLongTrip() = Trip(
        distanceKm = prefs.getFloat(K_DIST, 0f).toDouble(),
        drivingMs = prefs.getLong(K_TIME, 0L),
        maxSpeedKmh = prefs.getInt(K_MAX, 0),
        fuelUsedL = prefs.getInt(K_FUEL, 0)
    )

    private fun saveLongTrip() {
        val t = _longTrip.value
        prefs.edit()
            .putFloat(K_DIST, t.distanceKm.toFloat())
            .putLong(K_TIME, t.drivingMs)
            .putInt(K_MAX, t.maxSpeedKmh)
            .putInt(K_FUEL, t.fuelUsedL)
            .apply()
    }

    private companion object {
        /** Trimis de EventCenter la ACC: extra true = contact oprit. */
        const val ACTION_ACC = "com.choiceway.eventcenter.EventUtils.KSW_ZXW_ACC_CONTROL_BEFORE"
        const val EXTRA_ACC_OFF = "com.choiceway.eventcenter.EventUtils.KSW_ZXW_ACC_CONTROL_BEFORE_DATA"

        const val PREFS = "e92_trip"
        const val K_DIST = "long_km"
        const val K_TIME = "long_ms"
        const val K_MAX = "long_max"
        const val K_FUEL = "long_fuel"

        const val MOVING_KMH = 3
        /** Un gol mai lung în eșantioane nu se integrează: n-am ști cât am mers. */
        const val MAX_STEP_MS = 1_000L
        /** O scădere mai mare dintr-odată e zgomot de senzor, nu consum. */
        const val MAX_FUEL_STEP = 3
        const val SAVE_EVERY_MS = 30_000L
        const val MIN_AVG_MS = 60_000L
        const val MIN_CONSUMPTION_KM = 20.0
    }
}
