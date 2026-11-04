package ro.e92.launcher.can

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.e92.launcher.core.EventLog
import ro.e92.launcher.core.KswSettings
import ro.e92.launcher.core.Services
import ro.e92.launcher.ui.HomeActivity

/** Launcher-ul adus în față de marșarier, nu de șofer: la ieșire revine unde era. */
const val EXTRA_REVERSE = "ro.e92.launcher.REVERSE"

/**
 * Treapta de marșarier pe unitatea KSW.
 *
 * MCU-ul trimite `F2 00 11 02 <01|02|03> <01 intră|00 iese>` (cameră montată
 * ulterior, 360, originală), dar EventCenter nu dă cadrul mai departe nimănui:
 * doar îl scrie în jurnal și, dacă tipul de cameră din SysVar se potrivește,
 * deschide fereastra lui de sistem (tip 2010), peste care nicio aplicație nu poate
 * desena. De aici cele două jumătăți:
 *
 *  - citim jurnalul, deci launcher-ul are nevoie de READ_LOGS, acordat o dată prin adb;
 *  - cu scena noastră pornită punem tipul de cameră pe 0, valoare pentru care
 *    EventCenter nu mai deschide fereastra. Ce face MCU-ul cu 0 nu știm până la
 *    proba din mașină, de aceea opțiunea e oprită implicit și se poate reveni la 1.
 *
 * Emite `true` sau `null`, niciodată `false`: un „nu" de aici ar acoperi o altă sursă.
 */
class ReverseSource(
    private val context: Context,
    private val scope: CoroutineScope
) : CanDataSource {

    override val id = "ksw-reverse"
    override val description = "Reverse gear (EventCenter log)"

    private val _state = MutableStateFlow(VehicleState.EMPTY)
    override val vehicleState: StateFlow<VehicleState> = _state.asStateFlow()

    override val isAvailable: Boolean get() = job?.isActive == true

    /** Distanțele în cm, câte una pe senzor. Azi vin doar din /sim: unitatea nu le primește. */
    private val _sensorCm = MutableStateFlow<List<Int>?>(null)
    val sensorCm: StateFlow<List<Int>?> = _sensorCm.asStateFlow()

    /**
     * EventCenter are o fereastră de radar alimentată de acest broadcast, dar în
     * proba din 2026-11-04 n-a venit niciodată. Îl ascultăm doar ca să-i vedem
     * formatul în jurnal dacă apare: octeții nu sunt încă interpretați ca distanțe.
     */
    private val radarReceiver = object : BroadcastReceiver() {
        private var last: String? = null
        override fun onReceive(c: Context?, intent: Intent?) {
            val raw = intent?.getByteArrayExtra(RADAR_DATA)?.joinToString(" ") { "%02x".format(it) } ?: "(gol)"
            if (raw != last) EventLog.log(TAG, "radar KSW: $raw")
            last = raw
        }
    }

    private val simulated = MutableStateFlow(false)

    /** Din [HomeActivity]: dacă launcher-ul e deja în față, nu e nimic de adus. */
    @Volatile
    var launcherInFront = false
    private var job: Job? = null

    @Volatile
    private var process: Process? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { readLoop() }
        runCatching { context.registerReceiver(radarReceiver, IntentFilter(RADAR_ACTION)) }
    }

    override fun stop() {
        job?.cancel()
        job = null
        process?.destroy()
        runCatching { context.unregisterReceiver(radarReceiver) }
    }

    private suspend fun readLoop() {
        var warned = false
        while (scope.isActive && job?.isCancelled != true) {
            if (context.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
                if (!warned) EventLog.log(TAG, "fără READ_LOGS: nu văd marșarierul")
                warned = true
                delay(PERMISSION_RECHECK_MS)
                continue
            }
            runCatching { follow() }.onFailure { EventLog.log(TAG, "logcat: ${it.message}") }
            // Fără jurnal nu mai știm treapta; scena nu trebuie să rămână agățată.
            publish(false)
            delay(RESTART_MS)
        }
    }

    private fun follow() {
        val started = ProcessBuilder(
            "logcat", "-v", "brief", "-T", "1", "-e", "0xf2 0x00 0x11 0x02", "EventService:I", "*:S"
        ).redirectErrorStream(true).start()
        process = started
        EventLog.log(TAG, "urmăresc marșarierul")
        try {
            started.inputStream.bufferedReader().forEachLine { line ->
                FRAME.find(line)?.let { publish(it.groupValues[1] == "1") }
            }
        } finally {
            started.destroy()
        }
    }

    private fun publish(on: Boolean) {
        val value = if (on) true else null
        if (_state.value.reverseGear == value && _state.value.timestamp != 0L) return
        if (on) EventLog.log(TAG, "marșarier") else if (_state.value.reverseGear == true) EventLog.log(TAG, "ieșit din marșarier")
        _state.value = _state.value.copy(reverseGear = value, timestamp = SystemClock.elapsedRealtime())
    }

    fun simulate(on: Boolean, cm: List<Int>?) {
        _sensorCm.value = if (on) cm else null
        simulated.value = on
        publish(on)
    }

    /**
     * Aduce launcher-ul în față când se intră în marșarier, de oriunde ai fi.
     * Scena o deschide și o închide [HomeActivity].
     */
    fun watchForScene() {
        applyCameraMode()
        scope.launch {
            Services.vehicle.state
                .map { it.reverseGear == true }
                .distinctUntilChanged()
                .collect { on ->
                    if (on && Services.prefs.reverseScene && !launcherInFront) {
                        runCatching {
                            context.startActivity(
                                Intent(context, HomeActivity::class.java)
                                    .putExtra(EXTRA_REVERSE, true)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            )
                        }.onFailure { EventLog.log(TAG, "scena: ${it.message}") }
                    }
                }
        }
    }

    /** Pune tipul de cameră conform opțiunii. La pornire: unitatea își poate rescrie setările. */
    fun applyCameraMode() {
        scope.launch(Dispatchers.IO) {
            val wanted = if (Services.prefs.reverseScene) CAMERA_NONE else CAMERA_AFTERMARKET
            val current = KswSettings.read(context, CAMERA_SELECTION) ?: return@launch
            // Oprită, opțiunea nu atinge nimic decât dacă noi am pus 0 mai devreme.
            if (current == wanted || (!Services.prefs.reverseScene && current != CAMERA_NONE)) return@launch
            KswSettings.write(context, CAMERA_SELECTION, wanted)
            context.sendBroadcast(Intent(APPLY_ACTION).putExtra(APPLY_EXTRA, APPLY_CAMERA))
            EventLog.log(TAG, "tip cameră KSW $current → $wanted")
        }
    }

    private companion object {
        const val TAG = "Reverse"
        val FRAME = Regex("""0xf2 0x00 0x11 0x02 0x0[123] 0x0([01])""")

        const val RADAR_ACTION = "com.szchoiceway.eventcenter.MCU_CAR_CAN_RADAR_INFO"
        const val RADAR_DATA = "com.szchoiceway.eventcenter.CAR_CAN_DATA"
        const val CAMERA_SELECTION = "KESAIWEI_SYS_CAMERA_SELECTION"
        const val CAMERA_NONE = "0"
        const val CAMERA_AFTERMARKET = "1"
        const val APPLY_ACTION = "com.szchoiceway.eventcenter.EventUtils.ZXW_SENDBROADCAST8902MOD"
        const val APPLY_EXTRA = "com.szchoiceway.eventcenter.EventUtils.ZXW_SENDBROADCAST8902MOD_EXTRA"
        const val APPLY_CAMERA = 4

        const val PERMISSION_RECHECK_MS = 30_000L
        const val RESTART_MS = 3_000L
    }
}
