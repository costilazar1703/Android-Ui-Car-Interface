package ro.e92.launcher.ui.screens

import ro.e92.launcher.core.AppLaunch
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.KswBluetooth
import ro.e92.launcher.core.KswSettings
import ro.e92.launcher.core.Services
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.ui.ScreenHost

/**
 * „Bluetooth" în format split: muzica, telefonul și setările.
 *
 * Pe unitatea KSW telefonul e al modulului vendorului, nu al Android-ului, iar
 * ecranul ăsta îl comandă direct prin [ro.e92.launcher.core.KswBluetooth]:
 * starea legăturii, vizibilitatea pentru împerechere, reconectarea, resetul.
 * Nu dublăm un stack: trimitem comenzi aceluiași serviciu pe care îl folosește
 * și aplicația KSW, deci cele două nu se pot contrazice. Pe o unitate fără
 * KSW rămâne vechiul comportament (lista Android + setările de sistem).
 */
class BluetoothScreen(host: ScreenHost) : SplitScreen(host) {
    override val backgroundRes: Int get() = R.drawable.bg_menu_phone


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
        screenScope.launch {
            var lastResult: Int? = ksw.state.value.lastPairResult
            ksw.state.collect { st ->
                if (st.lastPairResult != lastResult) {
                    lastResult = st.lastPairResult
                    st.lastPairResult?.let { result ->
                        android.widget.Toast.makeText(
                            context,
                            if (result == KswBluetooth.PAIR_SUCCESS) R.string.bt_pair_ok else R.string.bt_pair_failed,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                if (selectedNavId == NAV_DEVICES || selectedNavId == NAV_AUDIO) reloadDetail()
            }
        }
        ksw.refresh()
    }

    private val ksw get() = Services.kswBt
    private val useKsw get() = ksw.isInstalled

    /** „Forget all" cere a doua apăsare; prima doar armează. */
    private var forgetArmed = false

    /** Butoanele de volan rămân active și aici. */
    override fun onAction(action: LauncherAction): Boolean = when (action) {
        LauncherAction.MEDIA_NEXT -> { if (kswAudio) ksw.next() else Services.media.next(); true }
        LauncherAction.MEDIA_PREV -> { if (kswAudio) ksw.previous() else Services.media.previous(); true }
        LauncherAction.MEDIA_PLAY_PAUSE -> { if (kswAudio) ksw.playPause() else Services.media.playPause(); true }
        else -> false
    }

    /**
     * Muzica de pe telefon trece prin modulul KSW și de obicei nu apare ca
     * MediaSession în Android - atunci butoanele merg direct la modul.
     */
    private val kswAudio: Boolean
        get() = useKsw && !Services.media.snapshot.value.hasSession &&
            ksw.state.value.link >= KswBluetooth.Link.CONNECTED

    override fun navEntries(): List<NavEntry> = listOf(
        NavEntry(NAV_AUDIO, context.getString(R.string.bt_audio)),
        NavEntry(NAV_DEVICES, context.getString(if (useKsw) R.string.bt_phone else R.string.bt_devices)),
        NavEntry(NAV_SETTINGS, context.getString(R.string.bt_settings))
    )

    override fun detailTitle(navId: String): String = when (navId) {
        NAV_AUDIO -> context.getString(R.string.bt_audio)
        NAV_DEVICES -> context.getString(if (useKsw) R.string.bt_phone else R.string.bt_paired)
        else -> context.getString(R.string.bt_settings)
    }.uppercase()

    override fun detailHint(navId: String): String =
        if (navId == NAV_DEVICES && !useKsw) context.getString(R.string.bt_hint) else ""

    override fun detailRows(navId: String): List<DetailRow> = when (navId) {
        NAV_AUDIO -> audioRows()
        NAV_DEVICES -> if (useKsw) phoneRows() else deviceRows()
        else -> settingsRows()
    }

    // -------------------------------------------------------------------- audio

    private fun audioRows(): List<DetailRow> {
        val media = Services.media.snapshot.value
        if (kswAudio) return kswAudioRows()
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

    private fun kswAudioRows(): List<DetailRow> = listOf(
        DetailRow(
            id = "bt_track",
            title = ksw.state.value.phoneName,
            value = context.getString(R.string.bt_audio)
        ),
        DetailRow(
            id = "bt_playpause",
            title = context.getString(R.string.bt_play) + " / " + context.getString(R.string.bt_pause),
            onActivate = { ksw.playPause() }
        ),
        DetailRow(id = "bt_next", title = context.getString(R.string.bt_next), onActivate = { ksw.next() }),
        DetailRow(id = "bt_prev", title = context.getString(R.string.bt_prev), onActivate = { ksw.previous() })
    )

    // ------------------------------------------------------------------- phone

    private fun phoneRows(): List<DetailRow> {
        val st = ksw.state.value
        val status = when {
            st.restartNeeded -> context.getString(R.string.bt_status_restart)
            st.moduleSilent -> context.getString(R.string.bt_status_silent)
            st.visible && st.link < KswBluetooth.Link.CONNECTED -> context.getString(R.string.bt_status_visible)
            st.link == KswBluetooth.Link.UNAVAILABLE -> context.getString(R.string.bt_status_unavailable)
            st.link == KswBluetooth.Link.IDLE -> context.getString(R.string.bt_status_idle)
            st.link == KswBluetooth.Link.CONNECTING -> context.getString(R.string.bt_status_connecting)
            st.link == KswBluetooth.Link.CONNECTED -> context.getString(R.string.bt_status_connected)
            else -> context.getString(R.string.bt_status_in_call)
        }
        val unit = st.unitName.ifEmpty { "CAR KIT" }
        val pin = st.pin.ifEmpty { "0000" }
        return listOf(
            DetailRow(
                id = "bt_status",
                title = status,
                value = st.phoneName.ifEmpty { if (st.visible) "$unit · PIN $pin" else "" },
                onActivate = { ksw.refresh() }
            ),
            DetailRow(
                id = "bt_make_visible",
                title = context.getString(R.string.bt_make_visible),
                value = context.getString(R.string.bt_make_visible_hint, unit, pin),
                onActivate = { ksw.makeVisible() }
            ),
            DetailRow(
                id = "bt_reconnect",
                title = context.getString(R.string.bt_reconnect),
                onActivate = { ksw.reconnectLast() }
            ),
            resetRow(),
            DetailRow(
                id = "bt_forget_all",
                title = context.getString(R.string.bt_forget_all),
                value = if (forgetArmed) context.getString(R.string.bt_forget_confirm) else "",
                onActivate = {
                    if (forgetArmed) {
                        forgetArmed = false
                        ksw.forgetAll()
                    } else {
                        forgetArmed = true
                    }
                    reloadDetail("bt_forget_all")
                }
            ),
            DetailRow(
                id = "bt_module_type",
                title = context.getString(R.string.bt_module_type),
                value = KswSettings.btTypeLabel(Services.prefs.btModuleType),
                onActivate = {
                    val types = KswSettings.BT_TYPES
                    val next = types[(types.indexOf(Services.prefs.btModuleType) + 1) % types.size]
                    ksw.setModuleType(next)
                    reloadDetail("bt_module_type")
                }
            ),
            DetailRow(
                id = "bt_rename",
                title = context.getString(R.string.bt_rename, Services.prefs.btUnitName),
                value = context.getString(R.string.bt_rename_now, unit),
                onActivate = { ksw.applyIdentity() }
            ),
            DetailRow(
                id = "bt_vendor",
                title = context.getString(R.string.bt_vendor_app),
                onActivate = { AppLaunch.openBluetooth(context) }
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
                        onActivate = { AppLaunch.openBluetooth(context) }
                    )
                )
            }
        }

        rows.add(resetRow())
        rows.add(
            DetailRow(
                id = "bt_connect_new",
                title = context.getString(R.string.bt_connect_new),
                onActivate = { AppLaunch.openBluetooth(context) }
            )
        )
        return rows
    }

    // ----------------------------------------------------------------- settings

    private fun settingsRows(): List<DetailRow> = listOf(
        resetRow(),
        DetailRow(
            id = "bt_sys",
            title = context.getString(R.string.set_bluetooth),
            onActivate = { AppLaunch.openBluetooth(context) }
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

    private fun resetRow() = DetailRow(
        id = "bt_reset",
        title = context.getString(R.string.bt_reset),
        value = context.getString(R.string.bt_reset_hint),
        onActivate = { AppLaunch.resetBluetooth(context) }
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
