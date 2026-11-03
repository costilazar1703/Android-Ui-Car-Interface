package ro.e92.launcher.ui.screens

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.DisplayMetrics
import android.view.View
import ro.e92.launcher.can.CanDataSource
import ro.e92.launcher.core.Services
import java.io.File

/**
 * Textul din dreapta ecranului de Diagnostic, construit într-un singur loc ca
 * să-l poată servi și [ro.e92.launcher.remote.DiagServer] pe Wi-Fi. Ce vezi pe
 * ecran în mașină e exact ce primește laptopul.
 */
object DiagnosticsReport {

    fun build(context: Context, decor: View?): String {
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
        decor?.let {
            sb.append("window     = ").append(it.width).append('x')
                .append(it.height).append(" px\n")
        }
        // Dacă asta nu e 160, TOATE valorile în dp din dimens.xml trebuie recalibrate.
        sb.append("density    = ").append(metrics.densityDpi).append(" dpi (x")
            .append(metrics.density).append(")\n")
        sb.append("usable dp  = ").append((metrics.widthPixels / metrics.density).toInt())
            .append('x').append((metrics.heightPixels / metrics.density).toInt()).append("\n\n")

        sb.append("notif acc  = ").append(Services.media.hasNotificationAccess()).append('\n')
        sb.append("location   = ").append(Services.net.hasLocationPermission()).append('\n')
        sb.append("su binary  = ").append(suBinaryPresent()).append('\n')
        // 0 = Android sare peste animații; salutul de pornire nu depinde de asta.
        sb.append("anim scale = ").append(
            runCatching {
                android.provider.Settings.Global.getFloat(
                    context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
                )
            }.getOrDefault(-1f)
        ).append("\n\n")

        appendBluetoothAndMedia(context, sb)

        val raw = Services.broadcastCan.lastRaw.value
        sb.append("last CAN broadcast:\n")
        sb.append(if (raw.isEmpty()) "  (none)" else raw)
        return sb.toString()
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
    private fun appendBluetoothAndMedia(context: Context, sb: StringBuilder) {
        sb.append("--- bluetooth ---\n")
        sb.append("ksw bt type= ").append(
            ro.e92.launcher.core.KswSettings.read(context, ro.e92.launcher.core.KswSettings.BT_TYPE) ?: "—"
        ).append(" (3 = BC6)\n")
        // Data ultimei scrieri pe portul modulului: 2013 = nimeni nu i-a
        // trimis nimic de la boot (unitatea pornește cu ceasul în 2013).
        sb.append("ttyS4 used= ").append(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(File("/dev/ttyS4").lastModified()))
        ).append('\n')
        sb.append("goc_serial= ").append(File("/dev/goc_serial").exists()).append('\n')
        sb.append("feasycom  = ").append(
            runCatching {
                Runtime.getRuntime().exec(arrayOf("getprop", "init.svc.feasycom"))
                    .inputStream.bufferedReader().readText().trim()
            }.getOrNull().orEmpty().ifEmpty { "—" }
        ).append('\n')
        // Setări pe care un reset al interfeței le readuce la valori implicite.
        for ((key, label) in listOf(
            "KESAIWEI_RECORD_CAR_TYPE" to "car type  ",
            "KESAIWEI_RECORD_BT_OFF" to "bt switch ",
            "Set_Canbustype" to "canbus    "
        )) {
            sb.append(label).append("= ")
                .append(ro.e92.launcher.core.KswSettings.read(context, key) ?: "—").append('\n')
        }

        val adapter = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()

        if (adapter == null) {
            sb.append("adapter    = absent (module on MCU?)\n")
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

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sbin/su",
        "/vendor/bin/su"
    )
}
