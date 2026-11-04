package ro.e92.launcher.core

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.e92.launcher.core.KswBluetooth.Link
import ro.e92.launcher.core.KswBluetooth.State

/**
 * Motorul Bluetooth „E92": doar muzică (A2DP + AVRCP), fără bTSuite.
 *
 * bTSuite a fost problema, nu stiva: crapă la orice intent spre ecranul lui,
 * oprește muzica la fiecare secundă dacă ecranul nu există și cere agenda după
 * fiecare HFP, iar iPhone-ul rupe tot la 15 s (docs/feasycom-protocol.md). Aici
 * vorbim direct cu stiva prin [FeasyLink] și publicăm în același
 * [KswBluetooth.State] ca bTSuite, deci chip-ul, mini-player-ul, ecranele și
 * butoanele de volan nu știu care motor rulează.
 *
 * Apeluri nu există aici: nu trimitem nimic HFP. Dacă telefonul își ridică
 * singur HFP-ul (Apeluri pornit pe iPhone), doar îl trecem în stare și în jurnal.
 */
class FeasyMusic(
    context: Context,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<State>,
    prepareStack: () -> Unit
) : FeasyLink.Listener {

    private val link = FeasyLink(KswBluetooth.GOC_SERIAL, prepareStack, this)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    private var loop: Job? = null

    /** HFP ridicat de telefon și A2DP „conectare în curs" (MU2), din care iese [State.link]. */
    @Volatile
    private var hfp = Link.IDLE
    @Volatile
    private var a2dpConnecting = false
    private var a2dpConnectingSince = 0L

    /** Primul telefon din lista de împerecheate: pe un link doar A2DP nu vine MX0. */
    @Volatile
    private var pairedName = ""
    @Volatile
    private var connectedName = ""

    /** Șoferul a cerut împerechere: până iese din ea nu ne legăm singuri la ultimul telefon. */
    @Volatile
    private var pairingUntil = 0L

    @Volatile
    private var nextConnectAt = 0L
    @Volatile
    private var attempts = 0

    @Volatile
    private var lastLineAt = 0L
    private var lastPingAt = 0L
    private var lastHfpLog = -1

    /** Repetările lui MG: noi nu trimitem DY, deci le poate cere doar bTSuite. */
    private var mgRepeats = 0
    private var mgFirstAt = 0L
    private var lastMg = -1

    /** Primul telefon din lista stivei; gol = stiva nu are niciunul împerecheat. */
    val pairedPhone: String get() = pairedName

    /** Secunde de la ultima linie primită de la stivă. */
    val silentForS: Long get() = (SystemClock.elapsedRealtime() - lastLineAt) / 1000

    fun start() = link.start()

    fun stop() {
        link.stop()
        loop?.cancel()
        abandonFocus()
    }

    /** Contact, trezire, motor: reîncearcă de la capăt, fără pauza lungă. */
    fun kick(reason: String) {
        EventLog.log(TAG, "ghiont ($reason)")
        attempts = 0
        nextConnectAt = SystemClock.elapsedRealtime() + KICK_DELAY_MS
    }

    fun refresh() = ping()

    fun repair() {
        EventLog.log(TAG, "reparare cerută - redeschid legătura")
        link.reopen()
        kick("reparare")
    }

    // ------------------------------------------------------------- comenzi

    fun playPause() {
        val s = state.value
        if (!s.audioConnected) {
            connectLast()
            return
        }
        if (!s.audioStreaming) link.send("VB")
        link.send("MA")
    }

    fun next() = link.send("MD")

    fun previous() = link.send("ME")

    fun connectLast() {
        attempts = 0
        pairingUntil = 0L
        nextConnectAt = SystemClock.elapsedRealtime() + ATTEMPT_MS
        sendConnect()
    }

    private fun sendConnect() {
        // Un al doilea MI cât primul e în curs întoarce doar ERROR.
        if (a2dpConnecting) return
        link.send("MI")
    }

    /** Unitatea devine vizibilă. Cu telefon legat modul de împerechere îl poate rupe. */
    fun makeVisible() {
        if (state.value.phoneConnected) {
            EventLog.log(TAG, "nu intru în împerechere cu un telefon conectat")
            return
        }
        pairingUntil = SystemClock.elapsedRealtime() + PAIRING_MS
        link.send("DA")
    }

    /** Rupe muzica, iese din orice împerechere începută și redevine vizibilă. */
    fun reset() {
        pairingUntil = SystemClock.elapsedRealtime() + PAIRING_MS
        link.send("MJ")
        link.send("DB")
        link.send("DA")
    }

    fun forgetAll() {
        pairingUntil = SystemClock.elapsedRealtime() + PAIRING_MS
        pairedName = ""
        connectedName = ""
        link.send("DV")
    }

    /** Numele și PIN-ul, doar dacă diferă de ce raportează stiva. */
    fun applyIdentity() {
        val s = state.value
        val name = Services.prefs.btUnitName
        if (name.isNotBlank() && s.unitName != name) link.send("MM", name)
        if (s.pin != KswBluetooth.UNIT_PIN) link.send("MN", KswBluetooth.UNIT_PIN)
    }

    private fun ping() {
        lastPingAt = SystemClock.elapsedRealtime()
        link.send("MV", quiet = true)
    }

    // ---------------------------------------------------------------- pty

    /**
     * MH oprește conectarea automată a stivei. Ea cheamă HFP la 14 s („hfp conn
     * to"), iar cu HFP ridicat sau chiar doar încercat cade și muzica. Închiderea
     * cu DD nu e o soluție: la 5 s după ea stiva s-a reinițializat (2026-11-04
     * 13:53). Muzica o reconectăm noi, cu MI.
     */
    override fun onLinkOpen() {
        val now = SystemClock.elapsedRealtime()
        lastLineAt = now
        lastPingAt = now
        hfp = Link.IDLE
        a2dpConnecting = false
        lastMg = -1
        attempts = 0
        nextConnectAt = now + FIRST_CONNECT_MS
        state.update {
            it.copy(link = Link.IDLE, moduleSilent = false, songTitle = Services.prefs.lastSongTitle)
        }
        // Starea reală, dacă launcher-ul a repornit cu telefonul deja legat.
        link.send("MH")
        link.send("MF")
        link.send("MM")
        link.send("MN")
        link.send("MX")
        link.send("MV")
        startLoop()
    }

    override fun onLinkClosed(reason: String) {
        loop?.cancel()
        abandonFocus()
        hfp = Link.IDLE
        a2dpConnecting = false
        connectedName = ""
        state.update {
            it.copy(
                link = Link.UNAVAILABLE,
                visible = false,
                audioConnected = false,
                audioStreaming = false,
                phoneName = "",
                songTitle = "",
                songArtist = "",
                songAlbum = "",
                songDurationMs = 0L
            )
        }
    }

    /**
     * Pasul de un secund: conectarea la ultimul telefon și verificarea stivei.
     * Cât timp telefonul lipsește reîncercăm rar - în mașină apare singur abia
     * când urci, iar o încercare e doar un MI.
     */
    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val s = state.value
                if (a2dpConnecting && now - a2dpConnectingSince > CONNECTING_STUCK_MS) {
                    a2dpConnecting = false
                    publishLink()
                }
                if (pairingUntil != 0L && now > pairingUntil) pairingUntil = 0L
                if (!s.audioConnected && pairingUntil == 0L && now >= nextConnectAt) {
                    attempts++
                    nextConnectAt = now + if (attempts < FAST_ATTEMPTS) ATTEMPT_MS else SLOW_ATTEMPT_MS
                    sendConnect()
                }
                if (now - lastPingAt >= PING_MS) ping()
                val silent = now - lastLineAt > SILENT_MS
                if (silent != s.moduleSilent) {
                    EventLog.log(TAG, if (silent) "stiva nu mai răspunde" else "stiva răspunde din nou")
                    state.update { it.copy(moduleSilent = silent) }
                }
            }
        }
    }

    // --------------------------------------------------------- indicații

    override fun onLine(code: String, data: ByteArray) {
        lastLineAt = SystemClock.elapsedRealtime()
        when (code) {
            "IS" -> onStackInit()
            "II" -> onPairingOn()
            "IJ" -> onPairingOff(data)
            "IV" -> onConnecting()
            "IA" -> onHfp(Link.IDLE, "IA")
            "IB" -> onHfp(Link.CONNECTED, "IB")
            "MG" -> onHfpStatus(digit(data))
            "MU" -> onAvStatus(digit(data))
            "MA" -> onSuspended()
            "MB" -> onPlaying()
            "MY" -> onA2dpGone("MY")
            "MI" -> onTrack(data)
            "MX" -> onDevice(data)
            "MF" -> EventLog.log(TAG, "← MF${text(data)} (răspuns automat, conectare automată)")
            "MM" -> text(data).let { name -> state.update { it.copy(unitName = name) } }
            "MN" -> text(data).let { pin -> state.update { it.copy(pin = pin) } }
            "ER" -> EventLog.log(TAG, "← ERROR")
        }
    }

    private fun onStackInit() {
        EventLog.log(TAG, "← IS: stiva s-a inițializat")
        hfp = Link.IDLE
        a2dpConnecting = false
        clearAudio()
        kick("IS")
    }

    private fun onPairingOn() {
        // Stiva repetă II și cu muzica pornită; nu e o împerechere și nu e o veste.
        val visible = !state.value.audioConnected
        if (visible != state.value.visible) {
            EventLog.log(TAG, "← II: vizibilă=$visible")
            state.update { it.copy(visible = visible) }
        }
    }

    private fun onPairingOff(data: ByteArray) {
        val result = when (digit(data)) {
            1 -> KswBluetooth.PAIR_SUCCESS
            0 -> KswBluetooth.PAIR_TIMEOUT
            else -> null
        }
        EventLog.log(TAG, "← IJ${text(data)}: ieșit din împerechere")
        // IJ simplu vine și din DB, în mijlocul unui reset care intră iar în
        // împerechere; doar un rezultat înseamnă că șoferul a terminat.
        if (result != null) pairingUntil = 0L
        state.update { it.copy(visible = false, lastPairResult = result) }
    }

    private fun onConnecting() {
        EventLog.log(TAG, "← IV: telefonul se conectează")
        a2dpConnecting = true
        a2dpConnectingSince = SystemClock.elapsedRealtime()
        publishLink()
    }

    private fun onHfp(value: Link, line: String) {
        if (value != hfp) {
            EventLog.log(
                TAG,
                "← $line: HFP " + if (value == Link.CONNECTED) "ridicat de telefon" else "căzut"
            )
        }
        hfp = value
        publishLink()
    }

    private fun onHfpStatus(n: Int) {
        watchForeignPolling(n)
        if (n != lastHfpLog) {
            lastHfpLog = n
            EventLog.log(TAG, "← MG$n")
        }
        hfp = when {
            n >= 3 -> Link.CONNECTED
            n == 2 -> Link.CONNECTING
            else -> Link.IDLE
        }
        publishLink()
    }

    /**
     * Noi nu cerem niciodată starea HFP, dar bTSuite o cere la 5 s: dacă MG vine
     * regulat, BTService n-a eliberat pty-ul (cineva îl ține legat). Cei doi s-ar
     * bate pe răspunsuri, iar tipul 6 e deja scris, deci o repornire rezolvă.
     */
    private fun watchForeignPolling(n: Int) {
        val now = SystemClock.elapsedRealtime()
        if (n != lastMg || now - mgFirstAt > FOREIGN_WINDOW_MS) {
            mgFirstAt = now
            mgRepeats = 0
        }
        lastMg = n
        if (++mgRepeats >= FOREIGN_REPEATS && !state.value.restartNeeded) {
            EventLog.log(TAG, "bTSuite încă ține $GOC - e nevoie de o repornire a unității")
            state.update { it.copy(restartNeeded = true) }
        }
    }

    private fun onAvStatus(n: Int) {
        val s = state.value
        when (n) {
            1 -> if (s.audioConnected || a2dpConnecting) onA2dpGone("MU1") else publishLink()
            2 -> if (!s.audioConnected) onConnectingAudio()
            3, 4 -> {
                if (!s.audioConnected || s.audioStreaming) EventLog.log(TAG, "← MU$n")
                markConnected(streaming = false)
            }
            5 -> markConnected(streaming = true)
        }
    }

    private fun onConnectingAudio() {
        EventLog.log(TAG, "← MU2: muzica se conectează")
        a2dpConnecting = true
        a2dpConnectingSince = SystemClock.elapsedRealtime()
        publishLink()
    }

    private fun onSuspended() {
        if (state.value.audioStreaming) EventLog.log(TAG, "← MA: muzica s-a oprit")
        markConnected(streaming = false)
    }

    private fun onPlaying() {
        markConnected(streaming = true)
    }

    private fun markConnected(streaming: Boolean) {
        val before = state.value
        a2dpConnecting = false
        attempts = 0
        pairingUntil = 0L
        if (!before.audioConnected) {
            EventLog.log(TAG, "muzica e legată")
            // Numele vine în MX; pe un link doar A2DP stiva nu-l trimite singură.
            link.send("MX")
        }
        state.update {
            it.copy(
                audioConnected = true,
                audioStreaming = streaming,
                visible = false,
                phoneName = connectedName.ifBlank { pairedName.ifBlank { it.phoneName } }
            )
        }
        publishLink()
        if (streaming && !before.audioStreaming) {
            EventLog.log(TAG, "← MB: muzica cântă")
            // Stiva rămâne pe mut până îi spunem altfel; bTSuite făcea la fel.
            link.send("VB")
            link.send("MZ")
            requestFocus()
        } else if (!streaming && before.audioStreaming) {
            abandonFocus()
        }
    }

    private fun onA2dpGone(line: String) {
        val s = state.value
        a2dpConnecting = false
        connectedName = ""
        if (s.audioConnected) {
            EventLog.log(TAG, "← $line: muzica s-a rupt")
            // A căzut singură: revenim repede, dar tot lăsăm telefonul să respire.
            attempts = 0
            nextConnectAt = SystemClock.elapsedRealtime() + AFTER_DROP_MS
        }
        clearAudio()
        publishLink()
    }

    private fun clearAudio() {
        abandonFocus()
        Services.prefs.lastSongTitle = ""
        state.update {
            it.copy(
                audioConnected = false,
                audioStreaming = false,
                phoneName = if (hfp >= Link.CONNECTED) it.phoneName else "",
                songTitle = "",
                songArtist = "",
                songAlbum = "",
                songDurationMs = 0L
            )
        }
    }

    private fun publishLink() {
        val value = when {
            hfp >= Link.CONNECTED -> Link.CONNECTED
            a2dpConnecting || hfp == Link.CONNECTING -> Link.CONNECTING
            else -> Link.IDLE
        }
        if (value != state.value.link) {
            EventLog.log(TAG, "legătura: ${state.value.link} → $value")
            state.update { it.copy(link = value) }
        }
    }

    private fun onTrack(data: ByteArray) {
        val track = parseTrack(data) ?: return
        Services.prefs.lastSongTitle = track.title
        val s = state.value
        if (track.title != s.songTitle || track.artist != s.songArtist) {
            EventLog.log(TAG, "← piesă: ${track.title} / ${track.artist} / ${track.durationMs / 1000}s")
        }
        state.update {
            it.copy(
                songTitle = track.title,
                songArtist = track.artist,
                songAlbum = track.album,
                songDurationMs = track.durationMs
            )
        }
    }

    private fun onDevice(data: ByteArray) {
        val device = parseDevice(data) ?: return
        if (device.index == 0) {
            connectedName = device.name
        } else if (pairedName.isBlank()) {
            pairedName = device.name
        }
        val s = state.value
        val name = connectedName.ifBlank { pairedName }
        if (s.phoneConnected && name != s.phoneName) {
            EventLog.log(TAG, "← telefon: $name")
            state.update { it.copy(phoneName = name) }
        }
    }

    private fun requestFocus() {
        runCatching {
            audio?.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        runCatching { audio?.abandonAudioFocus(focusListener) }
    }

    private fun digit(data: ByteArray): Int =
        if (data.isEmpty()) -1 else data[0].toInt() - '0'.code

    private fun text(data: ByteArray) = String(data, Charsets.UTF_8)

    data class Track(val title: String, val artist: String, val album: String, val index: Int, val durationMs: Long)

    data class Device(val index: Int, val address: String, val name: String)

    companion object {
        private const val TAG = "FeasyMusic"
        private const val GOC = KswBluetooth.GOC_SERIAL

        /** `MI<titlu>\xff<artist>\xff<album>\xff<nr>\xff<ms>\xff`, fără cele două litere. */
        fun parseTrack(data: ByteArray): Track? {
            val fields = ArrayList<String>(5)
            var start = 0
            for (i in data.indices) {
                if (data[i] == SEPARATOR) {
                    fields.add(String(data, start, i - start, Charsets.UTF_8).trim())
                    start = i + 1
                }
            }
            if (fields.isEmpty()) return null
            return Track(
                title = fields[0],
                artist = fields.getOrElse(1) { "" },
                album = fields.getOrElse(2) { "" },
                index = fields.getOrElse(3) { "" }.toIntOrNull() ?: 0,
                durationMs = fields.getOrElse(4) { "" }.toLongOrNull() ?: 0L
            )
        }

        /** `MX<cifră><adresă de 12><nume>`; 0 = telefonul legat, 1.. = lista de împerecheate. */
        fun parseDevice(data: ByteArray): Device? {
            if (data.size < DEVICE_NAME_AT) return null
            return Device(
                index = data[0].toInt() - '0'.code,
                address = String(data, 1, DEVICE_NAME_AT - 1, Charsets.ISO_8859_1),
                name = String(data, DEVICE_NAME_AT, data.size - DEVICE_NAME_AT, Charsets.UTF_8).trim()
            )
        }

        private const val SEPARATOR = 0xFF.toByte()
        private const val DEVICE_NAME_AT = 13

        private const val TICK_MS = 1_000L
        private const val FIRST_CONNECT_MS = 3_000L
        private const val KICK_DELAY_MS = 3_000L
        private const val AFTER_DROP_MS = 10_000L
        /** Primele încercări sunt dese, apoi rare: telefonul poate lipsi tot drumul. */
        private const val FAST_ATTEMPTS = 3
        private const val ATTEMPT_MS = 20_000L
        private const val SLOW_ATTEMPT_MS = 60_000L
        private const val CONNECTING_STUCK_MS = 30_000L
        private const val PAIRING_MS = 3 * 60_000L
        private const val PING_MS = 30_000L
        private const val SILENT_MS = 95_000L
        private const val FOREIGN_WINDOW_MS = 30_000L
        private const val FOREIGN_REPEATS = 4
    }
}
