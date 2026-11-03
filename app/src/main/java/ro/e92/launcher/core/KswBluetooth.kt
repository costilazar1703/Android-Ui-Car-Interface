package ro.e92.launcher.core

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bluetooth-ul telefonului pe unitatea KSW, comandat direct de launcher.
 *
 * Pe unitatea asta Android nu vede telefonul: modulul (BT2010 pe /dev/ttyS4)
 * e ținut de aplicația vendorului, `com.szchoiceway.btsuite`. Ea expune însă
 * `BTService` exportat și fără permisiune, cu interfața AIDL
 * `com.szchoiceway.btsuite.IBTService` - aceeași pe care o folosește propriul
 * ei ecran. Ne legăm la el și trimitem tranzacțiile direct prin Binder, cu
 * codurile citite din aplicația decompilată (vezi [Tx]), fără să copiem AIDL-ul.
 *
 * Starea vine pe două căi: la legare și după fiecare comandă o citim din
 * serviciu, iar între timp ascultăm broadcast-urile `HBCP_EVT_*` pe care
 * serviciul le trimite la orice schimbare.
 */
class KswBluetooth(private val context: Context, private val scope: CoroutineScope) {

    enum class Link { UNAVAILABLE, IDLE, CONNECTING, CONNECTED, IN_CALL }

    enum class Call { NONE, INCOMING, OUTGOING, ACTIVE }

    data class State(
        val link: Link = Link.UNAVAILABLE,
        /** Unitatea e în modul de împerechere: telefonul o vede și se poate lega. */
        val visible: Boolean = false,
        /** Rezultatul ultimei împerecheri, null cât nu s-a terminat niciuna. */
        val lastPairResult: Int? = null,
        val audioStreaming: Boolean = false,
        /** Titlul piesei de pe telefon, singurul câmp de metadata pe care îl dă modulul. */
        val songTitle: String = "",
        val phoneName: String = "",
        val unitName: String = "",
        val pin: String = "",
        val call: Call = Call.NONE,
        /** Numărul celui care sună / e sunat; gol cât nu l-a trimis modulul. */
        val callNumber: String = "",
        /** Numele din agenda telefonului, când modulul îl are. */
        val callName: String = "",
        /** SystemClock.elapsedRealtime() la care a început convorbirea; 0 = nu vorbim. */
        val callStartedAt: Long = 0L,
        /**
         * Modulul n-a raportat nimic de un minut după pornire / contact și nici
         * nu iese din „initialising": serviciul KSW nu-i mai vorbește (vezi
         * [startWatchdog]). Doar o repornire a navigației îl readuce.
         */
        val moduleSilent: Boolean = false,
        /**
         * Am readus tipul modulului la portul direct după ce l-am găsit pe altceva (de
         * obicei BC5, după un reset). bTSuite îl citește doar la pornire, deci
         * contează abia după o repornire a navigației.
         */
        val restartNeeded: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var binder: IBinder? = null
    private var started = false

    val isInstalled: Boolean
        get() = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    fun start() {
        if (started) return
        started = true
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(EVT_HSHF_STATUS)
            addAction(EVT_AV_STATUS)
            addAction(EVT_PAIR_STATUS)
            addAction(EVT_DEVICE_NAME)
            addAction(EVT_DEVICE_PINCODE)
            addAction(EVT_CONTACT_NUM)
            addAction(EVT_CONTACT_ADDRESS)
        })
        context.registerReceiver(wakeReceiver, IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(ACTION_ACC)
        })
        if (isInstalled) {
            scope.launch {
                // Ordinea contează: BTService își deschide portul o singură
                // dată, la creare, iar bind() îl creează dacă nu rulează. Deci
                // întâi tipul și puntea, abia apoi legătura cu serviciul.
                keepModeMemoryOff()
                val typeChanged = keepModuleType()
                activeModuleType = Services.prefs.btModuleType
                val bridgeStarted = withContext(Dispatchers.IO) { ensureBridge() }
                bind()
                if (typeChanged || bridgeStarted) restartKswService()
                startWatchdog("pornire")
                watchBridge()
            }
        }
    }

    /**
     * Stiva Bluetooth a unității e serviciul init „feasycom"
     * (/oem/bin/blueware /dev/ttyS4 921600): vorbește HCI cu cipul CSR și
     * expune /dev/goc_serial, pe care bTSuite trimite comenzile. Serviciul e
     * „disabled" în /oem/etc/init/init.rc și pe build-ul ăsta nimic nu-l mai
     * pornește - de aici „Initialising" la nesfârșit. Îl pornim noi; SELinux e
     * permisiv pe unitate, deci ctl.start e acceptat și de la o aplicație.
     *
     * @return true dacă puntea a apărut acum (BTService trebuie redeschis)
     */
    private fun ensureBridge(): Boolean {
        if (File(GOC_SERIAL).exists()) return false
        Log.w(TAG, "$GOC_SERIAL lipsește - pornesc serviciul $BRIDGE_SERVICE")
        runCatching {
            Runtime.getRuntime().exec(arrayOf("setprop", "ctl.start", BRIDGE_SERVICE)).waitFor()
        }.onFailure { Log.w(TAG, "nu pot porni $BRIDGE_SERVICE", it) }
        repeat(BRIDGE_WAIT_STEPS) {
            if (File(GOC_SERIAL).exists()) {
                Log.i(TAG, "$GOC_SERIAL a apărut")
                return true
            }
            Thread.sleep(BRIDGE_WAIT_STEP_MS)
        }
        // Fără punte, repornirea BTService n-ar schimba nimic; o lăsăm în pace.
        Log.w(TAG, "$GOC_SERIAL tot lipsește după pornirea $BRIDGE_SERVICE")
        return false
    }

    /**
     * BTService deschide portul doar în onCreate. Dacă a pornit înaintea
     * punții (sau cu alt tip de modul), îl oprim și îl pornim iar ca să-l
     * redeschidă. Merge doar dacă nimeni altcineva nu e legat de el; altfel
     * rămâne aviz „repornește o dată" - la pornire puntea vine acum prima.
     */
    private suspend fun restartKswService() {
        val component = Intent().setClassName(PACKAGE, SERVICE_CLASS)
        Log.w(TAG, "repornesc BTService ca să redeschidă $GOC_SERIAL")
        runCatching { context.unbindService(connection) }
        binder = null
        runCatching { context.stopService(component) }
            .onFailure { Log.w(TAG, "stopService BTService", it) }
        delay(SERVICE_RESTART_GAP_MS)
        runCatching { context.startService(component) }
            .onFailure { Log.w(TAG, "startService BTService", it) }
        delay(SERVICE_RESTART_GAP_MS)
        bind()
    }

    /**
     * bTSuite vorbește cu modulul prin puntea Feasycom doar cu tipurile 1-5.
     * Ținem tipul ales în Bluetooth → Phone (implicit BC6). bTSuite îl citește
     * doar la crearea BTService, deci o schimbare cere redeschiderea lui.
     *
     * @return true dacă a trebuit schimbat
     */
    private fun keepModuleType(): Boolean {
        val wanted = Services.prefs.btModuleType
        val current = KswSettings.read(context, KswSettings.BT_TYPE)
        Log.i(TAG, "tipul modulului Bluetooth: $current (vrem $wanted)")
        if (current == wanted) return false
        val written = KswSettings.write(context, KswSettings.BT_TYPE, wanted)
        if (written) Log.w(TAG, "tipul modulului era $current - l-am pus pe $wanted")
        return written
    }

    /**
     * Alegerea din meniu: scrie tipul acum, dar BTService îl citește doar la
     * pornire. Nu-l repornim de aici - ar rupe o legătură în curs; cere o
     * repornire a unității, după care pornirea îl ia în ordinea bună.
     */
    fun setModuleType(value: String) {
        Services.prefs.btModuleType = value
        scope.launch {
            if (KswSettings.write(context, KswSettings.BT_TYPE, value)) {
                // Înapoi la tipul cu care a pornit BTService nu cere repornire.
                _state.value = _state.value.copy(restartNeeded = value != activeModuleType)
            }
        }
    }

    /** Tipul cu care a pornit BTService la pornirea asta. */
    @Volatile
    private var activeModuleType: String? = null

    /** Unitatea nu mai redeschide ecranul Bluetooth KSW peste salut la pornire. */
    private fun keepModeMemoryOff() {
        if (KswSettings.read(context, KswSettings.MODE_MEMORY) != "0") {
            KswSettings.write(context, KswSettings.MODE_MEMORY, "0")
            Log.i(TAG, "am oprit reluarea ultimei surse la pornire")
        }
    }

    /**
     * Stiva Feasycom e un serviciu „oneshot": dacă moare, init nu-l repornește
     * și Bluetooth-ul rămâne mort până la următorul contact. O verificăm rar
     * (un test de fișier) și, dacă puntea a dispărut, o pornim și redeschidem
     * BTService. Cu puntea vie nu atingem nimic.
     */
    private fun watchBridge() {
        scope.launch {
            while (true) {
                delay(BRIDGE_CHECK_MS)
                val restarted = withContext(Dispatchers.IO) { ensureBridge() }
                if (restarted) {
                    Log.w(TAG, "puntea Bluetooth căzuse - am repornit-o")
                    restartKswService()
                }
            }
        }
    }

    // ------------------------------------------------------------- watchdog
    //
    // De ce există: în BTService (bTSuite) comenzile care pornesc conectarea -
    // „auto-connect", „conectează ultimul telefon" - se trimit O SINGURĂ DATĂ,
    // la crearea serviciului (checkBTStatus). Unitatea însă nu repornește la
    // contact, doar adoarme; la trezire modulul repornește și raportează
    // „Power ON Init", iar serviciul doar își resetează starea, fără să mai
    // ceară conectarea. De aici „uneori se conectează, uneori nu".
    //
    // Al doilea mod de eșec: dacă la crearea serviciului portul serial nu e
    // încă gata, openSerialPort() înghite eroarea și nu mai reîncearcă - modulul
    // e pornit, dar nimeni nu-i mai vorbește până la o repornire a navigației.
    // Pe acela nu-l putem repara din afară; îl recunoaștem și îl spunem.

    private var watchdog: kotlinx.coroutines.Job? = null
    @Volatile
    private var lastModuleEventAt = 0L
    private var screenOffAt = 0L

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> screenOffAt = android.os.SystemClock.elapsedRealtime()
                android.content.Intent.ACTION_SCREEN_ON -> {
                    val off = screenOffAt
                    screenOffAt = 0L
                    if (off != 0L && android.os.SystemClock.elapsedRealtime() - off > WAKE_AFTER_MS) startWatchdog("trezire")
                }
                ACTION_ACC -> if (!intent.getBooleanExtra(EXTRA_ACC_OFF, true)) startWatchdog("contact")
            }
        }
    }

    /**
     * Repetă, o dată sau de două ori, exact ce face serviciul KSW la prima lui
     * pornire: conectează ultimul telefon (HFP, apoi A2DP). Nimic dacă telefonul
     * e deja legat, dacă e un apel sau dacă unitatea e în modul de împerechere -
     * acolo ar strica ce face șoferul.
     */
    private fun startWatchdog(reason: String) {
        if (!isInstalled) return
        watchdog?.cancel()
        val started = android.os.SystemClock.elapsedRealtime()
        watchdog = scope.launch {
            Log.i(TAG, "watchdog ($reason)")
            repeat(RECONNECT_ATTEMPTS) { attempt ->
                delay(RECONNECT_AFTER_MS)
                if (binder == null) bind()
                refreshNow()
                val s = _state.value
                if (s.link >= Link.CONNECTING || s.visible || s.call != Call.NONE) return@launch
                if (s.link == Link.IDLE) {
                    Log.i(TAG, "telefonul nu e legat după contact - reconectez (încercarea ${attempt + 1})")
                    call(Tx.CONNECT)
                    delay(STEP_MS)
                    call(Tx.CON_LAST_A2DP)
                }
            }
            // Tăcerea: niciun eveniment de la modul și serviciul tot „initialising".
            val waited = android.os.SystemClock.elapsedRealtime() - started
            if (waited < SILENT_AFTER_MS) delay(SILENT_AFTER_MS - waited)
            val quiet = lastModuleEventAt < started
            val stuck = callInt(Tx.GET_CONNECT_STATE).let { it == null || it == 0 }
            if (quiet && stuck && binder != null) {
                Log.w(TAG, "modulul nu răspunde - BTService nu mai vorbește cu el")
                _state.value = _state.value.copy(moduleSilent = true)
            }
        }
    }

    private fun bind() {
        val intent = Intent(ACTION_SERVICE).setPackage(PACKAGE)
        val ok = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!ok) Log.w(TAG, "bindService a refuzat BTService")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Procesul vendorului a murit; BIND_AUTO_CREATE îl repornește și
            // primim iar onServiceConnected.
            binder = null
            _state.value = _state.value.copy(link = Link.UNAVAILABLE)
        }
    }

    // ------------------------------------------------------------- comenzi

    /** Unitatea devine vizibilă, ca telefonul s-o găsească și să se împerecheze. */
    fun makeVisible() = command {
        // Modul de împerechere cu un telefon legat îl poate deconecta; un
        // telefon nou se adaugă doar când nu e niciunul conectat.
        if (_state.value.link >= Link.CONNECTED) {
            Log.i(TAG, "nu intru în împerechere cu un telefon conectat")
            return@command
        }
        call(Tx.PAIR_DEVICE) { it.writeInt(0) }
    }

    /** Reconectează ultimul telefon: apeluri (HFP) și muzică (A2DP). */
    fun reconnectLast() = command {
        call(Tx.CONNECT)
        delay(STEP_MS)
        call(Tx.CON_LAST_A2DP)
    }

    /**
     * Ce făceai de mână când telefonul nu mai vedea unitatea, fără repornirea
     * navigației: rupe legătura agățată, iese din orice împerechere începută și
     * intră din nou în modul vizibil. Telefonul cu „conectare automată" se
     * leagă singur; altfel îl alegi din lista lui de Bluetooth.
     */
    fun reset() = command {
        call(Tx.DISCONNECT_A2DP)
        call(Tx.DISCONNECT)
        delay(STEP_MS)
        call(Tx.CANCEL_PAIR)
        delay(STEP_MS)
        call(Tx.PAIR_DEVICE) { it.writeInt(0) }
    }

    /** Șterge toate telefoanele memorate; modulul intră apoi singur în modul vizibil. */
    fun forgetAll() = command { call(Tx.DEL_ALL_PAIRED) }

    // -------------------------------------------------------------- apeluri

    fun answer() = command { call(Tx.ACCEPT_CALL) }

    /** Respinge un apel care sună sau închide unul în curs - pe KSW e aceeași comandă. */
    fun hangUp() = command { call(Tx.END_CALL) }

    /** Convorbirea trece pe telefon (difuzorul mașinii tace). */
    fun transferToPhone() = command { call(Tx.TRANSFER_TO_PHONE) }

    fun redial() = command { call(Tx.DIAL_LAST) }

    fun dial(number: String) = command { call(Tx.DIAL_NUMBER) { it.writeString(number) } }

    fun playPause() = command { call(Tx.PLAY_PAUSE) }
    fun next() = command { call(Tx.NEXT) }
    fun previous() = command { call(Tx.PREV) }

    /**
     * Numele sub care telefonul vede unitatea și PIN-ul. Doar la cerere, din
     * ecranul Bluetooth: o comandă trimisă modulului la fiecare pornire putea
     * rupe legătura cu telefonul, iar stabilitatea ei e mai importantă.
     * Se trimite doar ce diferă de ce raportează modulul.
     */
    fun applyIdentity() = command {
        val name = Services.prefs.btUnitName
        val current = callString(Tx.GET_DEVICE_NAME)
        if (name.isNotBlank() && current != null && current != name) {
            call(Tx.CHANGE_NAME) { it.writeString(name) }
            delay(STEP_MS)
        }
        val pin = callString(Tx.GET_PINCODE)
        if (pin != null && pin != UNIT_PIN) call(Tx.SET_PIN) { it.writeString(UNIT_PIN) }
    }

    /** Rulează pe fundal, apoi recitește starea - ecranul arată imediat efectul. */
    private fun command(block: suspend () -> Unit) {
        scope.launch {
            if (binder == null) bind()
            runCatching { block() }.onFailure { Log.w(TAG, "comanda a eșuat", it) }
            delay(STEP_MS)
            refresh()
        }
    }

    // --------------------------------------------------------------- stare

    fun refresh() {
        scope.launch { refreshNow() }
    }

    private fun refreshNow() {
        if (binder == null) return
        val link = linkOf(callInt(Tx.GET_CONNECT_STATE) ?: return)
        _state.value = _state.value.copy(
            link = link,
            visible = callInt(Tx.GET_PAIR_STATE) == PAIR_IN_PROGRESS,
            audioStreaming = callInt(Tx.GET_A2DP_STATE) == AV_STREAMING,
            phoneName = if (link >= Link.CONNECTED) callString(Tx.GET_CONNECT_PHONE_NAME).orEmpty() else "",
            unitName = callString(Tx.GET_DEVICE_NAME) ?: _state.value.unitName,
            pin = callString(Tx.GET_PINCODE) ?: _state.value.pin
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            intent ?: return
            val value = intent.getIntExtra(EXTRA_INT, -1)
            val text = intent.getStringExtra(EXTRA_STR)
            val s = _state.value
            _state.value = when (intent.action) {
                EVT_HSHF_STATUS -> {
                    val call = callOf(value)
                    s.copy(
                        link = linkOf(value),
                        phoneName = if (linkOf(value) >= Link.CONNECTED) s.phoneName else "",
                        call = call,
                        callNumber = if (call == Call.NONE) "" else s.callNumber,
                        callName = if (call == Call.NONE) "" else s.callName,
                        callStartedAt = when {
                            call != Call.ACTIVE -> 0L
                            s.callStartedAt != 0L -> s.callStartedAt
                            else -> android.os.SystemClock.elapsedRealtime()
                        }
                    )
                }
                EVT_CONTACT_NUM -> s.copy(callNumber = text ?: s.callNumber)
                // „Address" e, în ciuda numelui, numele din agendă al apelantului.
                EVT_CONTACT_ADDRESS -> s.copy(callName = text?.takeIf { it.isNotBlank() } ?: s.callName)
                EVT_AV_STATUS -> s.copy(
                    audioStreaming = value == AV_STREAMING,
                    songTitle = text?.trim() ?: s.songTitle
                )
                EVT_PAIR_STATUS -> s.copy(
                    visible = value == PAIR_IN_PROGRESS,
                    lastPairResult = value.takeIf { it != PAIR_IN_PROGRESS }
                )
                EVT_DEVICE_NAME -> s.copy(unitName = text ?: s.unitName)
                EVT_DEVICE_PINCODE -> s.copy(pin = text ?: s.pin)
                else -> s
            }
            // Numele telefonului nu vine pe broadcast: îl cerem la conectare.
            if (intent.action == EVT_HSHF_STATUS && linkOf(value) == Link.CONNECTED) refresh()
            // Ecranul Bluetooth KSW trimite „mute music" când se închide și abia
            // la redeschidere „unmute" - de aici muzica auzită doar cu ecranul
            // lor deschis. La fiecare pornire a redării deblocăm noi sunetul.
            if (intent.action == EVT_AV_STATUS && value == AV_STREAMING && !s.audioStreaming) {
                scope.launch { call(Tx.UNMUTE_MUSIC) }
            }
            // Numărul și numele apelantului le cerem și direct, dacă broadcast-ul întârzie.
            if (intent.action == EVT_HSHF_STATUS && callOf(value) != Call.NONE) refreshCaller()
            // Orice veste de la modul înseamnă că serviciul KSW încă îi vorbește.
            if (intent.action == EVT_HSHF_STATUS || intent.action == EVT_AV_STATUS || intent.action == EVT_PAIR_STATUS) {
                lastModuleEventAt = android.os.SystemClock.elapsedRealtime()
                if (_state.value.moduleSilent) _state.value = _state.value.copy(moduleSilent = false)
            }
        }
    }

    private fun refreshCaller() {
        scope.launch {
            val number = callString(Tx.GET_CONTACT_NUM).orEmpty().takeIf { it.any(Char::isDigit) }
            val name = callString(Tx.GET_CONTACT_NAME).orEmpty().takeIf { it.isNotBlank() && it.none(::isPlaceholderChar) }
            val s = _state.value
            if (s.call == Call.NONE) return@launch
            _state.value = s.copy(
                callNumber = number ?: s.callNumber,
                callName = name ?: s.callName
            )
        }
    }

    /** Valorile implicite ale vendorului („未知号码") sunt în chineză: nu le afișăm. */
    private fun isPlaceholderChar(c: Char) = c.code in 0x4E00..0x9FFF

    private fun callOf(hshf: Int): Call = when (hshf) {
        5 -> Call.INCOMING
        4 -> Call.OUTGOING
        6 -> Call.ACTIVE
        else -> Call.NONE
    }

    private fun linkOf(hshf: Int): Link = when (hshf) {
        0, 1 -> Link.IDLE
        2 -> Link.CONNECTING
        3 -> Link.CONNECTED
        4, 5, 6 -> Link.IN_CALL
        else -> Link.UNAVAILABLE
    }

    // -------------------------------------------------------------- Binder

    private fun call(code: Int, write: (Parcel) -> Unit = {}) {
        transact(code, write) { }
    }

    private fun callInt(code: Int): Int? = transact(code) { it.readInt() }

    private fun callString(code: Int): String? = transact(code) { it.readString() }

    private fun <T> transact(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T? {
        val b = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (e: Exception) {
            Log.w(TAG, "tranzacția $code a eșuat", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Codurile din IBTService.Stub, aplicația bTSuite de pe unitate (vezi .unit-dump/NOTES-bluetooth.md). */
    private object Tx {
        const val PAIR_DEVICE = 1
        const val CANCEL_PAIR = 2
        const val CONNECT = 3
        const val DISCONNECT = 4
        const val DIAL_NUMBER = 8
        const val SET_PIN = 18
        const val CHANGE_NAME = 30
        const val DIAL_LAST = 10
        /** cancelDail: pe KSW respinge sau închide (rejectCall e gol la ei). */
        const val END_CALL = 11
        const val ACCEPT_CALL = 12
        const val TRANSFER_TO_PHONE = 15
        const val GET_DEVICE_NAME = 6
        const val DEL_ALL_PAIRED = 7
        const val PLAY_PAUSE = 25
        const val PREV = 26
        const val NEXT = 27
        const val DISCONNECT_A2DP = 28
        const val UNMUTE_MUSIC = 33
        const val CON_LAST_A2DP = 29
        const val GET_CONNECT_STATE = 34
        const val GET_PAIR_STATE = 35
        const val GET_A2DP_STATE = 36
        const val GET_PINCODE = 37
        const val GET_CONNECT_PHONE_NAME = 50
        const val GET_CONTACT_NAME = 41
        const val GET_CONTACT_NUM = 42
    }

    companion object {
        /** Puntea stivei Feasycom spre bTSuite și serviciul init care o face. */
        const val GOC_SERIAL = "/dev/goc_serial"
        const val BRIDGE_SERVICE = "feasycom"
        const val BRIDGE_WAIT_STEPS = 25
        const val BRIDGE_WAIT_STEP_MS = 200L
        const val SERVICE_CLASS = "com.szchoiceway.btsuite.BTService"
        const val SERVICE_RESTART_GAP_MS = 1_500L
        const val BRIDGE_CHECK_MS = 30_000L

        const val PACKAGE = "com.szchoiceway.btsuite"
        private const val ACTION_SERVICE = "com.szchoiceway.btsuite.BTService"
        private const val DESCRIPTOR = "com.szchoiceway.btsuite.IBTService"

        private const val EVT_HSHF_STATUS = "com.szchoiceway.btsuite.HBCP_EVT_HSHF_STATUS"
        private const val EVT_AV_STATUS = "com.szchoiceway.btsuite.HBCP_EVT_AV_STATUS"
        private const val EVT_PAIR_STATUS = "com.szchoiceway.btsuite.HBCP_EVT_PAIR_STATUS"
        private const val EVT_DEVICE_NAME = "com.szchoiceway.btsuite.HBCP_EVT_DEVICE_NAME"
        private const val EVT_DEVICE_PINCODE = "com.szchoiceway.btsuite.HBCP_EVT_DEVICE_PINCODE"
        private const val EVT_CONTACT_NUM = "com.szchoiceway.btsuite.HBCP_EVT_CONTACT_NUM"
        private const val EVT_CONTACT_ADDRESS = "com.szchoiceway.btsuite.HBCP_EVT_CONTACT_ADDRESS"
        private const val EXTRA_INT = "com.szchoiceway.btsuite.DATA_INT"
        private const val EXTRA_STR = "com.szchoiceway.btsuite.DATA_STR"

        const val UNIT_PIN = "0000"
        const val PAIR_SUCCESS = 0
        const val PAIR_TIMEOUT = 1
        private const val PAIR_IN_PROGRESS = 4
        private const val AV_STREAMING = 4

        /** Trimis de EventCenter la contact: extra true = contact oprit. */
        private const val ACTION_ACC = "com.choiceway.eventcenter.EventUtils.KSW_ZXW_ACC_CONTROL_BEFORE"
        private const val EXTRA_ACC_OFF = "com.choiceway.eventcenter.EventUtils.KSW_ZXW_ACC_CONTROL_BEFORE_DATA"

        /** Cât lăsăm modulul și telefonul să se lege singure înainte să le dăm un ghiont. */
        private const val RECONNECT_AFTER_MS = 20_000L
        private const val RECONNECT_ATTEMPTS = 2
        private const val SILENT_AFTER_MS = 60_000L
        /** Ecran stins mai mult de atât = unitatea a dormit, deci modulul a repornit. */
        private const val WAKE_AFTER_MS = 60_000L

        /** Pauza dintre comenzi: modulul serial nu le primește bine lipite. */
        private const val STEP_MS = 600L
        private const val TAG = "KswBluetooth"
    }
}
