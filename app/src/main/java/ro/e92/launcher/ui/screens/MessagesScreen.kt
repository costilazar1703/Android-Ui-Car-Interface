package ro.e92.launcher.ui.screens

import android.content.Intent
import android.provider.Settings
import ro.e92.launcher.R
import ro.e92.launcher.ui.ScreenHost

/**
 * „Messages" în format split.
 *
 * Mesajele de pe telefon ajung în mașină prin profilul Bluetooth MAP, care e al
 * unității, nu al nostru — la fel ca la [TelephoneScreen]. Ecranul există ca
 * destinație reală a meniului (cu structura corectă) și duce în aplicația de
 * mesaje a sistemului, în loc să afișeze un inbox fals.
 */
class MessagesScreen(host: ScreenHost) : SplitScreen(host) {

    override val title: String get() = "MESSAGES"
    override val navHeader: String get() = context.getString(R.string.menu_messages)

    override fun navEntries(): List<NavEntry> = listOf(
        NavEntry(NAV_INBOX, context.getString(R.string.msg_inbox)),
        NavEntry(NAV_DEVICE, context.getString(R.string.bt_devices))
    )

    override fun detailTitle(navId: String): String = when (navId) {
        NAV_INBOX -> context.getString(R.string.msg_inbox)
        else -> context.getString(R.string.bt_devices)
    }.uppercase()

    override fun detailHint(navId: String): String =
        if (navId == NAV_INBOX) context.getString(R.string.msg_hint) else ""

    override fun detailRows(navId: String): List<DetailRow> = when (navId) {
        NAV_INBOX -> listOf(
            DetailRow(
                id = "msg_empty",
                title = context.getString(R.string.msg_empty)
            ),
            DetailRow(
                id = "msg_open",
                title = context.getString(R.string.msg_open_app),
                onActivate = { openMessagingApp() }
            )
        )

        else -> listOf(
            DetailRow(
                id = "msg_bt",
                title = context.getString(R.string.set_bluetooth),
                onActivate = { openSystem(Settings.ACTION_BLUETOOTH_SETTINGS) }
            ),
            DetailRow(
                id = "msg_bt_screen",
                title = context.getString(R.string.menu_bluetooth),
                onActivate = { host.push(BluetoothScreen(host)) }
            )
        )
    }

    private fun openMessagingApp() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_APP_MESSAGING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(intent) }
        } else {
            host.push(AppDrawerScreen(host))
        }
    }

    private fun openSystem(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val NAV_INBOX = "msg_inbox"
        const val NAV_DEVICE = "msg_device"
    }
}
