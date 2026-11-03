package ro.e92.launcher.ui.screens

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.ui.ScreenHost
import java.util.Locale

/**
 * Calculatorul de bord, în același format split ca Bluetooth și Setări:
 * stânga drumul acesta / drumul lung, dreapta cifrele lui. Se împrospătează
 * singur cât e deschis, ca să-l poți lăsa pe ecran pe autostradă.
 */
class TripScreen(host: ScreenHost) : SplitScreen(host) {
    override val backgroundRes: Int get() = R.drawable.bg_menu_nav

    override val title: String get() = "TRIP"
    override val navHeader: String get() = context.getString(R.string.trip_title)

    /** Resetarea drumului lung cere a doua apăsare. */
    private var resetArmed = false

    override fun onShow() {
        super.onShow()
        // O dată pe secundă: datele vin de 10 ori pe secundă, dar cifrele
        // astea nu au ce să arate mai des.
        screenScope.launch {
            while (isActive) {
                delay(REFRESH_MS)
                if (selectedNavId != "") reloadDetail()
            }
        }
    }

    override fun navEntries(): List<NavEntry> = listOf(
        NavEntry(NAV_DRIVE, context.getString(R.string.trip_drive)),
        NavEntry(NAV_LONG, context.getString(R.string.trip_long))
    )

    override fun detailTitle(navId: String): String =
        context.getString(if (navId == NAV_LONG) R.string.trip_long else R.string.trip_drive).uppercase()

    override fun detailHint(navId: String): String =
        context.getString(if (navId == NAV_LONG) R.string.trip_long_hint else R.string.trip_drive_hint)

    override fun detailRows(navId: String): List<DetailRow> {
        val long = navId == NAV_LONG
        val t = if (long) Services.trip.longTrip.value else Services.trip.drive.value
        val car = Services.vehicle.state.value
        val rows = arrayListOf(
            row("distance", R.string.trip_distance, String.format(Locale.US, "%.1f km", t.distanceKm)),
            row("time", R.string.trip_time, duration(t.drivingMs)),
            row("avg", R.string.trip_avg_speed, t.avgSpeedKmh?.let { "$it km/h" } ?: DASH),
            row("max", R.string.trip_max_speed, if (t.maxSpeedKmh > 0) "${t.maxSpeedKmh} km/h" else DASH),
            row("fuel", R.string.trip_fuel_used, if (t.fuelUsedL > 0) "${t.fuelUsedL} l" else DASH),
            row("cons", R.string.trip_consumption,
                t.consumption?.let { String.format(Locale.US, "%.1f l/100 km", it) } ?: DASH),
            row("range", R.string.trip_range_now, car.rangeKm?.let { "$it km" } ?: DASH),
            row("tank", R.string.trip_fuel_now, car.fuel?.let { "$it l" } ?: DASH)
        )
        if (long) {
            rows.add(
                DetailRow(
                    id = "trip_reset",
                    title = context.getString(R.string.trip_reset),
                    value = if (resetArmed) context.getString(R.string.trip_reset_confirm) else "",
                    onActivate = {
                        if (resetArmed) {
                            resetArmed = false
                            Services.trip.resetLongTrip()
                        } else {
                            resetArmed = true
                        }
                        reloadDetail("trip_reset")
                    }
                )
            )
        }
        return rows
    }

    private fun row(id: String, title: Int, value: String) =
        DetailRow(id = "trip_$id", title = context.getString(title), value = value)

    /** „1 h 24 min" / „12 min". */
    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        if (ms <= 0L) return DASH
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }

    private companion object {
        const val NAV_DRIVE = "trip_drive"
        const val NAV_LONG = "trip_long"
        const val DASH = "—"
        const val REFRESH_MS = 1_000L
    }
}
