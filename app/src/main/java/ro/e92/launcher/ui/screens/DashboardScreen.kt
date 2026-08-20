package ro.e92.launcher.ui.screens

import android.view.LayoutInflater
import android.view.ViewGroup
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

    override val title: String get() = "DASHBOARD"

    private lateinit var binding: ScreenDashboardBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup) =
        ScreenDashboardBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        binding.gaugeSpeed.configure(
            caption = "SPEED",
            unit = context.getString(R.string.unit_kmh),
            maxValue = 260f,
            majorTickStep = 20f
        )
        binding.gaugeRpm.configure(
            caption = "RPM",
            unit = context.getString(R.string.unit_rpm),
            maxValue = 8000f,
            majorTickStep = 1000f,
            redlineFrom = 6500f,
            displayDivisor = 1000f,
            decimals = 1
        )

        screenScope.launch {
            Services.vehicle.state.collect { render(it) }
        }
        screenScope.launch {
            Services.vehicle.activeSourceId.collect {
                binding.txtSource.text = "source: $it"
            }
        }
    }

    /** OPTION pe dashboard duce direct în diagnostic — util când datele arată ciudat. */
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

        binding.txtHandbrake.text = if (state.handbrake == true) "HANDBRAKE" else ""
        binding.txtDoors.text = if (state.doorsOpen.isEmpty()) {
            ""
        } else {
            state.doorsOpen.joinToString(", ") { it.label }
        }
        // Marșarierul e doar afișat. Niciun overlay video — non-goal explicit.
        binding.txtGear.text = if (state.reverseGear == true) "REVERSE" else ""
    }
}
