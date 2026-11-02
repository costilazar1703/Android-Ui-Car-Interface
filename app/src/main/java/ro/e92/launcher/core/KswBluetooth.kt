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

    data class State(
        val link: Link = Link.UNAVAILABLE,
        /** Unitatea e în modul de împerechere: telefonul o vede și se poate lega. */
        val visible: Boolean = false,
        /** Rezultatul ultimei împerecheri, null cât nu s-a terminat niciuna. */
        val lastPairResult: Int? = null,
        val audioStreaming: Boolean = false,
        val phoneName: String = "",
        val unitName: String = "",
        val pin: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var binder: IBinder? = null
    private var started = false

    val isInstalled: Boolean
        get() = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    fun start() {
        if (started || !isInstalled) return
        started = true
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(EVT_HSHF_STATUS)
            addAction(EVT_AV_STATUS)
            addAction(EVT_PAIR_STATUS)
            addAction(EVT_DEVICE_NAME)
            addAction(EVT_DEVICE_PINCODE)
        })
        bind()
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
    fun makeVisible() = command { call(Tx.PAIR_DEVICE) { it.writeInt(0) } }

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

    fun playPause() = command { call(Tx.PLAY_PAUSE) }
    fun next() = command { call(Tx.NEXT) }
    fun previous() = command { call(Tx.PREV) }

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
        scope.launch {
            if (binder == null) return@launch
            val link = linkOf(callInt(Tx.GET_CONNECT_STATE) ?: return@launch)
            _state.value = _state.value.copy(
                link = link,
                visible = callInt(Tx.GET_PAIR_STATE) == PAIR_IN_PROGRESS,
                audioStreaming = callInt(Tx.GET_A2DP_STATE) == AV_STREAMING,
                phoneName = if (link >= Link.CONNECTED) callString(Tx.GET_CONNECT_PHONE_NAME).orEmpty() else "",
                unitName = callString(Tx.GET_DEVICE_NAME) ?: _state.value.unitName,
                pin = callString(Tx.GET_PINCODE) ?: _state.value.pin
            )
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            intent ?: return
            val value = intent.getIntExtra(EXTRA_INT, -1)
            val text = intent.getStringExtra(EXTRA_STR)
            val s = _state.value
            _state.value = when (intent.action) {
                EVT_HSHF_STATUS -> s.copy(
                    link = linkOf(value),
                    phoneName = if (linkOf(value) >= Link.CONNECTED) s.phoneName else ""
                )
                EVT_AV_STATUS -> s.copy(audioStreaming = value == AV_STREAMING)
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
        }
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
        const val GET_DEVICE_NAME = 6
        const val DEL_ALL_PAIRED = 7
        const val PLAY_PAUSE = 25
        const val PREV = 26
        const val NEXT = 27
        const val DISCONNECT_A2DP = 28
        const val CON_LAST_A2DP = 29
        const val GET_CONNECT_STATE = 34
        const val GET_PAIR_STATE = 35
        const val GET_A2DP_STATE = 36
        const val GET_PINCODE = 37
        const val GET_CONNECT_PHONE_NAME = 50
    }

    companion object {
        const val PACKAGE = "com.szchoiceway.btsuite"
        private const val ACTION_SERVICE = "com.szchoiceway.btsuite.BTService"
        private const val DESCRIPTOR = "com.szchoiceway.btsuite.IBTService"

        private const val EVT_HSHF_STATUS = "com.szchoiceway.btsuite.HBCP_EVT_HSHF_STATUS"
        private const val EVT_AV_STATUS = "com.szchoiceway.btsuite.HBCP_EVT_AV_STATUS"
        private const val EVT_PAIR_STATUS = "com.szchoiceway.btsuite.HBCP_EVT_PAIR_STATUS"
        private const val EVT_DEVICE_NAME = "com.szchoiceway.btsuite.HBCP_EVT_DEVICE_NAME"
        private const val EVT_DEVICE_PINCODE = "com.szchoiceway.btsuite.HBCP_EVT_DEVICE_PINCODE"
        private const val EXTRA_INT = "com.szchoiceway.btsuite.DATA_INT"
        private const val EXTRA_STR = "com.szchoiceway.btsuite.DATA_STR"

        const val PAIR_SUCCESS = 0
        const val PAIR_TIMEOUT = 1
        private const val PAIR_IN_PROGRESS = 4
        private const val AV_STREAMING = 4

        /** Pauza dintre comenzi: modulul serial nu le primește bine lipite. */
        private const val STEP_MS = 600L
        private const val TAG = "KswBluetooth"
    }
}
