package ro.e92.launcher.ui.screens

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.can.VehicleState
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ScreenDashboardBinding
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost

/**
 * Dashboard pe tot ecranul. Fără elemente focusabile: nu e nimic de selectat,
 * exact ca la ceasurile unei mașini. Se iese cu BACK sau MENU.
 */
class DashboardScreen(host: ScreenHost) : Screen(host) {
    // Fundal simplu, nu fotografia cu bordul: cadranele din poză concurau cu
    // cele adevărate, chiar lângă ele.
    /** Fără fotografie: ceasurile pe negru simplu, mai curat și mai ușor de citit. */
    override val backgroundRes: Int get() = 0

    override val title: String get() = "DASHBOARD"

    private lateinit var binding: ScreenDashboardBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup) =
        ScreenDashboardBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        // Doar aici avem nevoie de cadre CAN (tensiunea bateriei); la ieșire se opresc.
        Services.kswCar?.dashboardOpen = true
        binding.gaugeRow.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) sizeGauges(r - l, b - t)
        }
        // Scalele copiate după bordul E92 320d: kilometrajul pe un arc lung,
        // 0 jos-stânga (~7 și un sfert) până la 260 jos-dreapta (~4 și jumătate),
        // cifre din 20 în 20. Unghiurile sunt în grade Canvas, 0° = ora 3.
        binding.gaugeSpeed.configure(
            caption = "SPEED",
            unit = context.getString(R.string.unit_kmh),
            maxValue = 260f,
            majorTickStep = 20f,
            minorPerMajor = 2,
            labelStep = 20f,
            startAngle = 128f,
            sweepAngle = 276f,
            labelSizeRatio = 0.040f
        )
        // Turometrul diesel: 0–5, zona roșie scurtă imediat după 5 (până la 5.5),
        // gradații mici la fiecare 500.
        binding.gaugeRpm.configure(
            caption = "RPM",
            unit = context.getString(R.string.unit_rpm),
            maxValue = 5500f,
            majorTickStep = 1000f,
            minorPerMajor = 2,
            redlineFrom = 5000f,
            displayDivisor = 1000f,
            decimals = 1,
            startAngle = 142f,
            sweepAngle = 257f
        )
        // Cadranele mici: turbo și lichid de răcire, din adaptorul OBD.
        binding.gaugeBoost.configure(
            caption = "BOOST",
            unit = "bar",
            // Și vacuum, ca la cadranul vechi: de la -1 bar (2026-11-17).
            minValue = -1f,
            maxValue = 2.5f,
            majorTickStep = 0.5f,
            minorPerMajor = 1,
            labelStep = 1f,
            decimals = 1,
            startAngle = 180f,
            sweepAngle = 180f,
            // Aceeași înălțime ca „COOLANT" din dreapta.
            captionLift = SMALL_CAPTION_LIFT
        )
        binding.gaugeOil.configure(
            caption = "COOLANT",
            unit = "°C",
            maxValue = 130f,
            majorTickStep = 20f,
            minorPerMajor = 1,
            labelStep = 40f,
            redlineFrom = 110f,
            startAngle = 180f,
            sweepAngle = 180f,
            // Mai jos: „COOLANT” e lung și la 0.38 intra peste cifrele 40 și 80 (2026-11-16).
            captionLift = SMALL_CAPTION_LIFT
        )

        screenScope.launch {
            Services.vehicle.state.collect { render(it) }
        }
        screenScope.launch {
            Services.reverse.batteryVolts.collect { renderBattery(it) }
        }
        screenScope.launch {
            Services.vehicle.activeSourceId.collect {
                binding.txtSource.text = "source: $it"
            }
        }
    }

    /**
     * Cele mari cât toată înălțimea, cele mici [SMALL_RATIO] din ele, coborâte
     * spre baza celor mari. Dacă patru nu încap pe lățime, se micșorează toate
     * proporțional.
     */
    private fun sizeGauges(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val gap = (GAP_DP * context.resources.displayMetrics.density).toInt()
        val big = minOf(height.toFloat(), (width - 3 * gap) / (2 + 2 * SMALL_RATIO)).toInt()
        val small = (big * SMALL_RATIO).toInt()
        // Centrul celor mici la ~70% din înălțimea celor mari.
        val drop = (big * 0.70f - small / 2f).toInt() - (big - small) / 2
        val specs = listOf(
            binding.gaugeBoost to small, binding.gaugeSpeed to big,
            binding.gaugeRpm to big, binding.gaugeOil to small
        )
        specs.forEachIndexed { i, (view, size) ->
            val lp = view.layoutParams as LinearLayout.LayoutParams
            lp.width = size
            lp.height = size
            lp.marginStart = if (i == 0) 0 else gap
            lp.topMargin = if (size == small) drop.coerceAtLeast(0) else 0
            view.layoutParams = lp
        }
    }

    /** OPTION pe dashboard duce direct în diagnostic — util când datele arată ciudat. */
    override fun onHide() {
        Services.kswCar?.dashboardOpen = false
    }

    override fun onAction(action: LauncherAction): Boolean {
        if (action != LauncherAction.OPTION) return false
        host.push(DiagnosticsScreen(host))
        return true
    }

    private fun render(state: VehicleState) {
        val speed = state.speedKmh
        if (speed != null) binding.gaugeSpeed.setValue(speed.toFloat())
        else binding.gaugeSpeed.setUnknown()

        val rpm = state.rpm
        if (rpm != null) binding.gaugeRpm.setValue(rpm.toFloat())
        else binding.gaugeRpm.setUnknown()

        val boost = state.boostBar
        if (boost != null) binding.gaugeBoost.setValue(boost)
        else binding.gaugeBoost.setUnknown()

        val coolant = state.coolantTempC
        if (coolant != null) binding.gaugeOil.setValue(coolant.toFloat())
        else binding.gaugeOil.setUnknown()

        val warnings = buildList {
            if (state.handbrake == true) add("HANDBRAKE")
            state.doorsOpen.forEach { add(it.label.uppercase()) }
            // Marșarierul e doar afișat. Niciun overlay video — non-goal explicit.
            if (state.reverseGear == true) add("REVERSE")
        }
        binding.txtWarnings.text = warnings.joinToString("  ·  ")
    }

    /** „14.2 V"; galben sub 13 V cu motorul pornit (nu încarcă), roșu sub 11.8 V. */
    private fun renderBattery(volts: Float?) {
        if (volts == null) {
            binding.txtBattery.text = "-- V"
            binding.txtBattery.setTextColor(context.getColor(R.color.text_primary))
            binding.icBattery.setColorFilter(context.getColor(R.color.text_primary))
            return
        }
        binding.txtBattery.text = String.format(java.util.Locale.US, "%.1f V", volts)
        val running = (Services.vehicle.state.value.rpm ?: 0) >= 300
        val color = when {
            volts < 11.8f -> context.getColor(R.color.warn_red)
            running && volts < 13.0f -> context.getColor(R.color.warn_amber)
            else -> context.getColor(R.color.text_primary)
        }
        binding.txtBattery.setTextColor(color)
        binding.icBattery.setColorFilter(color)
    }

    private companion object {
        /** Etichetele cadranelor mici (BOOST, COOLANT), la aceeași înălțime. */
        const val SMALL_CAPTION_LIFT = 0.2f
        /** Turbo și ulei, față de viteză și turații. */
        const val SMALL_RATIO = 0.48f
        const val GAP_DP = 14f
    }
}
