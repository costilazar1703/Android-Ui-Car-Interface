package ro.e92.launcher.ui.screens

import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.ViewGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.e92.launcher.can.CanDataSource
import ro.e92.launcher.core.Services
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
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

        appendBluetoothAndMedia(sb)

        val raw = Services.broadcastCan.lastRaw.value
        sb.append("last CAN broadcast:\n")
        sb.append(if (raw.isEmpty()) "  (niciunul)" else raw)

        binding.txtState.text = sb
    }

    /**
     * Ce vede ANDROID din Bluetooth și din media.
     *
     * Blocul ăsta există ca să răspundă la o singură întrebare, direct în mașină:
     * modulul Bluetooth al unității (BC5 / BC6 / BC8) trece prin Android sau e
     * legat direct la MCU?
     *
     * Pe multe unități aftermarket modulul vorbește cu MCU-ul pe serial, iar
     * aplicația de Bluetooth a vendorului doar îi trimite comenzi. În cazul ăla
     * Android nu vede nici adaptor, nici aparate împerecheate, iar sunetul ajunge
     * la amplificator fără să treacă prin sistemul de operare — deci nu există
     * nici MediaSession, iar butoanele noastre n-au ce comanda.
     *
     * Cum se citește, cu muzica pornită de pe telefon:
     *   - „sessions" listează ceva     → merge, inclusiv din meniul principal
     *   - adapter absent / bonded 0    → modulul e pe MCU, comenzile rămân la vendor
     *   - sessions 0, notif acc false  → nu e modulul, e permisiunea neacordată
     */
    private fun appendBluetoothAndMedia(sb: StringBuilder) {
        sb.append("--- bluetooth ---\n")

        val adapter = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()

        if (adapter == null) {
            sb.append("adapter    = absent (modul pe MCU?)\n")
        } else {
            sb.append("adapter    = ")
                .append(if (adapter.isEnabled) "on" else "off")
                .append(" · ").append(runCatching { adapter.name }.getOrNull() ?: "—")
                .append('\n')

            val bonded = runCatching { adapter.bondedDevices }.getOrNull()
            sb.append("bonded     = ").append(bonded?.size ?: 0).append('\n')
            bonded?.take(4)?.forEach { device ->
                sb.append("  ")
                    .append(runCatching { device.name }.getOrNull() ?: device.address)
                    .append('\n')
            }
            sb.append("a2dp       = ")
                .append(profileName(runCatching {
                    adapter.getProfileConnectionState(BluetoothProfile.A2DP)
                }.getOrDefault(-1)))
                .append('\n')
            sb.append("headset    = ")
                .append(profileName(runCatching {
                    adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                }.getOrDefault(-1)))
                .append('\n')
        }

        sb.append("\n--- media ---\n")
        val sessions = Services.media.sessions.value
        sb.append("sessions   = ").append(sessions.size).append('\n')
        sessions.take(5).forEach { session ->
            sb.append("  ").append(session.packageName)
                .append(" · ").append(if (session.isPlaying) "playing" else "paused")
                .append('\n')
        }
        val snapshot = Services.media.snapshot.value
        sb.append("controlled = ").append(snapshot.packageName ?: "—").append('\n')
        sb.append("track      = ").append(snapshot.title ?: "—").append("\n\n")
    }

    private fun profileName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        else -> "—"
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
