package ro.e92.launcher.ui

import android.view.View
import android.widget.TextView
import ro.e92.launcher.R
import ro.e92.launcher.can.VehicleState
import java.util.Locale

/**
 * Ce spune mașina în bara de sus: temperatura de afară și autonomia, plus
 * bannerul de avertismente de sub bară.
 *
 * Avertismentele au două feluri, după cât de grave sunt:
 *  - frâna de mână trasă în mers stă pe ecran cât durează situația;
 *  - rezerva și poleiul apar o singură dată pe drum, câteva secunde - sunt
 *    informații, nu alarme, și n-au ce căuta permanent peste meniu.
 *
 * „Pe drum" = de la ultima pornire a launcher-ului sau de la ultimul contact
 * (vezi [newDrive]).
 */
class CarStatus(private val status: TextView, private val banner: TextView) {

    private val ctx = status.context
    private var fuelShown = false
    private var iceShown = false
    private var transient: Int? = null

    fun newDrive() {
        fuelShown = false
        iceShown = false
    }

    fun render(state: VehicleState) {
        renderStatus(state)
        renderAlert(state)
    }

    private fun renderStatus(state: VehicleState) {
        val parts = ArrayList<String>(2)
        state.outsideTempC?.let { parts.add(formatTemp(it)) }
        state.rangeKm?.let { parts.add(ctx.getString(R.string.car_range_short, it)) }
        status.text = parts.joinToString(SEPARATOR)
        status.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderAlert(state: VehicleState) {
        val moving = (state.speedKmh ?: 0) >= MOVING_KMH
        if (state.handbrake == true && moving) {
            show(ctx.getString(R.string.alert_handbrake), sticky = true)
            return
        }
        if (banner.tag == STICKY) hide()
        // Unul pe rând: următorul așteaptă până se stinge cel de acum.
        if (banner.visibility == View.VISIBLE) return

        val range = state.rangeKm
        if (!fuelShown && range != null && range <= LOW_RANGE_KM) {
            fuelShown = true
            show(ctx.getString(R.string.alert_low_fuel, range), sticky = false)
            return
        }
        val temp = state.outsideTempC
        if (!iceShown && temp != null && temp <= ICE_C) {
            iceShown = true
            show(ctx.getString(R.string.alert_ice, formatTemp(temp)), sticky = false)
        }
    }

    private fun show(text: String, sticky: Boolean) {
        if (banner.visibility == View.VISIBLE && banner.text == text) return
        banner.removeCallbacks(hideRunnable)
        banner.text = text
        banner.tag = if (sticky) STICKY else null
        if (banner.visibility != View.VISIBLE) {
            banner.alpha = 0f
            banner.translationY = -banner.resources.displayMetrics.density * 12f
            banner.visibility = View.VISIBLE
            banner.animate().alpha(1f).translationY(0f).setDuration(ANIM_MS).start()
        }
        if (!sticky) banner.postDelayed(hideRunnable, TRANSIENT_MS)
    }

    private val hideRunnable = Runnable { hide() }

    private fun hide() {
        banner.removeCallbacks(hideRunnable)
        banner.tag = null
        if (banner.visibility != View.VISIBLE) return
        banner.animate().alpha(0f).setDuration(ANIM_MS)
            .withEndAction { banner.visibility = View.GONE }.start()
    }

    /** „12.5°" ca în bord, dar fără „.0" când e număr întreg. */
    private fun formatTemp(c: Float): String =
        if (c % 1f == 0f) "${c.toInt()}°" else String.format(Locale.US, "%.1f°", c)

    private companion object {
        const val SEPARATOR = "   ·   "
        const val STICKY = "sticky"
        const val MOVING_KMH = 5
        const val LOW_RANGE_KM = 60
        const val ICE_C = 3f
        const val TRANSIENT_MS = 8_000L
        const val ANIM_MS = 220L
    }
}
