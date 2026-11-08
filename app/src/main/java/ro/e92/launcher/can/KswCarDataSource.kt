package ro.e92.launcher.can

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
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

/**
 * Datele mașinii exact cum le ia aplicația „Dashboard" a unității KSW
 * (ksw_dashboard.apk, decompilată 2026-11-03): autonomia, combustibilul,
 * temperatura de afară, viteza, turațiile și frâna de mână.
 *
 * Nu vin pe broadcast: aplicația se leagă de serviciul EventCenter
 * (IEventService), își înregistrează un callback (tranzacția 120) și cere
 * MCU-ului datele la fiecare 500 ms (tranzacția 119, cadrul F2 00 68 02 05 00).
 * EventCenter răspunde pe callback cu evenimentul 90 - cadrul cu datele de
 * bord - și cu 91 când se schimbă frâna sau centura (citite apoi din SysVar).
 *
 * Cadrul 90 (octeți, big-endian):
 *   5-6   autonomie km (cu modul de sistem 1/3: 20-22, pe trei octeți)
 *   7-8   consum mediu din bord ×10 · 9-10 viteză medie din bord ×10
 *   11-12 viteză · 13-14 turații
 *   15-16 combustibil, litri · 17-18 temperatura de afară ×10, cu semn
 *   19    bit 2 = grade Fahrenheit
 */
class KswCarDataSource(
    private val context: Context,
    private val scope: CoroutineScope
) : CanDataSource {

    override val id = "ksw-dash"
    override val description = "KSW dashboard data (EventCenter)"

    private val _state = MutableStateFlow(VehicleState.EMPTY)
    override val vehicleState: StateFlow<VehicleState> = _state.asStateFlow()

    override val isAvailable: Boolean
        get() = service != null && SystemClock.elapsedRealtime() - _state.value.timestamp < CanDataSource.STALE_AFTER_MS

    @Volatile
    private var service: IBinder? = null
    private var poller: Job? = null
    private var bound = false
    private var loggedFirstFrame = false
    private var systemMode = 0
    private var canForwarding: Boolean? = null
    private var lastRpm = 0

    override fun start() {
        // Marșarierul vine pe alt canal (cadrul 0x11), deci îl putem urmări fără CAN.
        scope.launch {
            Services.reverse.vehicleState
                .map { it.reverseGear == true }
                .distinctUntilChanged()
                .collect { updateCanForwarding() }
        }
        if (bound) return
        bound = runCatching {
            context.bindService(Intent(EVENT_SERVICE).setPackage(EVENT_PACKAGE), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) EventLog.log(TAG, "EventCenter indisponibil - fără datele de bord KSW")
    }

    override fun stop() {
        poller?.cancel()
        poller = null
        service?.let { binder ->
            transact(binder, TX_SEND_MCU) { it.writeByteArray(CAN_OFF) }
            register(binder, null)
        }
        canForwarding = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder ?: return
            canForwarding = null
            systemMode = KswSettings.read(context, "KESAIWEI_SYS_MODE_SELECTION")?.toIntOrNull() ?: 0
            register(binder, callback)
            readHandbrake()
            // Cererile de la pornirea ecranului de bord KSW, apoi cererea periodică.
            for (cmd in START_CMDS) sendCanbus(binder, cmd)
            poller?.cancel()
            poller = scope.launch {
                while (isActive) {
                    transact(binder, TX_SEND_MCU) { it.writeByteArray(MCU_REQUEST) }
                    delay(POLL_MS)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            poller?.cancel()
            service = null
        }
    }

    /** ICallbackfn: EventCenter ne trimite evenimentele aici. */
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK_DESCRIPTOR)
                return true
            }
            if (code != 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK_DESCRIPTOR)
            val what = data.readInt()
            data.readInt()
            data.readInt()
            val bytes = data.createByteArray()
            data.readString()
            when (what) {
                EVT_DASH -> if (bytes != null && bytes.size >= 20) onFrame(bytes)
                EVT_BELT_BRAKE -> readHandbrake()
            }
            reply?.writeNoException()
            return true
        }
    }

    private fun onFrame(b: ByteArray) {
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        var range = u16(5)
        if ((systemMode == 1 || systemMode == 3) && b.size >= 23) {
            range = ((b[20].toInt() and 0xFF) shl 16) or ((b[21].toInt() and 0xFF) shl 8) or (b[22].toInt() and 0xFF)
        }
        val fahrenheit = (b[19].toInt() and 4) != 0
        val rawTemp = u16(17)
        val temp = if (rawTemp and 0x8000 != 0) rawTemp - 0x10000 else rawTemp
        val outside = if (fahrenheit) null else temp / 10f
        val speed = u16(11)
        val rpm = u16(13)
        val fuel = u16(15)
        updateCanForwarding(rpm)
        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            EventLog.log(TAG, "date de bord KSW: autonomie $range km, combustibil $fuel l, afară $outside, $speed km/h, $rpm rpm")
        }
        _state.value = _state.value.copy(
            rangeKm = range.takeIf { it in 1..2000 },
            avgConsumption = (u16(7) / 10f).takeIf { it > 0f && it < 100f },
            avgSpeedKmh = (u16(9) / 10f).takeIf { it > 0f && it < 300f },
            fuel = fuel.takeIf { it in 0..100 },
            outsideTempC = outside?.takeIf { it in -40f..60f },
            speedKmh = speed.takeIf { it in 0..300 },
            rpm = rpm.takeIf { it in 0..8000 },
            timestamp = SystemClock.elapsedRealtime()
        )
    }

    /**
     * Cadrele CAN brute (A1 1D) încarcă legătura cu MCU-ul cu până la ~240 de cadre pe
     * secundă, așa că le cerem doar când chiar trebuie: în marșarier, cu scena noastră
     * pornită (senzori, volan), și, dacă e aleasă, pentru Drift Mode cât merge motorul.
     * Aceeași comandă ca butonul din Factory.
     */
    fun updateCanForwarding(rpm: Int = lastRpm) {
        lastRpm = rpm
        val reverse = Services.reverse.vehicleState.value.reverseGear == true && Services.prefs.reverseScene
        val drift = Services.prefs.driftMode && rpm >= ENGINE_ON_RPM
        val wanted = reverse || drift
        // Fără legătura cu EventCenter nu trimitem nimic și nu ținem minte nimic: altfel
        // „pornite" rămânea notat fără să fi plecat comanda (marșarier imediat după pornire).
        val binder = service ?: return
        val now = SystemClock.elapsedRealtime()
        if (wanted == canForwarding) {
            // Comanda se poate pierde pe legătura cu MCU-ul: dacă vrem cadre și de
            // [CAN_RETRY_MS] n-a venit niciunul, o trimitem din nou (e aceeași comandă ca
            // butonul din Factory, repetată nu strică nimic).
            if (!wanted || now - Services.reverse.lastCanAt < CAN_RETRY_MS || now - canSentAt < CAN_RETRY_MS) return
            EventLog.log(TAG, "cadre CAN: nu vin, retrimit pornirea")
        } else {
            EventLog.log(TAG, "cadre CAN: " + if (wanted) "pornite" else "oprite")
        }
        canForwarding = wanted
        canSentAt = now
        scope.launch {
            transact(binder, TX_SEND_MCU) { it.writeByteArray(if (wanted) CAN_ON else CAN_OFF) }
        }
    }

    private var canSentAt = 0L

    private fun readHandbrake() {
        val park = KswSettings.read(context, "KESAIWEI_RECORD_PARK")?.toIntOrNull() ?: return
        _state.value = _state.value.copy(handbrake = park == 1, timestamp = SystemClock.elapsedRealtime())
    }

    private fun register(binder: IBinder, cb: Binder?) {
        transact(binder, TX_REGISTER_CALLBACK) { it.writeStrongBinder(cb) }
    }

    /** Comandă spre cutia CAN, încadrată ca în aplicația KSW: 2E, date, sumă inversată. */
    private fun sendCanbus(binder: IBinder, cmd: ByteArray) {
        val framed = ByteArray(cmd.size + 2)
        framed[0] = 0x2E
        var sum = 0
        for (i in cmd.indices) {
            framed[i + 1] = cmd[i]
            sum += cmd[i]
        }
        framed[cmd.size + 1] = (sum.toByte().toInt() xor 0xFF).toByte()
        transact(binder, TX_SEND_CANBUS) { it.writeByteArray(framed) }
    }

    private fun transact(binder: IBinder, code: Int, write: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "tranzacția $code", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val TAG = "KswCarData"
        const val EVENT_SERVICE = "com.szchoiceway.eventcenter.EventService"
        const val EVENT_PACKAGE = "com.szchoiceway.eventcenter"
        const val SERVICE_DESCRIPTOR = "com.szchoiceway.eventcenter.IEventService"
        const val CALLBACK_DESCRIPTOR = "com.szchoiceway.eventcenter.ICallbackfn"

        const val TX_SEND_CANBUS = 81
        const val TX_SEND_MCU = 119
        const val TX_REGISTER_CALLBACK = 120

        const val EVT_DASH = 90
        const val EVT_BELT_BRAKE = 91

        const val POLL_MS = 500L

        val MCU_REQUEST = byteArrayOf(0xF2.toByte(), 0x00, 0x68, 0x02, 0x05, 0x00)
        val CAN_ON = byteArrayOf(0xF2.toByte(), 0x00, 0x70, 0x02, 0x0F, 0x01)
        val CAN_OFF = byteArrayOf(0xF2.toByte(), 0x00, 0x70, 0x02, 0x0F, 0x00)
        const val ENGINE_ON_RPM = 300
        const val CAN_RETRY_MS = 3_000L
        val START_CMDS = listOf(
            byteArrayOf(0x89.toByte(), 0x01, 0x10),
            byteArrayOf(0x8A.toByte(), 0x01, 0x10),
            byteArrayOf(0x90.toByte(), 0x02, 0x24, 0x00)
        )
    }
}
