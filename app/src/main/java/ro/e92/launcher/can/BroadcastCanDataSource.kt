package ro.e92.launcher.can

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sursă bazată pe broadcast-urile serviciului vendor (EventCenter).
 *
 * Acțiunile și cheile KSW din [CanBroadcastConfig.DEFAULT] sunt cele pe care le ascultă
 * aplicația Dashboard a unității (citite din EventCenter și ksw_dashboard decompilate):
 * viteza și turațiile pe ZXW_CAN_SPEED_EVT / ZXW_CAN_ROTATING_SPEED_EVT, iar combustibilul,
 * autonomia, temperatura exterioară și frâna de mână în pachetul ZXW_SENDBROADCAST8902MOD.
 *
 * Confirmat în mașină: captura din 2026-11-04 (launcher-ul asculta broadcast-urile unității)
 * are mii de pachete ZXW_SENDBROADCAST8902MOD cu valori reale în cheile _ShunShiSuDu (viteza),
 * _fadongjizhuansu (turațiile), _youLiang (combustibilul), _xushilicheng (autonomia),
 * _huanjinwendu (temperatura exterioară) și _shousha (frâna de mână). Aceleași date vin și din
 * [KswCarDataSource] (cererea de date de bord către MCU); [ReverseSource] adaugă cadrele CAN
 * brute (senzori, volan, manetă, Drive Sport, DSC, ceas, baterie). Cheile generice de după cele
 * KSW („speed", „rpm" etc.) sunt pentru alte plăci și nu sunt folosite pe unitatea din mașină.
 *
 * Ecranul de diagnostic arată orice broadcast prins pe acțiunile din listă, cu toate
 * extras-urile, ca o cheie nouă să poată fi mapată direct în mașină.
 */
class BroadcastCanDataSource(
    private val context: Context,
    private val config: CanBroadcastConfig = CanBroadcastConfig.DEFAULT
) : CanDataSource {

    override val id = "broadcast"
    override val description = "Broadcast vendor: ${config.actions.joinToString()}"

    private val _state = MutableStateFlow(VehicleState.EMPTY)
    override val vehicleState: StateFlow<VehicleState> = _state.asStateFlow()

    /** Ultimele extras primite, ca text — pentru ecranul de diagnostic. */
    private val _lastRaw = MutableStateFlow("")
    val lastRaw: StateFlow<String> = _lastRaw.asStateFlow()

    override val isAvailable: Boolean
        get() = registered &&
            _state.value.hasData &&
            SystemClock.elapsedRealtime() - _state.value.timestamp < CanDataSource.STALE_AFTER_MS

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            _lastRaw.value = describe(intent)
            val parsed = parse(intent) ?: return
            _state.value = parsed
        }
    }

    override fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            (config.actions + config.reverseStartActions + config.reverseEndActions).forEach { addAction(it) }
        }
        try {
            context.registerReceiver(receiver, filter)
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "Nu s-a putut înregistra receiver-ul CAN", e)
        }
    }

    override fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        _state.value = VehicleState.EMPTY
    }

    private fun parse(intent: Intent): VehicleState? {
        val prev = _state.value

        // Marșarierul pe KSW vine ca două acțiuni fără extras (început / sfârșit),
        // nu ca o cheie într-un pachet.
        val reverseByAction = when (intent.action) {
            in config.reverseStartActions -> true
            in config.reverseEndActions -> false
            else -> null
        }
        if (reverseByAction != null) {
            return prev.copy(reverseGear = reverseByAction, timestamp = SystemClock.elapsedRealtime())
        }

        val extras = intent.extras ?: return null

        // Un broadcast poate purta doar un subset de câmpuri; ce nu vine acum
        // se păstrează din starea anterioară. Contorizăm separat câte chei
        // CUNOSCUTE au fost efectiv prezente, ca să nu marcăm sursa ca vie pe
        // un broadcast care nu e al nostru.
        var known = 0

        val speed = config.speedKeys.firstNotNullOfOrNull { extras.readInt(it) }
            ?.also { known++ } ?: prev.speedKmh
        val rpm = config.rpmKeys.firstNotNullOfOrNull { extras.readInt(it) }
            ?.also { known++ } ?: prev.rpm
        val handbrake = config.handbrakeKeys.firstNotNullOfOrNull { extras.readBool(it) }
            ?.also { known++ } ?: prev.handbrake
        val reverse = config.reverseKeys.firstNotNullOfOrNull { extras.readBool(it) }
            ?.also { known++ } ?: prev.reverseGear
        val sport = config.sportKeys.firstNotNullOfOrNull { extras.readBool(it) }
            ?.also { known++ } ?: prev.sportMode
        val boost = config.boostKeys.firstNotNullOfOrNull { extras.readFloat(it) }
            ?.also { known++ } ?: prev.boostBar
        val oilTemp = config.oilTempKeys.firstNotNullOfOrNull { extras.readInt(it) }
            ?.also { known++ } ?: prev.oilTempC
        val fuel = config.fuelKeys.firstNotNullOfOrNull { extras.readInt(it) }
            ?.also { known++ } ?: prev.fuel
        val range = config.rangeKeys.firstNotNullOfOrNull { extras.readInt(it) }
            ?.also { known++ } ?: prev.rangeKm
        val outside = config.outsideTempKeys.firstNotNullOfOrNull { extras.readFloat(it) }
            ?.also { known++ } ?: prev.outsideTempC

        // Ușile: dacă măcar o cheie de ușă e prezentă, frame-ul e autoritar pentru
        // TOATE ușile — altfel o ușă închisă nu s-ar stinge niciodată din UI.
        var anyDoorKeyPresent = false
        val doors = HashSet<Door>(2)
        for ((door, key) in config.doorKeys) {
            val open = extras.readBool(key) ?: continue
            anyDoorKeyPresent = true
            if (open) doors.add(door)
        }
        if (anyDoorKeyPresent) known++

        if (known == 0) return null

        return VehicleState(
            speedKmh = speed?.coerceIn(0, 320),
            rpm = rpm?.coerceIn(0, 9000),
            handbrake = handbrake,
            doorsOpen = if (anyDoorKeyPresent) doors else prev.doorsOpen,
            reverseGear = reverse,
            sportMode = sport,
            boostBar = boost?.coerceIn(-1f, 6f),
            oilTempC = oilTemp?.coerceIn(-40, 200),
            fuel = fuel?.takeIf { it in 0..FUEL_MAX },
            rangeKm = range?.takeIf { it in 0..RANGE_MAX },
            outsideTempC = outside?.takeIf { it in -40f..60f },
            timestamp = SystemClock.elapsedRealtime()
        )
    }

    private fun android.os.Bundle.readInt(key: String): Int? = when (val v = get(key)) {
        is Int -> v
        is Short -> v.toInt()
        is Byte -> v.toInt() and 0xFF
        is Float -> v.toInt()
        is Double -> v.toInt()
        is String -> leadingNumber(v)?.toInt()
        else -> null
    }

    private fun android.os.Bundle.readFloat(key: String): Float? = when (val v = get(key)) {
        is Float -> v
        is Double -> v.toFloat()
        is Int -> v.toFloat()
        is String -> leadingNumber(v)
        else -> null
    }

    /**
     * KSW trimite unele valori ca text gata formatat: autonomia „420km",
     * temperatura „12.5℃" (EventService.ksw_0x00_0xA1_0x19_refreshData).
     * În Fahrenheit sau mile nu le convertim - le ignorăm, mașina e pe metric.
     */
    private fun leadingNumber(s: String): Float? {
        val t = s.trim()
        if (t.endsWith("F") || t.endsWith("mile")) return null
        return NUMBER.find(t)?.value?.toFloatOrNull()
    }

    private fun android.os.Bundle.readBool(key: String): Boolean? = when (val v = get(key)) {
        is Boolean -> v
        is Int -> v != 0
        is Byte -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", ignoreCase = true)
        else -> null
    }

    private fun describe(intent: Intent): String {
        val sb = StringBuilder(intent.action ?: "?")
        intent.extras?.let { b ->
            for (key in b.keySet()) {
                sb.append("\n  ").append(key).append(" = ").append(b.get(key))
            }
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "BroadcastCan"
        val NUMBER = Regex("""^-?\d+(\.\d+)?""")
        /** Rezervorul E92 are 63 l; peste asta valoarea nu e în litri. */
        const val FUEL_MAX = 80
        const val RANGE_MAX = 2000
    }
}

/**
 * Configurația de mapare broadcast → [VehicleState], într-un singur loc.
 */
data class CanBroadcastConfig(
    val actions: List<String>,
    val speedKeys: List<String>,
    val rpmKeys: List<String>,
    val handbrakeKeys: List<String>,
    val reverseKeys: List<String>,
    val sportKeys: List<String>,
    val boostKeys: List<String>,
    val oilTempKeys: List<String>,
    val fuelKeys: List<String> = emptyList(),
    val rangeKeys: List<String> = emptyList(),
    val outsideTempKeys: List<String> = emptyList(),
    val doorKeys: Map<Door, String>,
    val reverseStartActions: List<String> = emptyList(),
    val reverseEndActions: List<String> = emptyList()
) {
    companion object {
        private const val KSW = "com.choiceway.eventcenter.EventUtils"
        private const val KSW_8902 = "com.szchoiceway.eventcenter.EventUtils.ZXW_SENDBROADCAST8902MOD"

        /**
         * Primele intrări din fiecare listă sunt ale unității KSW din mașină, citite din
         * aplicațiile ei (EventCenter, ksw_dashboard) și văzute în mașină: exact acțiunile și
         * cheile pe care le ascultă dashboard-ul vendorului. Restul sunt valori uzuale pe alte
         * plăci (GS/ZXW), păstrate pentru alte unități.
         */
        val DEFAULT = CanBroadcastConfig(
            actions = listOf(
                "$KSW.ZXW_CAN_SPEED_EVT",
                "$KSW.ZXW_CAN_ROTATING_SPEED_EVT",
                "$KSW.ZXW_CAN_SEATBELT_EVT",
                "$KSW_8902",
                "android.intent.action.CAN_DATA",
                "com.mcu.CAN_INFO",
                "com.android.mcu.CAR_INFO",
                "com.zxw.canbus.DATA"
            ),
            speedKeys = listOf("$KSW.ZXW_CAN_SPEED_EVT_EXTRA", "${KSW_8902}_ShunShiSuDu", "speed", "car_speed", "vehicle_speed", "kmh"),
            rpmKeys = listOf("$KSW.ZXW_CAN_ROTATING_SPEED_EVT_EXTRA", "${KSW_8902}_fadongjizhuansu", "rpm", "engine_rpm", "revs"),
            handbrakeKeys = listOf("${KSW_8902}_shousha", "handbrake", "parking_brake", "brake"),
            reverseKeys = listOf("reverse", "back_car", "rear_gear"),
            // În mașină modul Sport se citește din CAN (0x1D2, în ReverseSource), nu de aici.
            // Cheile de mai jos sunt pentru alte plăci.
            sportKeys = listOf("sport", "sport_mode", "drive_mode", "dme_sport", "mode_sport"),
            boostKeys = listOf("boost", "boost_bar", "boost_pressure", "turbo_boost"),
            oilTempKeys = listOf("oil_temp", "oil_temperature", "engine_oil_temp"),
            // Pachetul KSW cu ZXW_SENDBROADCAST8902MOD_EXTRA = 25.
            fuelKeys = listOf("${KSW_8902}_youLiang"),
            rangeKeys = listOf("${KSW_8902}_xushilicheng"),
            outsideTempKeys = listOf("${KSW_8902}_huanjinwendu"),
            doorKeys = mapOf(
                Door.FRONT_LEFT to "door_fl",
                Door.FRONT_RIGHT to "door_fr",
                Door.REAR_LEFT to "door_rl",
                Door.REAR_RIGHT to "door_rr",
                Door.TRUNK to "door_trunk",
                Door.HOOD to "door_hood"
            ),
            reverseStartActions = listOf("$KSW.MCU_MSG_BACKCAR_START"),
            reverseEndActions = listOf("$KSW.MCU_MSG_BACKCAR_END")
        )
    }
}
