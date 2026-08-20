package ro.e92.launcher.ui.screens

import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.ViewGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.e92.launcher.can.CanDataSource
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ScreenDiagnosticsBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.input.KeyEventLog
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost
import java.io.File

/**
 * Ecranul care înlocuiește laptopul în mașină.
 *
 * Stânga: fiecare tastă primită, cu keycode-ul brut și acțiunea în care s-a
 * tradus — exact ce trebuie ca să mapezi controller-ul iDrive stând pe scaun.
 * Dreapta: starea vehiculului, sursa activă, ultimul broadcast CAN brut cu tot
 * cu extras necunoscute, plus datele de care depinde layout-ul (densitate!).
 */
class DiagnosticsScreen(host: ScreenHost) : Screen(host) {

    override val title: String get() = "DIAGNOSTICS"

    private lateinit var binding: ScreenDiagnosticsBinding
    private var lastKeyRevision = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup) =
        ScreenDiagnosticsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        screenScope.launch {
            while (isActive) {
                renderKeys()
                renderState()
                delay(REFRESH_MS)
            }
        }
    }

    override fun focusTargets(): List<FocusTarget> = listOf(
        FocusTarget(
            id = "clear",
            view = binding.btnClear,
            onActivate = {
                KeyEventLog.clear()
                lastKeyRevision = -1
                renderKeys()
            },
            right = "settings"
        ),
        FocusTarget(
            id = "settings",
            view = binding.btnSettings,
            onActivate = { host.push(SettingsScreen(host)) },
            left = "clear"
        )
    )

    private fun renderKeys() {
        val revision = KeyEventLog.revision
        if (revision == lastKeyRevision) return
        lastKeyRevision = revision
        binding.txtKeys.text = KeyEventLog.snapshot().joinToString("\n")
        binding.scrollKeys.scrollTo(0, 0)
    }

    private fun renderState() {
        val state = Services.vehicle.state.value
        val metrics: DisplayMetrics = context.resources.displayMetrics

        val sb = StringBuilder(512)
        sb.append("speed      = ").append(state.speedKmh ?: "—").append(" km/h\n")
        sb.append("rpm        = ").append(state.rpm ?: "—").append('\n')
        sb.append("handbrake  = ").append(state.handbrake ?: "—").append('\n')
        sb.append("reverse    = ").append(state.reverseGear ?: "—").append('\n')
        sb.append("doors      = ")
            .append(if (state.doorsOpen.isEmpty()) "—" else state.doorsOpen.joinToString(","))
            .append('\n')
        sb.append("age        = ")
            .append(if (state.hasData) "${android.os.SystemClock.elapsedRealtime() - state.timestamp} ms" else "—")
            .append("\n\n")

        sb.append("speed src  = ").append(Services.vehicle.activeSourceId.value).append('\n')
        sb.append("can mode   = ").append(Services.prefs.canMode).append('\n')
        for (source in Services.vehicle.sources) {
            sb.append("  ").append(source.id).append(" · ")
                .append(if (source.isAvailable) "live" else "idle").append('\n')
        }
        sb.append("stale after= ").append(CanDataSource.STALE_AFTER_MS).append(" ms\n\n")

        sb.append("screen     = ").append(metrics.widthPixels).append('x')
            .append(metrics.heightPixels).append(" px\n")
        // Dacă fereastra e mai mică decât ecranul, o bară de sistem încă ocupă
        // spațiu. Dacă sunt egale dar tot vezi butoane sus, e overlay de vendor.
        host.activity.window?.decorView?.let { decor ->
            sb.append("window     = ").append(decor.width).append('x')
                .append(decor.height).append(" px\n")
        }
        // Dacă asta nu e 160, TOATE valorile în dp din dimens.xml trebuie recalibrate.
        sb.append("density    = ").append(metrics.densityDpi).append(" dpi (x")
            .append(metrics.density).append(")\n")
        sb.append("usable dp  = ").append((metrics.widthPixels / metrics.density).toInt())
            .append('x').append((metrics.heightPixels / metrics.density).toInt()).append("\n\n")

        sb.append("notif acc  = ").append(Services.media.hasNotificationAccess()).append('\n')
        sb.append("location   = ").append(Services.net.hasLocationPermission()).append('\n')
        sb.append("su binary  = ").append(suBinaryPresent()).append("\n\n")

        val raw = Services.broadcastCan.lastRaw.value
        sb.append("last CAN broadcast:\n")
        sb.append(if (raw.isEmpty()) "  (niciunul)" else raw)

        binding.txtState.text = sb
    }

    /**
     * Indiciu, nu dovadă: prezența binarului nu garantează că `su` acordă root.
     * Verificarea reală rămâne `adb shell su -c id` din Faza 0.3.
     */
    private fun suBinaryPresent(): Boolean = SU_PATHS.any { File(it).exists() }

    private companion object {
        const val REFRESH_MS = 500L
        val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sbin/su",
            "/vendor/bin/su"
        )
    }
}
