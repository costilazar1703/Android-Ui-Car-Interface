package ro.e92.launcher.ui.screens

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost
import ro.e92.launcher.ui.widget.ParkSensorView

/**
 * Scena de marșarier: mașina la mijloc, cu spatele în jos, între barele launcher-ului.
 * O deschide și o închide [ro.e92.launcher.ui.HomeActivity], după treapta din CAN.
 */
class ReverseScreen(host: ScreenHost) : Screen(host) {

    override val title: String get() = context.getString(R.string.reverse_title)
    override val backgroundRes: Int get() = R.drawable.bg_reverse

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        inflater.inflate(R.layout.screen_reverse, container, false)

    override fun onShow() {
        val view = root ?: return
        val sensors = view.findViewById<ParkSensorView>(R.id.reverseSensors)
        val distance = view.findViewById<TextView>(R.id.reverseDistance)
        val note = view.findViewById<TextView>(R.id.reverseSensorNote)
        screenScope.launch {
            Services.reverse.sensorCm.collect { cm ->
                sensors.distancesCm = cm
                note.visibility = if (cm == null) View.VISIBLE else View.INVISIBLE
                val closest = cm?.minOrNull()
                distance.visibility = if (closest == null) View.GONE else View.VISIBLE
                if (closest != null) distance.text = context.getString(R.string.reverse_distance_cm, closest)
            }
        }
    }
}
