package ro.e92.launcher.ui

import android.view.View
import android.widget.TextView
import ro.e92.launcher.R
import ro.e92.launcher.can.VehicleState
import java.util.Locale

/**
 * Ce spune mașina în bara de sus: autonomia în stânga, temperatura de afară în
 * dreapta, plus
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
class CarStatus(
    private val temp: TextView,
    private val range: TextView,
    /** Procentul de rezervor de lângă autonomie. */
    private val fuelPct: TextView,
    /** Bara de rezervor de sub autonomie. */
    private val fuelBar: ro.e92.launcher.ui.widget.FuelBarView,
    /** Pompa + autonomia; ascunsă (dar păstrându-și locul) cât nu știm autonomia. */
    private val rangeBlock: View,
    private val banner: TextView
) {

    private val ctx = temp.context
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
        val t = state.outsideTempC
        temp.text = t?.let { formatTemp(it) }.orEmpty()
        temp.visibility = if (t == null) View.GONE else View.VISIBLE

        val r = state.rangeKm
        range.text = r?.let { ctx.getString(R.string.car_range_short, it) }.orEmpty()
        rangeBlock.visibility = if (r == null) View.INVISIBLE else View.VISIBLE

        val level = fuelLevel(state)
        fuelPct.text = level?.let { "${(it * 100).toInt()}%" }.orEmpty()
        fuelBar.visibility = if (level == null) View.GONE else View.VISIBLE
        level?.let { fuelBar.setLevel(it) }
    }

    /**
     * Nivelul rezervorului, 0..1. Din litrii trimiși de KSW dacă vin; altfel
     * estimat din autonomie față de un plin, ca bara să aibă ce arăta.
     */
    private fun fuelLevel(state: VehicleState): Float? {
        state.fuel?.let { return it / TANK_LITRES }
        return state.rangeKm?.let { it / FULL_TANK_RANGE_KM }
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
        const val STICKY = "sticky"
        const val MOVING_KMH = 5
        const val LOW_RANGE_KM = 60

        /** Rezervorul E92 diesel. */
        const val TANK_LITRES = 61f

        /** Autonomia aproximativă cu plinul; doar pentru estimarea barei. */
        const val FULL_TANK_RANGE_KM = 800f
        const val ICE_C = 3f
        const val TRANSIENT_MS = 8_000L
        const val ANIM_MS = 220L
    }
}
