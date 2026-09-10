package ro.e92.launcher.ui.screens

import android.content.Intent
import android.provider.Settings
import ro.e92.launcher.R
import ro.e92.launcher.ui.ScreenHost

/**
 * „Telephone" în format split.
 *
 * Onest despre ce e cablat și ce nu: apelurile trec prin profilurile HFP/PBAP ale
 * unității, nu prin Android-ul pe care rulăm noi. Până când se stabilește dacă
 * unitatea expune un dialer lansabil (Open Question 6), rândurile duc în dialer-ul
 * de sistem și în agenda de sistem — care există pe orice build — în loc să
 * afișeze liste goale care par defecte.
 */
class TelephoneScreen(host: ScreenHost) : SplitScreen(host) {

    override val title: String get() = "TELEPHONE"
    override val navHeader: String get() = context.getString(R.string.menu_telephone)

    override fun navEntries(): List<NavEntry> = listOf(
        NavEntry(NAV_RECENT, context.getString(R.string.tel_recent)),
        NavEntry(NAV_CONTACTS, context.getString(R.string.tel_contacts)),
        NavEntry(NAV_DIALPAD, context.getString(R.string.tel_dialpad)),
        NavEntry(NAV_DEVICE, context.getString(R.string.bt_connect_new))
    )

    override fun detailTitle(navId: String): String = when (navId) {
        NAV_RECENT -> context.getString(R.string.tel_recent)
        NAV_CONTACTS -> context.getString(R.string.tel_contacts)
        NAV_DIALPAD -> context.getString(R.string.tel_dialpad)
        else -> context.getString(R.string.bt_devices)
    }.uppercase()

    override fun detailHint(navId: String): String = when (navId) {
        NAV_RECENT, NAV_CONTACTS -> context.getString(R.string.tel_hint)
        NAV_DEVICE -> context.getString(R.string.bt_hint)
        else -> ""
    }

    override fun detailRows(navId: String): List<DetailRow> = when (navId) {
        NAV_RECENT -> listOf(
            DetailRow(
                id = "tel_recent_open",
                title = context.getString(R.string.tel_open_dialer),
                onActivate = { openDialer() }
            ),
            DetailRow(
                id = "tel_recent_pending",
                title = context.getString(R.string.tel_pending_hfp)
            )
        )

        NAV_CONTACTS -> listOf(
            DetailRow(
                id = "tel_contacts_open",
                title = context.getString(R.string.tel_open_contacts),
                onActivate = { openContacts() }
            ),
            DetailRow(
                id = "tel_contacts_pending",
                title = context.getString(R.string.tel_pending_pbap)
            )
        )

        NAV_DIALPAD -> listOf(
            DetailRow(
                id = "tel_dial",
                title = context.getString(R.string.tel_open_dialer),
                onActivate = { openDialer() }
            )
        )

        else -> listOf(
            DetailRow(
                id = "tel_bt",
                title = context.getString(R.string.set_bluetooth),
                onActivate = { openSystem(Settings.ACTION_BLUETOOTH_SETTINGS) }
            ),
            DetailRow(
                id = "tel_bt_screen",
                title = context.getString(R.string.menu_bluetooth),
                onActivate = { host.push(BluetoothScreen(host)) }
            )
        )
    }

    private fun openDialer() {
        val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(intent) }
        }
    }

    private fun openContacts() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setType("vnd.android.cursor.dir/contact")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(intent) }
        } else {
            // Multe build-uri de unitate n-au aplicație de contacte; app drawer-ul
            // e alternativa care nu minte.
            host.push(AppDrawerScreen(host))
        }
    }

    private fun openSystem(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val NAV_RECENT = "tel_recent"
        const val NAV_CONTACTS = "tel_contacts"
        const val NAV_DIALPAD = "tel_dialpad"
        const val NAV_DEVICE = "tel_device"
    }
}
