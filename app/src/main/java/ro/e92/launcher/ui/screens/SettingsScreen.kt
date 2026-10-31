package ro.e92.launcher.ui.screens

import android.content.Intent
import android.provider.Settings
import ro.e92.launcher.R
import ro.e92.launcher.core.Prefs
import ro.e92.launcher.core.CrashGuard
import ro.e92.launcher.core.Services
import ro.e92.launcher.core.SettingsBackup
import ro.e92.launcher.core.Storage
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.ui.ScreenHost

/**
 * Setările launcher-ului, în formatul split BMW.
 *
 * Patru categorii, dintre care două rezolvă probleme concrete de teren:
 *
 *  - **Assigned apps** — meniurile CarPlay / Dashboard / Car Info lansează software
 *    terț. Package name-ul se ALEGE dintr-o listă de aplicații instalate, nu se
 *    tastează: pe un ecran fără tastatură fizică și cu navigare din rotiță,
 *    introducerea manuală a unui string de tip `com.vendor.autoplay` e un mod
 *    sigur de a greși. Valoarea salvată e același String cerut de `Prefs`.
 *
 *  - **Button mapping** — folosește `pendingLearn` din HardKeyRouter: apeși rândul,
 *    apoi butonul fizic. Nu are nevoie de laptop, ceea ce contează pentru că
 *    keycode-urile reale ale controller-ului iDrive se află abia în mașină.
 */
class SettingsScreen(
    host: ScreenHost,
    /** Categoria pe care se deschide, ceruta de randul din meniul cu rotita. */
    override val initialNavId: String? = null
) : SplitScreen(host) {
    override val backgroundRes: Int get() = R.drawable.bg_menu_tech


    override val title: String get() = "SETTINGS"
    override val navHeader: String get() = context.getString(R.string.menu_settings)

    override fun onShow() {
        super.onShow()
        // Modul „învață" raportează înapoi aici ca să reîmprospăteze rândul legat.
        Services.keys.onLearned = { _, action -> reloadDetail("key_${action.name}") }
    }

    override fun onHide() {
        // Nu lăsăm modul „învață" activ după plecarea din ecran: ar captura
        // următoarea tastă apăsată oriunde în launcher.
        Services.keys.pendingLearn = null
        Services.keys.onLearned = null
    }

    override fun navEntries(): List<NavEntry> = listOf(
        NavEntry(NAV_APPS, context.getString(R.string.set_nav_assigned)),
        NavEntry(NAV_BUTTONS, context.getString(R.string.set_nav_buttons)),
        NavEntry(NAV_LAUNCHER, context.getString(R.string.set_nav_launcher)),
        NavEntry(NAV_SYSTEM, context.getString(R.string.set_nav_system))
    )

    override fun detailTitle(navId: String): String = when (navId) {
        NAV_APPS -> context.getString(R.string.set_nav_assigned)
        NAV_BUTTONS -> context.getString(R.string.set_nav_buttons)
        NAV_LAUNCHER -> context.getString(R.string.set_nav_launcher)
        else -> context.getString(R.string.set_nav_system)
    }.uppercase()

    override fun detailHint(navId: String): String = when (navId) {
        NAV_APPS -> context.getString(R.string.set_assigned_hint)
        NAV_BUTTONS -> context.getString(R.string.set_keymap_hint)
        NAV_SYSTEM -> context.getString(R.string.set_system_hint)
        else -> ""
    }

    override fun detailRows(navId: String): List<DetailRow> = when (navId) {
        NAV_APPS -> assignedAppRows()
        NAV_BUTTONS -> keymapRows()
        NAV_LAUNCHER -> launcherRows()
        else -> systemRows()
    }

    // ------------------------------------------------------- aplicații atribuite

    private fun assignedAppRows(): List<DetailRow> = listOf(
        appRow(
            id = "pkg_carplay",
            title = context.getString(R.string.set_carplay_pkg),
            current = Services.prefs.carPlayPackage,
            assign = { Services.prefs.carPlayPackage = it }
        ),
        appRow(
            id = "pkg_dashboard",
            title = context.getString(R.string.set_dashboard_pkg),
            current = Services.prefs.dashboardPackage,
            emptyLabel = context.getString(R.string.set_dashboard_builtin),
            assign = { Services.prefs.dashboardPackage = it }
        ),
        appRow(
            id = "pkg_carinfo",
            title = context.getString(R.string.set_carinfo_pkg),
            current = Services.prefs.carInfoPackage,
            assign = { Services.prefs.carInfoPackage = it }
        ),
        appRow(
            id = "pkg_nav",
            title = context.getString(R.string.set_nav_app),
            current = Services.prefs.navPackage,
            assign = { Services.prefs.navPackage = it }
        ),
        appRow(
            id = "pkg_maps",
            title = context.getString(R.string.nav_pick_maps),
            current = Services.prefs.mapsPackage,
            assign = { Services.prefs.mapsPackage = it }
        ),
        appRow(
            id = "pkg_weather",
            title = context.getString(R.string.menu_weather),
            current = Services.prefs.weatherPackage,
            assign = { Services.prefs.weatherPackage = it }
        ),
        appRow(
            id = "pkg_browser",
            title = context.getString(R.string.nav_pick_chrome),
            current = Services.prefs.browserPackage,
            assign = { Services.prefs.browserPackage = it }
        )
    )

    private fun appRow(
        id: String,
        title: String,
        current: String,
        assign: (String) -> Unit,
        emptyLabel: String = context.getString(R.string.unbound)
    ) = DetailRow(
        id = id,
        title = title,
        value = if (current.isEmpty()) emptyLabel else labelFor(current),
        onActivate = {
            host.push(AppDrawerScreen(host) { entry -> assign(entry.packageName) })
        },
        onOption = {
            assign("")
            reloadDetail(id)
        }
    )

    /** Numele afișat al aplicației, cu package name-ul ca rezervă dacă a fost dezinstalată. */
    private fun labelFor(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    // ------------------------------------------------------------ maparea tastelor

    private fun keymapRows(): List<DetailRow> {
        val learning = Services.keys.pendingLearn
        return LauncherAction.entries.map { action ->
            val keys = Services.keys.keysFor(action)
            DetailRow(
                id = "key_${action.name}",
                title = action.label,
                value = when {
                    action == learning -> context.getString(R.string.learn_key_prompt)
                    keys.isEmpty() -> context.getString(R.string.unbound)
                    else -> keys.joinToString(", ")
                },
                onActivate = {
                    Services.keys.pendingLearn = action
                    reloadDetail("key_${action.name}")
                },
                onOption = {
                    keys.forEach { Services.keys.unbind(it) }
                    reloadDetail("key_${action.name}")
                }
            )
        } + DetailRow(
            id = "key_reset",
            title = context.getString(R.string.set_reset_keymap),
            onActivate = {
                Services.keys.resetToDefaults()
                reloadDetail("key_reset")
            }
        )
    }

    // ----------------------------------------------------------------- launcher

    private fun launcherRows(): List<DetailRow> = listOf(
        DetailRow(
            id = "can_source",
            title = context.getString(R.string.set_can_source),
            value = Services.prefs.canMode,
            onActivate = {
                Services.prefs.canMode = nextCanMode(Services.prefs.canMode)
                Services.rebuildCanSources()
                reloadDetail("can_source")
            }
        ),
        DetailRow(
            id = "animations",
            title = context.getString(R.string.set_animations),
            value = context.getString(
                if (Services.prefs.animationsEnabled) R.string.on else R.string.off
            ),
            onActivate = {
                Services.prefs.animationsEnabled = !Services.prefs.animationsEnabled
                reloadDetail("animations")
            }
        ),
        DetailRow(
            id = "diagnostics",
            title = context.getString(R.string.set_diagnostics),
            onActivate = { host.push(DiagnosticsScreen(host)) }
        ),
        // Salutul de mod se declanseaza normal din CAN, pe tranzitia spre Sport.
        // Randul asta exista ca sa poata fi vazut si reglat fara masina - si ca
        // sa se poata verifica in masina ca animatia merge, separat de intrebarea
        // daca unitatea raporteaza sau nu butonul Sport.
        DetailRow(
            id = "greeting_name",
            title = context.getString(R.string.set_greeting_name),
            value = Services.prefs.greetingName.ifBlank { "—" },
            onActivate = { editGreetingName() },
            onOption = {
                Services.prefs.greetingName = ""
                reloadDetail("greeting_name")
            }
        ),
        DetailRow(
            id = "sport_preview",
            title = context.getString(R.string.diag_sport_test),
            onActivate = { host.push(DriveSportScreen(host)) }
        ),
        // ---- backup si jurnale ----
        DetailRow(
            id = "backup_now",
            title = context.getString(R.string.backup_now),
            value = backupStatus(),
            onActivate = {
                val ok = SettingsBackup.backupNow(context, Services.prefs)
                toast(if (ok) R.string.backup_done else R.string.backup_failed)
                reloadDetail("backup_now")
            }
        ),
        DetailRow(
            id = "backup_restore",
            title = context.getString(R.string.backup_restore),
            onActivate = {
                if (SettingsBackup.restoreNow(context)) {
                    toast(R.string.backup_restored)
                    // Componentele citesc setarile o singura data, la pornire.
                    root?.postDelayed({ CrashGuard.restart(context) }, 1200)
                } else {
                    toast(R.string.backup_missing)
                }
            }
        ),
        DetailRow(
            id = "last_crash",
            title = context.getString(R.string.crash_last),
            value = CrashGuard.lastSummary(context).ifEmpty { context.getString(R.string.crash_none) }
        )
    )

    /** Unde e backup-ul si de cand - util de citit inainte sa reinstalezi ceva. */
    private fun backupStatus(): String {
        val file = SettingsBackup.lastBackup(context) ?: return context.getString(R.string.backup_never)
        val age = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(file.lastModified()))
        val where = if (Storage.isShared(context)) "/sdcard/E92Launcher" else context.getString(R.string.backup_private)
        return "$age · $where"
    }

    /**
     * Singurul dialog de text din aplicatie, si e intentionat un AlertDialog:
     * un nume se scrie cu tastatura de pe ecran, adica prin touch - rotita nu
     * poate tasta oricum, deci faptul ca dialogul isi ia singur tastele nu
     * strica nimic din navigarea cu rotita.
     */
    private fun editGreetingName() {
        val input = android.widget.EditText(context).apply {
            setText(Services.prefs.greetingName)
            setSelection(text.length)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            typeface = context.resources.getFont(R.font.inter_regular)
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val save = {
            Services.prefs.greetingName = input.text.toString()
            reloadDetail("greeting_name")
        }
        val dialog = android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.set_greeting_name)
            .setMessage(R.string.set_greeting_hint)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        // Enter-ul tastaturii salveaza: e butonul apasat instinctiv, iar fara
        // asta doar inchidea tastatura si numele nu se schimba.
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, _, _ ->
            save()
            dialog.dismiss()
            true
        }
        dialog.show()
    }

    private fun toast(res: Int) =
        android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------- sistem

    private fun systemRows(): List<DetailRow> = listOf(
        DetailRow(
            id = "sys_android",
            title = context.getString(R.string.set_android_settings),
            onActivate = { openSystem(Settings.ACTION_SETTINGS) }
        ),
        DetailRow(
            id = "sys_wifi",
            title = context.getString(R.string.set_wifi),
            onActivate = { Services.net.openWifiSettings() }
        ),
        DetailRow(
            id = "sys_bt",
            title = context.getString(R.string.set_bluetooth),
            onActivate = { openSystem(Settings.ACTION_BLUETOOTH_SETTINGS) }
        ),
        DetailRow(
            id = "sys_notif",
            title = context.getString(R.string.set_notification_access),
            value = context.getString(
                if (Services.media.hasNotificationAccess()) R.string.granted
                else R.string.not_granted
            ),
            onActivate = { openSystem(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        )
    )

    private fun nextCanMode(current: String): String = when (current) {
        Prefs.CAN_AUTO -> Prefs.CAN_BROADCAST
        Prefs.CAN_BROADCAST -> Prefs.CAN_GPS
        Prefs.CAN_GPS -> Prefs.CAN_MOCK
        else -> Prefs.CAN_AUTO
    }

    private fun openSystem(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    companion object {
        const val NAV_APPS = "nav_apps"
        const val NAV_BUTTONS = "nav_buttons"
        const val NAV_LAUNCHER = "nav_launcher"
        const val NAV_SYSTEM = "nav_system"
    }
}
