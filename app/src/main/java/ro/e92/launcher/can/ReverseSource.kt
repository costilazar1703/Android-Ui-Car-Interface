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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.e92.launcher.core.EventLog
import ro.e92.launcher.core.KswSettings
import ro.e92.launcher.core.Services
import ro.e92.launcher.ui.HomeActivity

/** Cele patru confirmări de mod, arătate de [ro.e92.launcher.ui.screens.ModeScreen]. */
enum class ModeScene { DRIFT, NORMAL, SPORT, DRIVE }

/** Senzorul nu vede nimic. Mai mare decât orice prag din ParkSensorView. */
const val NO_OBSTACLE_CM = 999

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

    /**
     * Distanțele estimate în cm, câte una pe senzor; [NO_OBSTACLE_CM] = nu vede nimic.
     * Null = n-am primit cadrul 0x1C2. Vine doar cu motorul pornit, în marșarier.
     */
    private val _sensorCm = MutableStateFlow<List<Int>?>(null)
    val sensorCm: StateFlow<List<Int>?> = _sensorCm.asStateFlow()

    /** Unghiul volanului din 0x0C4: -1 dreapta ... 1 stânga, null = necunoscut. */
    /** Tensiunea bateriei din cadrul CAN 0x3B4 (cât vin cadre CAN), în volți. */
    private val _batteryVolts = MutableStateFlow<Float?>(null)
    val batteryVolts: StateFlow<Float?> = _batteryVolts.asStateFlow()

    private val _steering = MutableStateFlow<Float?>(null)
    val steering: StateFlow<Float?> = _steering.asStateFlow()

    /**
     * Sistemele oprite (DSC sau DTC), citite direct din starea DSC: 0x19E, octetul 1,
     * bitul 0x10 (f0 = oprite, e0 = normal; probă oarbă 3 din 3, 2026-11-16). Null = n-am
     * văzut încă niciun cadru. Butonul (0x316) și mesajul de pe bord (0x338, cod B8) nu mai
     * hotărăsc nimic: numărarea apăsărilor putea rămâne în urmă, iar mesajul dispare singur.
     */
    private val _driftMode = MutableStateFlow<Boolean?>(null)
    val driftMode: StateFlow<Boolean?> = _driftMode.asStateFlow()

    /**
     * Drive Sport, din afișajul cutiei: 0x1D2 cu octetul 0 = 78 (D), octetul 4 = f0 în D și
     * f1 în DS (probă oarbă 2 din 2, 2026-11-16). 0x304 arată la fel în D și DS, nu e bun.
     */
    private val _sportMode = MutableStateFlow<Boolean?>(null)
    val sportMode: StateFlow<Boolean?> = _sportMode.asStateFlow()

    /**
     * Schimbările văzute în direct, pentru scena de mod. Prima citire după o pauză de cadre
     * (cadrele CAN vin doar cu ecranul Dashboard deschis) e doar punct de plecare: dacă modul
     * s-a schimbat cât nu ascultam, nu anunțăm nimic.
     */
    private val _modeEvents = MutableSharedFlow<ModeScene>(extraBufferCapacity = 4)
    val modeEvents: SharedFlow<ModeScene> = _modeEvents.asSharedFlow()

    /**
     * Poziția manetei, din același cadru 0x1D2, octetul 0 (capturile din curte, 2026-11-07):
     * E1 = P, D2 = R, B4 = N, 78 = D. Null = încă necitită.
     */
    private val _lever = MutableStateFlow<Char?>(null)
    val lever: StateFlow<Char?> = _lever.asStateFlow()

    private val dscTrack = ModeTrack<Boolean>()
    private val sportTrack = ModeTrack<Boolean>()
    private val leverTrack = ModeTrack<Char>()
    private var lastGear = -1

    private var lastRawLogAt = 0L
    private val seenMessages = HashSet<Int>()

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

    /** Marșarier pornit din /sim: viteza inventată a emulatorului nu trebuie să închidă scena. */
    val isSimulated: Boolean get() = simulated.value

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

    private var bootLogged = false
    private var lastNote = ""
    private var lastNoteAt = 0L

    /** „f2 00 15 02 01 01 e6" → „15 02 01 01": fără antet și fără suma de control. */
    private fun compact(frame: String): String =
        frame.trim().split(' ').map { it.removePrefix("0x") }.drop(2).dropLast(1).joinToString(" ")

    /**
     * O singură dată pe pornire: ce și-au spus EventCenter și MCU-ul înainte să pornim noi
     * (setările trimise MCU-ului și starea raportată de el). Ajunge în /events, deci se poate
     * citi după o repornire fără adb.
     */
    private fun logBootHandshake() {
        if (bootLogged) return
        bootLogged = true
        runCatching {
            val dump = ProcessBuilder(
                "logcat", "-d", "-v", "brief", "-e", BOOT_FILTER, "EventService:I", "*:S"
            ).redirectErrorStream(true).start()
            var count = 0
            dump.inputStream.bufferedReader().forEachLine { line ->
                if (count >= BOOT_MAX) return@forEachLine
                val note = BOOT_IN.find(line)?.let { "← " + compact(it.groupValues[1]) }
                    ?: BOOT_OUT.find(line)?.let { "→ " + compact(it.groupValues[1]) }
                    ?: return@forEachLine
                EventLog.log(TAG, "MCU la pornire $note")
                count++
            }
            dump.destroy()
        }.onFailure { EventLog.log(TAG, "MCU la pornire: ${it.message}") }
    }

    /** Cadrele de stare din mers; același cadru repetat în [NOTE_REPEAT_MS] se scrie o dată. */
    private fun noteMcu(line: String) {
        val note = MCU_IN.find(line)?.let { "← " + compact(it.groupValues[1]) }
            ?: MCU_OUT.find(line)?.let { "→ " + compact(it.groupValues[1]) }
            ?: return
        val now = SystemClock.elapsedRealtime()
        if (note == lastNote && now - lastNoteAt < NOTE_REPEAT_MS) return
        lastNote = note
        lastNoteAt = now
        EventLog.log(TAG, "MCU $note")
    }

    private fun follow() {
        logBootHandshake()
        val started = ProcessBuilder(
            "logcat", "-v", "brief", "-T", "1", "-e", LOG_FILTER, "EventService:I", "*:S"
        ).redirectErrorStream(true).start()
        process = started
        EventLog.log(TAG, "urmăresc marșarierul")
        try {
            started.inputStream.bufferedReader().forEachLine { line ->
                FRAME.find(line)?.let { publish(it.groupValues[2] == "1", it.groupValues[1]) }
                noteMcu(line)
                CAN_FRAME.find(line)?.let { match ->
                    val id = match.groupValues[1].toInt(16)
                    val bytes = BYTE.findAll(match.groupValues[2]).map { it.groupValues[1].toInt(16) }.toList()
                    onCan(id, bytes)
                }
            }
        } finally {
            started.destroy()
        }
    }

    /** Ultimul cadru CAN văzut în jurnal; [KswCarDataSource] retrimite pornirea dacă nu vin. */
    @Volatile
    var lastCanAt = 0L
        private set

    private fun onCan(id: Int, b: List<Int>) {
        lastCanAt = SystemClock.elapsedRealtime()
        when (id) {
            ID_SENSORS -> if (b.size >= SENSORS) onSensors(b.take(SENSORS))
            ID_STEERING -> if (b.size >= 2) {
                val raw = ((b[1] shl 8) or b[0]).toShort().toInt()
                _steering.value = (raw / STEERING_FULL).coerceIn(-1f, 1f)
            }
            ID_GEAR -> if (b.isNotEmpty()) onGear(b[0])
            ID_MESSAGE -> if (b.size >= 3) onCarMessage(b[0], b[1], b[2])
            ID_DSC_STATE -> if (b.size >= 2) {
                dscTrack.read(b[1] and DSC_OFF_BIT != 0)?.let { (on, live) -> setDrift(on, live) }
            }
            ID_GEARBOX -> if (b.size >= 5) {
                LEVER[b[0]]?.let { pos -> leverTrack.read(pos)?.let { (p, live) -> setLever(p, live) } }
                if (b[0] == GEARBOX_D) {
                    sportTrack.read(b[4] and 0x0F != 0)?.let { (on, live) -> setSport(on, live) }
                }
            }
            ID_CAR_TIME -> onCarTime(b)
            ID_BATTERY -> if (b.size >= 2) {
                // E9x PM: (b1 & 0x0F) << 8 | b0, câte 15 mV (2026-11-15, din capturi: 13,96-14,62 V în mers).
                val raw = ((b[1] and 0x0F) shl 8) or b[0]
                if (raw in 1..0xFFE) _batteryVolts.value = raw * 0.015f
            }
        }
    }

    /** Ceasul din bord: ora, minut, secundă, zi, lună în nibble-ul de sus, an pe 2 octeți LE. */
    private fun onCarTime(b: List<Int>) {
        if (b.size < 7) return
        val hour = b[0]; val min = b[1]; val sec = b[2]; val day = b[3]
        val month = b[4] shr 4
        val year = b[5] or (b[6] shl 8)
        if (hour > 23 || min > 59 || sec > 59 || day !in 1..31 || month !in 1..12 || year !in 2020..2099) return
        val local = java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, hour, min, sec)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        ro.e92.launcher.core.GpsClock.onCarTime(local)
    }

    private fun onSensors(raw: List<Int>) {
        _sensorCm.value = raw.map { value ->
            if (value >= RAW_NOTHING) NO_OBSTACLE_CM else (value - SENSOR_MARGIN_CM).coerceAtLeast(0)
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRawLogAt >= RAW_LOG_MS) {
            lastRawLogAt = now
            EventLog.log(TAG, "senzori brut: " + raw.joinToString(" "))
        }
    }

    /**
     * Doar jurnal: valoarea se schimbă și cu treapta de viteză din mers (C6 în D), iar
     * salutul Sport pornea singur. Îl legăm din nou după ce decodăm cadrul.
     */
    private fun onGear(value: Int) {
        if (value == lastGear) return
        lastGear = value
        EventLog.log(TAG, "treaptă brut: %02x".format(value))
    }

    private fun onCarMessage(code: Int, group: Int, status: Int) {
        val message = (group shl 8) or code
        if (seenMessages.add(message)) EventLog.log(TAG, "mesaj bord: cod %02x %02x".format(group, code))
    }

    private fun setDrift(on: Boolean, live: Boolean) {
        if (_driftMode.value == on) return
        EventLog.log(TAG, "sisteme " + (if (on) "oprite" else "normale") + if (live) "" else " (la prima citire)")
        _driftMode.value = on
        if (live) _modeEvents.tryEmit(if (on) ModeScene.DRIFT else ModeScene.NORMAL)
    }

    private fun setSport(on: Boolean, live: Boolean) {
        if (_sportMode.value == on) return
        EventLog.log(TAG, (if (on) "Drive Sport" else "Drive") + if (live) "" else " (la prima citire)")
        _sportMode.value = on
        if (live) _modeEvents.tryEmit(if (on) ModeScene.SPORT else ModeScene.DRIVE)
    }

    /**
     * Maneta s-a mutat. Din P sau N în D aduce cardul Comfort (o trecere lină pe Home, nu un text
     * schimbat brusc). Ieșirea din D anulează și Sport: cutia revine în D normal.
     */
    private fun setLever(pos: Char, live: Boolean) {
        val old = _lever.value
        if (old == pos) return
        EventLog.log(TAG, "manetă: $pos" + if (live) "" else " (la prima citire)")
        _lever.value = pos
        if (pos != 'D' && _sportMode.value == true) _sportMode.value = false
        if (live && pos == 'D' && (old == 'P' || old == 'N')) _modeEvents.tryEmit(ModeScene.DRIVE)
    }

    /**
     * O stare citită din cadre repetate: se schimbă abia după [STABLE_FRAMES] cadre la fel,
     * iar după o pauză mai lungă de [STATE_GAP_MS] prima valoare e doar punct de plecare.
     */
    private class ModeTrack<T> {
        private var lastAt = 0L
        private var candidate: T? = null
        private var same = 0
        private var fresh = true

        /** Întoarce (valoarea, e schimbare văzută în direct) când valoarea s-a așezat. */
        fun read(value: T): Pair<T, Boolean>? {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAt > STATE_GAP_MS) {
                fresh = true
                candidate = null
            }
            lastAt = now
            if (candidate != value) {
                candidate = value
                same = 0
            }
            if (++same != STABLE_FRAMES) return null
            val live = !fresh
            fresh = false
            return value to live
        }
    }

    /** Pentru probe fără mașină, din DiagServer: valorile brute ale cadrelor. */
    fun simulateCan(
        raw: List<Int>?, steer: Int?, gear: Int?, drift: Boolean?, sport: Boolean? = null, lever: Char? = null
    ) {
        lever?.let { setLever(it, _lever.value != null) }
        raw?.let { onSensors(it) }
        steer?.let { onCan(ID_STEERING, listOf(it and 0xFF, (it shr 8) and 0xFF)) }
        gear?.let { onGear(it) }
        // Ca în mașină: prima valoare e punct de plecare, a doua (diferită) aduce scena.
        drift?.let { setDrift(it, _driftMode.value != null) }
        sport?.let { setSport(it, _sportMode.value != null) }
    }

    /** Scena cerută direct, pentru poze din emulator. */
    fun simulateScene(scene: ModeScene) {
        _modeEvents.tryEmit(scene)
    }

    private fun publish(on: Boolean, frameType: String = "?") {
        val value = if (on) true else null
        if (_state.value.reverseGear == value && _state.value.timestamp != 0L) return
        if (on) {
            val camera = KswSettings.read(context, CAMERA_SELECTION)
            // Tipul din cadru (1 aftermarket, 2 360, 3 originală) arată ce crede MCU-ul că are.
            EventLog.log(TAG, "marșarier (cadru MCU tip $frameType, tip cameră KSW $camera)")
        } else if (_state.value.reverseGear == true) {
            EventLog.log(TAG, "ieșit din marșarier")
        }
        if (!on) _sensorCm.value = null
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
            // Radarul și liniile KSW: EventCenter le retrimite MCU-ului din setările lui la pornire,
            // deci le scriem și acolo, cu aceleași valori ca la MCU (Prefs.mcuRadar / mcuTrack).
            // Implicit pornite (2026-11-16): cât ecranul KSW tot apare de la al doilea R, e mai
            // util cu senzori și volan decât gol. Opțiunea oprită le repune pe 1.
            val p = Services.prefs
            // „Video original BMW": cu 1, EventCenter deschide la pornire fereastra lui de video
            // original, care fără ecran BMW arată „No Signal" peste tot (2026-11-16). Cu 0
            // pornește în schimb aplicația KSW de meniu original, pe care launcher-ul o închide.
            if (p.mcuOrigVideo >= 0) {
                val want = if (p.reverseScene) p.mcuOrigVideo.toString() else "1"
                if (KswSettings.read(context, ORIG_VIDEO) != want) {
                    KswSettings.write(context, ORIG_VIDEO, want)
                    EventLog.log(TAG, "$ORIG_VIDEO → $want")
                }
            }
            for ((key, value) in listOf(RADAR to p.mcuRadar, TRACK to p.mcuTrack)) {
                val want = if (p.reverseScene) value.toString() else "1"
                if (KswSettings.read(context, key) != want) {
                    KswSettings.write(context, key, want)
                    EventLog.log(TAG, "$key → $want")
                }
            }
            val wanted = if (Services.prefs.reverseScene) CAMERA_NONE else CAMERA_AFTERMARKET
            val current = KswSettings.read(context, CAMERA_SELECTION) ?: return@launch
            // Oprită, opțiunea nu atinge nimic decât dacă noi am pus 0 mai devreme.
            if (current == wanted || (!Services.prefs.reverseScene && current != CAMERA_NONE)) return@launch
            KswSettings.write(context, CAMERA_SELECTION, wanted)
            // Acțiunea e protejată: de la o aplicație obișnuită aruncă SecurityException și
            // omoară launcher-ul (2026-11-07). EventCenter citește valoarea la pornire.
            EventLog.log(TAG, "tip cameră KSW $current → $wanted, se aplică la următoarea pornire")
        }
    }

    private companion object {
        const val TAG = "Reverse"
        val FRAME = Regex("""0xf2 0x00 0x11 0x02 0x0([123]) 0x0([01])""")
        const val LOG_FILTER =
            "0xf2 0x00 0x11 0x02|0xA1_0x1DEvent: stringBuilder.toString\\(\\) = 0x(01c2|00c4|0304|0338|02f8|03b4|019e|01d2) |bydata = 0xf2 0x00 0x1[035] |bydata = 0xf2 0x00 0xa1 0x02 0x1a |ES s send: 0xf2 0x00 0x6[79] "
        val CAN_FRAME = Regex("""0xA1_0x1DEvent: .*= 0x([0-9a-f]{4}) \S+ \S+ 0x[0-9a-f]+ ((?:0x[0-9a-f]{2} ?)+)""")
        val BYTE = Regex("""0x([0-9a-f]{2})""")

        /**
         * Cadre de stare ale MCU-ului, pentru diagnoza marșarierului: 0x10 (alimentare și biți de
         * cameră), 0x13 (setările MCU-ului, răspuns la cererea 0x68 01 00), 0x15 (schimbare de mod
         * și sursă cerută de MCU; vine la ieșirea din primul R), A1 1A (intrare/ieșire din modul
         * original). 0x11 are jurnalul lui, la marșarier.
         */
        val MCU_IN = Regex("""bydata = ((?:0xf2 0x00 0x1[02-9] |0xf2 0x00 0xa1 0x02 0x1a )(?:0x[0-9a-f]{2} ?)*)""")
        /** La pornire le vrem pe toate, inclusiv marșarierele (0x11) de dinainte să pornim noi. */
        val BOOT_IN = Regex("""bydata = ((?:0xf2 0x00 0x1[0-9] |0xf2 0x00 0xa1 0x02 0x1a )(?:0x[0-9a-f]{2} ?)*)""")
        /** Ce trimite EventCenter (și noi, prin el) ca sursă (0x67) și mod de afișare (0x69). */
        val MCU_OUT = Regex("""ES s send: (0xf2 0x00 0x6[79] (?:0x[0-9a-f]{2} ?)*)""")
        /** La pornire: și setările (0x61, 0x63, 0x6A, 0x70) pe care EventCenter le trimite MCU-ului. */
        val BOOT_OUT = Regex("""ES s send: (0xf2 0x00 (?:0x6[1379a]|0x70) (?:0x[0-9a-f]{2} ?)*)""")
        const val BOOT_FILTER =
            "bydata = 0xf2 0x00 0x1[0-9] |bydata = 0xf2 0x00 0xa1 0x02 0x1a |ES s send: 0xf2 0x00 0x6[1379a] |ES s send: 0xf2 0x00 0x70 "
        const val BOOT_MAX = 80
        const val NOTE_REPEAT_MS = 2_000L

        const val ID_SENSORS = 0x1C2
        const val ID_STEERING = 0x0C4
        const val ID_GEAR = 0x304
        const val ID_MESSAGE = 0x338
        const val ID_CAR_TIME = 0x2F8
        const val ID_BATTERY = 0x3B4
        const val SENSORS = 4
        const val RAW_NOTHING = 0xFE
        /** Ce ține minte șoferul la ultimul roșu: 8 cm reali arătați ca 4, de siguranță. */
        const val SENSOR_MARGIN_CM = 4
        const val RAW_LOG_MS = 2_000L
        /** Volanul la capăt (2026-11-07); semnul pozitiv = stânga, de confirmat în mașină. */
        const val STEERING_FULL = 10_400f
        const val ID_DSC_STATE = 0x19E
        const val DSC_OFF_BIT = 0x10
        const val ID_GEARBOX = 0x1D2
        const val GEARBOX_D = 0x78
        val LEVER = mapOf(0xE1 to 'P', 0xD2 to 'R', 0xB4 to 'N', 0x78 to 'D')
        const val STABLE_FRAMES = 3
        const val STATE_GAP_MS = 3_000L

        const val RADAR_ACTION = "com.szchoiceway.eventcenter.MCU_CAR_CAN_RADAR_INFO"
        const val RADAR_DATA = "com.szchoiceway.eventcenter.CAR_CAN_DATA"
        const val CAMERA_SELECTION = "KESAIWEI_SYS_CAMERA_SELECTION"
        const val ORIG_VIDEO = "KSW_ORIGINAL_CAR_VIDEO_DISPLAY"
        const val RADAR = "KESAIWEI_SYS_RADAR"
        const val TRACK = "KESAIWEI_SYS_REVERSING_TRACK"
        const val CAMERA_NONE = "0"
        const val CAMERA_AFTERMARKET = "1"

        const val PERMISSION_RECHECK_MS = 30_000L
        const val RESTART_MS = 3_000L
    }
}
