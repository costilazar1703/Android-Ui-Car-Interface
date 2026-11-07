package ro.e92.launcher.ui.screens

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ScreenTripBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost
import java.util.Locale

/**
 * Calculatorul de bord, ca o scenă: distanța mare, consumul din bordul mașinii într-un
 * arc și restul cifrelor sub ele. Cifrele sunt portocalii ca să se vadă și în soare.
 */
class TripScreen(host: ScreenHost) : Screen(host) {

    override val title: String get() = "TRIP"
    override val backgroundRes: Int get() = R.drawable.bg_home

    private lateinit var binding: ScreenTripBinding
    private var long = false

    /** Resetarea drumului lung cere a doua apăsare. */
    private var resetArmed = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        ScreenTripBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        binding.tripTabDrive.setOnClickListener { select(false) }
        binding.tripTabLong.setOnClickListener { select(true) }
        binding.tripReset.setOnClickListener { reset() }
        render()
        screenScope.launch {
            while (isActive) {
                delay(REFRESH_MS)
                render()
            }
        }
    }

    override fun focusTargets(): List<FocusTarget> {
        val tabs = listOf(
            FocusTarget(ID_DRIVE, binding.tripTabDrive, onActivate = { select(false) }, right = ID_LONG),
            FocusTarget(
                ID_LONG, binding.tripTabLong, onActivate = { select(true) }, left = ID_DRIVE,
                right = if (long) ID_RESET else null
            )
        )
        return if (long) {
            tabs + FocusTarget(ID_RESET, binding.tripReset, onActivate = { reset() }, left = ID_LONG)
        } else {
            tabs
        }
    }

    private fun select(longTrip: Boolean) {
        if (long == longTrip) return
        long = longTrip
        resetArmed = false
        render()
        host.rebuildFocus(if (long) ID_LONG else ID_DRIVE)
    }

    private fun reset() {
        if (resetArmed) {
            resetArmed = false
            Services.trip.resetLongTrip()
        } else {
            resetArmed = true
        }
        render()
    }

    private fun render() {
        val t = if (long) Services.trip.longTrip.value else Services.trip.drive.value
        val car = Services.vehicle.state.value
        val b = binding
        val on = context.getColor(R.color.data_accent)
        val off = context.getColor(R.color.text_primary)
        b.tripTabDrive.setTextColor(if (long) off else on)
        b.tripTabLong.setTextColor(if (long) on else off)
        b.tripReset.visibility = if (long) View.VISIBLE else View.GONE
        b.tripReset.setText(if (resetArmed) R.string.trip_reset_confirm else R.string.trip_reset)

        set(b.tripDistance, String.format(Locale.US, "%.1f", t.distanceKm))
        set(b.tripCons, car.avgConsumption?.let { String.format(Locale.US, "%.1f", it) } ?: DASH)
        b.tripConsArc.value = car.avgConsumption
        set(b.tripTime, duration(t.drivingMs))
        set(b.tripAvg, t.avgSpeedKmh?.let { "$it km/h" } ?: DASH)
        set(b.tripMax, if (t.maxSpeedKmh > 0) "${t.maxSpeedKmh} km/h" else DASH)
        set(b.tripFuelUsed, if (t.fuelUsedL > 0) "${t.fuelUsedL} l" else DASH)
        set(b.tripRange, car.rangeKm?.let { "$it km" } ?: DASH)
        set(b.tripTank, car.fuel?.let { "$it l" } ?: DASH)
        set(b.tripBoardAvg, car.avgSpeedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: DASH)
    }

    private fun set(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    /** „1 h 24 min" / „12 min". */
    private fun duration(ms: Long): String {
        if (ms <= 0L) return DASH
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }

    private companion object {
        const val ID_DRIVE = "trip_drive"
        const val ID_LONG = "trip_long"
        const val ID_RESET = "trip_reset"
        const val DASH = "—"
        const val REFRESH_MS = 1_000L
    }
}
