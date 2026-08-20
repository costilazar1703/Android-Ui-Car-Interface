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
 * Sursă bazată pe broadcast-urile aplicației vendor.
 *
 * NIMIC din configurația de mai jos nu e confirmat — sunt doar valorile cele mai
 * frecvente pe plăcile GS/ZXW. Faza 0.1 stabilește adevărul:
 *
 *   adb logcat | grep -iE "can|bus|speed|rpm|acc|mcu"
 *   adb shell dumpsys activity services | grep -iE "can|mcu"
 *
 * Când știi acțiunea și cheile reale, editează [CanBroadcastConfig] — restul codului
 * nu se schimbă. Ecranul de diagnostic loghează orice broadcast prins pe acțiunile
 * din listă, inclusiv extras necunoscute, ca să poți face maparea direct în mașină.
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
        val filter = IntentFilter().apply { config.actions.forEach { addAction(it) } }
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
        val extras = intent.extras ?: return null
        val prev = _state.value

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
            timestamp = SystemClock.elapsedRealtime()
        )
    }

    private fun android.os.Bundle.readInt(key: String): Int? = when (val v = get(key)) {
        is Int -> v
        is Short -> v.toInt()
        is Byte -> v.toInt() and 0xFF
        is Float -> v.toInt()
        is Double -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
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
    }
}

/**
 * Configurația de mapare broadcast → [VehicleState].
 * Un singur loc de editat după Faza 0.1.
 */
data class CanBroadcastConfig(
    val actions: List<String>,
    val speedKeys: List<String>,
    val rpmKeys: List<String>,
    val handbrakeKeys: List<String>,
    val reverseKeys: List<String>,
    val doorKeys: Map<Door, String>
) {
    companion object {
        /** PRESUPUNERI. De înlocuit cu ce arată logcat-ul pe unitatea reală. */
        val DEFAULT = CanBroadcastConfig(
            actions = listOf(
                "android.intent.action.CAN_DATA",
                "com.mcu.CAN_INFO",
                "com.android.mcu.CAR_INFO",
                "com.zxw.canbus.DATA"
            ),
            speedKeys = listOf("speed", "car_speed", "vehicle_speed", "kmh"),
            rpmKeys = listOf("rpm", "engine_rpm", "revs"),
            handbrakeKeys = listOf("handbrake", "parking_brake", "brake"),
            reverseKeys = listOf("reverse", "back_car", "rear_gear"),
            doorKeys = mapOf(
                Door.FRONT_LEFT to "door_fl",
                Door.FRONT_RIGHT to "door_fr",
                Door.REAR_LEFT to "door_rl",
                Door.REAR_RIGHT to "door_rr",
                Door.TRUNK to "door_trunk",
                Door.HOOD to "door_hood"
            )
        )
    }
}
