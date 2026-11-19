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
 * consumat e nivelul de la plecare minus cel mai mic nivel văzut de atunci, pe
 * nivelul mediat pe ultimele [FUEL_WINDOW_MS] (rezervorul în șa se clatină în
 * viraje și la frânare). Nu se adună scăderi: varianta veche aduna fiecare
 * scădere și, cu nivelul care sare în sus și în jos, ieșea de ~10 ori mai mult
 * (2026-11-19). La o creștere clară ai alimentat: ce s-a consumat până atunci
 * rămâne, iar măsurarea pornește de la nivelul nou.
 */
class TripComputer(private val context: Context, private val scope: CoroutineScope) {

    data class Trip(
        val distanceKm: Double = 0.0,
        val drivingMs: Long = 0L,
        val maxSpeedKmh: Int = 0,
        val fuelUsedL: Int = 0,
        /**
         * Litrii cu zecimale, din consumul mediu al bordului × distanța de la resetarea lui;
         * null cât drumul lung nu e încă legat de resetarea din bord.
         */
        val fuelPrecise: Double? = null
    ) {
        /** l/100 km pe drumul ăsta; null până e destulă distanță ca cifra să însemne ceva. */
        val consumption: Double?
            get() = when {
                fuelPrecise != null && distanceKm >= MIN_CONS_KM -> fuelPrecise / distanceKm * 100.0
                fuelUsedL > 0 && distanceKm >= MIN_CONS_KM_ROUGH -> fuelUsedL / distanceKm * 100.0
                else -> null
            }

        val avgSpeedKmh: Int?
            get() = if (drivingMs < MIN_AVG_MS) null else (distanceKm / (drivingMs / 3_600_000.0)).toInt()
    }

    private val _drive = MutableStateFlow(Trip())
    val drive: StateFlow<Trip> = _drive.asStateFlow()

    private val _longTrip = MutableStateFlow(loadLongTrip())
    val longTrip: StateFlow<Trip> = _longTrip.asStateFlow()

    /** Ultimul rezumat de drum încheiat, pentru ecranul de rămas bun. */
    var lastFinishedDrive: Trip? = null
        private set

    private var lastSampleAt = 0L
    /** Citirile nivelului din ultimele [FUEL_WINDOW_MS], pentru mediană. */
    private val fuelWindow = ArrayDeque<Pair<Long, Int>>()
    private val driveFuel = FuelMeter()
    private val longFuel = FuelMeter(carried = _longTrip.value.fuelUsedL)
    private var lastLoggedFuel = -1

    /**
     * Drumul lung pornește odată cu resetarea consumului mediu din bord. Cât e legat, litrii
     * consumați de la resetare = consumul mediu din bord × distanța drumului lung.
     */
    private var longSynced = prefs.getBoolean(K_SYNCED, false)
    /** Litrii „de bord" la plecarea pe drumul ăsta; diferența față de acum = consumul drumului. */
    private var driveBoardBase: Double? = null
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

    /** Consumul mediu a fost resetat din bord: drumul lung pornește de la zero, legat de el. */
    fun onBoardAverageReset() {
        // Drumul ăsta continuă: baza lui se mută ca litrii lui să nu sară.
        val driveSoFar = _drive.value.fuelPrecise ?: driveFuel.used().toDouble()
        driveBoardBase = -driveSoFar
        longFuel.reset(0)
        longSynced = true
        _longTrip.value = Trip(fuelPrecise = 0.0)
        saveLongTrip()
        EventLog.log(TAG, "consum mediu resetat din bord: drumul lung de la zero")
    }

    private fun sample(state: VehicleState) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastSampleAt == 0L) 0L else (now - lastSampleAt).coerceAtMost(MAX_STEP_MS)
        lastSampleAt = now

        val fresh = state.hasData && now - state.timestamp < CanDataSource.STALE_AFTER_MS
        val speed = if (fresh) state.speedKmh ?: 0 else 0
        val km = speed * dt / 3_600_000.0
        val driving = speed >= MOVING_KMH

        state.fuel?.let { fuel ->
            fuelWindow.addLast(now to fuel)
            while (fuelWindow.isNotEmpty() && now - fuelWindow.first().first > FUEL_WINDOW_MS) fuelWindow.removeFirst()
            val level = fuelWindow.map { it.second }.sorted()[fuelWindow.size / 2]
            driveFuel.feed(level)
            longFuel.feed(level)
            if (level != lastLoggedFuel) {
                lastLoggedFuel = level
                EventLog.log(TAG, "nivel $level l, consumat: drum ${driveFuel.used()} l, lung ${longFuel.used()} l")
            }
        }
        val driveUsed = driveFuel.used()
        val longUsed = longFuel.used()

        if (km == 0.0 && !driving && driveUsed == _drive.value.fuelUsedL && longUsed == _longTrip.value.fuelUsedL) return
        val long = _longTrip.value.add(km, if (driving) dt else 0L, speed, longUsed)
        val board = if (longSynced) state.avgConsumption?.let { it * long.distanceKm / 100.0 } else null
        var drivePrecise: Double? = null
        if (board != null) {
            val base = driveBoardBase ?: (board - driveUsed).also { driveBoardBase = it }
            drivePrecise = (board - base).coerceAtLeast(0.0)
        }
        _drive.value = _drive.value.add(km, if (driving) dt else 0L, speed, driveUsed).copy(fuelPrecise = drivePrecise)
        _longTrip.value = long.copy(fuelPrecise = board)

        if (now - lastSavedAt > SAVE_EVERY_MS) {
            lastSavedAt = now
            saveLongTrip()
        }
    }

    private fun Trip.add(km: Double, ms: Long, speed: Int, fuelUsed: Int) = copy(
        distanceKm = distanceKm + km,
        drivingMs = drivingMs + ms,
        maxSpeedKmh = maxOf(maxSpeedKmh, speed),
        fuelUsedL = fuelUsed
    )

    /**
     * Litrii consumați pe un drum: [carried] din bucățile de dinainte (alimentări, contacte
     * anterioare la drumul lung) plus scăderea de la nivelul de plecare al bucății curente.
     */
    private class FuelMeter(var carried: Int = 0) {
        private var base: Int? = null
        private var low = 0

        fun feed(level: Int) {
            val b = base
            when {
                b == null -> {
                    base = level
                    low = level
                }
                // Alimentare: ce s-a consumat rămâne, se măsoară de la nivelul nou.
                level >= low + REFUEL_L -> {
                    carried += b - low
                    base = level
                    low = level
                }
                level < low -> low = level
            }
        }

        fun used(): Int = carried + ((base ?: low) - low).coerceAtLeast(0)

        /** Bucată nouă (contact nou): păstrează [carry] litri, nivelul de plecare se ia din nou. */
        fun reset(carry: Int) {
            carried = carry
            base = null
        }
    }

    private val accReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val off = intent?.getBooleanExtra(EXTRA_ACC_OFF, false) ?: return
            if (off == _ignitionOff.value) return
            if (off) {
                lastFinishedDrive = _drive.value
                saveLongTrip()
            } else {
                _drive.value = Trip()
                driveFuel.reset(0)
                driveBoardBase = null
                longFuel.reset(longFuel.used())
                fuelWindow.clear()
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
            .putBoolean(K_SYNCED, longSynced)
            .apply()
    }

    private companion object {
        /** Trimis de EventCenter la ACC: extra true = contact oprit. */
        const val ACTION_ACC = "com.choiceway.eventcenter.EventUtils.KSW_ZXW_ACC_CONTROL_BEFORE"
        const val EXTRA_ACC_OFF = "com.choiceway.eventcenter.EventUtils.KSW_ZXW_ACC_CONTROL_BEFORE_DATA"

        const val TAG = "Trip"
        const val PREFS = "e92_trip"
        const val K_DIST = "long_km"
        const val K_TIME = "long_ms"
        const val K_MAX = "long_max"
        const val K_FUEL = "long_fuel"
        const val K_SYNCED = "long_synced_board"

        const val MOVING_KMH = 3
        /** Un gol mai lung în eșantioane nu se integrează: n-am ști cât am mers. */
        const val MAX_STEP_MS = 1_000L
        /** Fereastra medianei pentru nivelul din rezervor. */
        const val FUEL_WINDOW_MS = 30_000L
        const val REFUEL_L = 5
        const val SAVE_EVERY_MS = 30_000L
        const val MIN_AVG_MS = 60_000L
        /** De la câți km arătăm consumul drumului, cu litri cu zecimale. */
        const val MIN_CONS_KM = 1.0
        /** La fel, dar doar cu litri întregi: abia de la 20 km cifra are sens. */
        const val MIN_CONS_KM_ROUGH = 20.0
    }
}
