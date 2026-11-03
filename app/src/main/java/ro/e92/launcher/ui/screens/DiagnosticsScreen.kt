package ro.e92.launcher.ui.screens

import ro.e92.launcher.R
import android.view.LayoutInflater
import android.view.ViewGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.e92.launcher.databinding.ScreenDiagnosticsBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.input.KeyEventLog
import ro.e92.launcher.remote.DiagServer
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost

/**
 * Ecranul care înlocuiește laptopul în mașină.
 *
 * Stânga: fiecare tastă primită, cu keycode-ul brut și acțiunea în care s-a
 * tradus — exact ce trebuie ca să mapezi controller-ul iDrive stând pe scaun.
 * Dreapta: starea vehiculului, sursa activă, ultimul broadcast CAN brut cu tot
 * cu extras necunoscute, plus datele de care depinde layout-ul (densitate!).
 */
class DiagnosticsScreen(host: ScreenHost) : Screen(host) {
    override val backgroundRes: Int get() = R.drawable.bg_menu


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
        // Sus, ca să se vadă fără derulare: adresa și cheia le citești de pe
        // ecran și le dai laptopului.
        val remote = DiagServer.address()
            ?.let { "wi-fi diag = http://$it  cheie ${DiagServer.key()}\n\n" }
            ?: "wi-fi diag = no network\n\n"
        binding.txtState.text = remote + DiagnosticsReport.build(context, host.activity.window?.decorView)
    }

    private companion object {
        const val REFRESH_MS = 500L
    }
}
