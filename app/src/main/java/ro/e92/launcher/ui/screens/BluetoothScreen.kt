package ro.e92.launcher.ui.screens

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.ui.ScreenHost

/**
 * „Bluetooth Audio" în format split.
 *
 * Ce face cu adevărat: comandă sursa A2DP care cântă, prin MediaSession (adică
 * exact aceleași butoane pe care le folosește și ecranul Media). Ce NU face:
 * împerecherea. Pe unitățile astea stack-ul Bluetooth e al vendorului, iar
 * dialogul lui de pairing e singurul care funcționează — încercarea de a-l
 * dubla din launcher ar produce două stări care se contrazic. De aceea
 * „Connect new mobile" deschide setările de sistem în loc să pretindă că
 * gestionează el legătura.
 */
class BluetoothScreen(host: ScreenHost) : SplitScreen(host) {

    override val title: String get() = "BLUETOOTH"
    override val navHeader: String get() = context.getString(R.string.menu_bluetooth)

    override fun onShow() {
        super.onShow()
        Services.media.refreshSessions()

        // Panoul „Audio" arată piesa curentă: trebuie să se împrospăteze singur
        // cât timp ecranul e vizibil, altfel rămâne pe ce era la intrare.
        screenScope.launch {
            Services.media.snapshot.collect {
                if (selectedNavId == NAV_AUDIO) reloadDetail()
            }
        }
    }

    /** Butoanele de volan rămân active și aici. */
    override fun onAction(action: LauncherAction): Boolean = when (action) {
        LauncherAction.MEDIA_NEXT -> { Services.media.next(); true }
        LauncherAction.MEDIA_PREV -> { Services.media.previous(); true }
        LauncherAction.MEDIA_PLAY_PAUSE -> { Services.media.playPause(); true }
        else -> false
    }

    override fun navEntries(): List<NavEntry> = listOf(
        NavEntry(NAV_AUDIO, context.getString(R.string.bt_audio)),
        NavEntry(NAV_DEVICES, context.getString(R.string.bt_devices)),
        NavEntry(NAV_SETTINGS, context.getString(R.string.bt_settings))
    )

    override fun detailTitle(navId: String): String = when (navId) {
        NAV_AUDIO -> context.getString(R.string.bt_audio)
        NAV_DEVICES -> context.getString(R.string.bt_paired)
        else -> context.getString(R.string.bt_settings)
    }.uppercase()

    override fun detailHint(navId: String): String =
        if (navId == NAV_DEVICES) context.getString(R.string.bt_hint) else ""

    override fun detailRows(navId: String): List<DetailRow> = when (navId) {
        NAV_AUDIO -> audioRows()
        NAV_DEVICES -> deviceRows()
        else -> settingsRows()
    }

    // -------------------------------------------------------------------- audio

    private fun audioRows(): List<DetailRow> {
        val media = Services.media.snapshot.value
        if (!media.hasSession) {
            return listOf(
                DetailRow(
                    id = "bt_no_source",
                    title = context.getString(R.string.media_no_session),
                    value = if (Services.media.hasNotificationAccess()) "" else
                        context.getString(R.string.media_grant_access),
                    onActivate = { openSystem(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
                )
            )
        }
        return listOf(
            DetailRow(
                id = "bt_track",
                title = media.title.orEmpty(),
                value = media.artist.orEmpty()
            ),
            DetailRow(
                id = "bt_playpause",
                title = context.getString(
                    if (media.isPlaying) R.string.bt_pause else R.string.bt_play
                ),
                onActivate = {
                    Services.media.playPause()
                    reloadDetail("bt_playpause")
                }
            ),
            DetailRow(
                id = "bt_next",
                title = context.getString(R.string.bt_next),
                onActivate = { Services.media.next() }
            ),
            DetailRow(
                id = "bt_prev",
                title = context.getString(R.string.bt_prev),
                onActivate = { Services.media.previous() }
            ),
            DetailRow(
                id = "bt_open_media",
                title = context.getString(R.string.media_sources),
                onActivate = { host.push(MediaScreen(host)) }
            )
        )
    }

    // ------------------------------------------------------------------ devices

    /**
     * Lista de aparate împerecheate se citește direct din adaptor. Fără
     * permisiune sau fără adaptor (emulator) returnează null, iar noi spunem
     * asta explicit în loc să afișăm o listă goală care pare stricată.
     */
    private fun deviceRows(): List<DetailRow> {
        val bonded = runCatching {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.bondedDevices
        }.getOrNull()

        val rows = ArrayList<DetailRow>(4)
        if (bonded == null) {
            rows.add(
                DetailRow(
                    id = "bt_unavailable",
                    title = context.getString(R.string.bt_unavailable)
                )
            )
        } else if (bonded.isEmpty()) {
            rows.add(
                DetailRow(
                    id = "bt_none",
                    title = context.getString(R.string.bt_no_devices)
                )
            )
        } else {
            for (device in bonded) {
                val name = runCatching { device.name }.getOrNull().orEmpty()
                rows.add(
                    DetailRow(
                        id = "bt_dev_${device.address}",
                        title = if (name.isEmpty()) device.address else name,
                        value = device.address,
                        // Conectarea efectivă e a stack-ului vendorului; noi
                        // ducem utilizatorul acolo unde poate acționa.
                        onActivate = { openSystem(Settings.ACTION_BLUETOOTH_SETTINGS) }
                    )
                )
            }
        }

        rows.add(
            DetailRow(
                id = "bt_connect_new",
                title = context.getString(R.string.bt_connect_new),
                onActivate = { openSystem(Settings.ACTION_BLUETOOTH_SETTINGS) }
            )
        )
        return rows
    }

    // ----------------------------------------------------------------- settings

    private fun settingsRows(): List<DetailRow> = listOf(
        DetailRow(
            id = "bt_sys",
            title = context.getString(R.string.set_bluetooth),
            onActivate = { openSystem(Settings.ACTION_BLUETOOTH_SETTINGS) }
        ),
        DetailRow(
            id = "bt_notif",
            title = context.getString(R.string.set_notification_access),
            value = context.getString(
                if (Services.media.hasNotificationAccess()) R.string.granted
                else R.string.not_granted
            ),
            onActivate = { openSystem(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        )
    )

    private fun openSystem(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val NAV_AUDIO = "bt_audio"
        const val NAV_DEVICES = "bt_devices"
        const val NAV_SETTINGS = "bt_settings"
    }
}
