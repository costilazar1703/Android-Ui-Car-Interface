package ro.e92.launcher.ui.screens

import ro.e92.launcher.core.KswVolume
import ro.e92.launcher.core.KswSettings
import ro.e92.launcher.core.AppLaunch
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.Prefs
import ro.e92.launcher.core.CrashGuard
import ro.e92.launcher.core.SelfUpdate
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
    override val singleSection: Boolean get() = initialNavId != null
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
        NavEntry(NAV_CAR, context.getString(R.string.set_nav_car)),
        NavEntry(NAV_SYSTEM, context.getString(R.string.set_nav_system))
    )

    override fun detailTitle(navId: String): String = when (navId) {
        NAV_CAR -> context.getString(R.string.set_nav_car)
        NAV_LAUNCHER -> context.getString(R.string.set_nav_launcher)
        else -> context.getString(R.string.set_nav_system)
    }.uppercase()

    override fun detailHint(navId: String): String = when (navId) {
        NAV_CAR -> context.getString(R.string.set_car_hint)
        NAV_SYSTEM, NAV_APPS -> context.getString(R.string.set_assigned_hint)
        else -> ""
    }

    override fun detailRows(navId: String): List<DetailRow> = when (navId) {
        // Setările launcher-ului stau sub cele ale unității, cu titlul lor: meniul cu rotița
        // rămâne la patru rânduri de mărimea normală.
        NAV_CAR -> carUnitRows() +
            DetailRow(id = "hdr_launcher", title = context.getString(R.string.set_nav_launcher), header = true) +
            launcherRows() +
            greetingRows()
        NAV_LAUNCHER -> launcherRows()
        // System cuprinde și Assigned apps și Button mapping (2026-11-17): SETUP are trei rânduri,
        // cu Brightness la mijloc. NAV_APPS și NAV_BUTTONS deschid tot aici.
        else -> systemRows() +
            DetailRow(id = "hdr_assigned", title = context.getString(R.string.set_nav_assigned), header = true) +
            assignedAppRows() +
            DetailRow(id = "hdr_buttons", title = context.getString(R.string.set_nav_buttons), header = true) +
            keymapRows()
    }

    // ----------------------------------------------------------- car & unit
    //
    // Tot ce am reparat pe unitatea KSW, într-un singur loc, ca să se poată
    // repara din mașină fără laptop.

    private fun carUnitRows(): List<DetailRow> {
        val ksw = Services.kswBt
        val prefs = Services.prefs
        return listOfNotNull(
            DetailRow(
                id = "car_live_can",
                title = context.getString(R.string.set_live_can),
                value = context.getString(if (prefs.liveCanEverywhere) R.string.on else R.string.off),
                onActivate = {
                    prefs.liveCanEverywhere = !prefs.liveCanEverywhere
                    Services.kswCar?.updateCanForwarding()
                    reloadDetail("car_live_can")
                }
            ),
            DetailRow(
                id = "car_bt_repair",
                title = context.getString(R.string.set_bt_repair),
                value = ksw.stackStatus() + " · " + KswSettings.btTypeLabel(prefs.btModuleType),
                onActivate = {
                    ksw.repair()
                    screenScope.launch {
                        kotlinx.coroutines.delay(5_000)
                        reloadDetail("car_bt_repair")
                    }
                }
            ),
            DetailRow(
                id = "car_bt_engine",
                title = context.getString(R.string.set_bt_engine),
                value = context.getString(
                    if (prefs.btEngine == Prefs.BT_ENGINE_E92) R.string.set_bt_engine_e92
                    else R.string.set_bt_engine_ksw
                ),
                onActivate = {
                    ksw.setEngine(
                        if (prefs.btEngine == Prefs.BT_ENGINE_E92) Prefs.BT_ENGINE_KSW
                        else Prefs.BT_ENGINE_E92
                    )
                    reloadDetail("car_bt_engine")
                }
            ),
            DetailRow(
                id = "car_battery",
                title = context.getString(R.string.set_battery),
                value = Services.vehicle.state.value.batteryV?.let { "%.1f V".format(it) }
                    ?: context.getString(R.string.set_battery_no_obd),
                onActivate = { reloadDetail("car_battery") }
            ),
            DetailRow(
                id = "car_bt_type",
                title = context.getString(R.string.bt_module_type),
                value = KswSettings.btTypeLabel(prefs.btModuleType),
                onActivate = {
                    val types = KswSettings.BT_TYPES
                    ksw.setModuleType(types[(types.indexOf(prefs.btModuleType) + 1) % types.size])
                    reloadDetail("car_bt_type")
                }
            ),
            DetailRow(
                id = "car_volume",
                title = context.getString(R.string.set_unit_volume),
                value = "${prefs.unitVolume} / ${KswVolume.MAX}",
                onActivate = {
                    val steps = KswVolume.STEPS
                    val next = steps[(steps.indexOf(prefs.unitVolume) + 1) % steps.size]
                    prefs.unitVolume = next
                    KswVolume.set(context, next)
                    reloadDetail("car_volume")
                }
            ),
            DetailRow(
                id = "car_type",
                title = context.getString(R.string.set_car_type),
                value = carTypeValue(),
                onActivate = {
                    val keys = KswSettings.CAR_TYPES.keys.toList()
                    val current = KswSettings.read(context, KswSettings.CAR_TYPE)
                    val next = keys[(keys.indexOf(current) + 1) % keys.size]
                    KswSettings.write(context, KswSettings.CAR_TYPE, next)
                    reloadDetail("car_type")
                }
            ),
            DetailRow(
                id = "car_bt_music_only",
                title = context.getString(R.string.set_bt_music_only),
                value = context.getString(if (prefs.btMusicOnly) R.string.on else R.string.off),
                onActivate = {
                    prefs.btMusicOnly = !prefs.btMusicOnly
                    Services.kswBt.applyProfile()
                    reloadDetail("car_bt_music_only")
                }
            ),
            DetailRow(
                id = "car_time_source",
                title = context.getString(R.string.set_time_source),
                value = context.getString(
                    when (KswSettings.read(context, KswSettings.TIME_SOURCE)) {
                        KswSettings.TIME_FROM_MCU -> R.string.time_source_mcu
                        KswSettings.TIME_FROM_GPS -> R.string.time_source_gps
                        else -> R.string.time_source_network
                    }
                ),
                onActivate = {
                    val next = when (KswSettings.read(context, KswSettings.TIME_SOURCE)) {
                        KswSettings.TIME_FROM_MCU -> KswSettings.TIME_FROM_GPS
                        KswSettings.TIME_FROM_GPS -> KswSettings.TIME_FROM_NETWORK
                        else -> KswSettings.TIME_FROM_MCU
                    }
                    KswSettings.write(context, KswSettings.TIME_SOURCE, next)
                    reloadDetail("car_time_source")
                }
            ),
            DetailRow(
                id = "car_reverse_scene",
                title = context.getString(R.string.set_reverse_scene),
                value = context.getString(if (prefs.reverseScene) R.string.on else R.string.off),
                onActivate = {
                    prefs.reverseScene = !prefs.reverseScene
                    Services.reverse.applyCameraMode()
                    reloadDetail("car_reverse_scene")
                }
            ),
            // Bipurile senzorilor în scena noastră; se aud doar cu radioul mașinii pornit.
            DetailRow(
                id = "car_park_beeps",
                title = context.getString(R.string.set_park_beeps),
                value = context.getString(if (prefs.parkBeeps) R.string.on else R.string.off),
                onActivate = {
                    prefs.parkBeeps = !prefs.parkBeeps
                    reloadDetail("car_park_beeps")
                }
            ),
            DetailRow(
                id = "car_park_beep_test",
                title = context.getString(R.string.set_park_beep_test),
                onActivate = {
                    runCatching {
                        val beeper = ro.e92.launcher.media.ParkBeeper(context)
                        beeper.testBeep()
                        root?.postDelayed({ beeper.release() }, 800L)
                    }.onFailure { ro.e92.launcher.core.EventLog.log("Reverse", "bip de test: ${it.message}") }
                }
            ),
            DetailRow(
                id = "car_block_reopen",
                title = context.getString(R.string.set_block_ksw_reopen),
                value = context.getString(if (prefs.blockKswReopen) R.string.on else R.string.off),
                onActivate = {
                    prefs.blockKswReopen = !prefs.blockKswReopen
                    // Oprit = lăsăm unitatea să-și redeschidă sursa, ca înainte.
                    KswSettings.write(context, KswSettings.MODE_MEMORY, if (prefs.blockKswReopen) "0" else "1")
                    reloadDetail("car_block_reopen")
                }
            ),
            DetailRow(
                id = "car_start_guard",
                title = context.getString(R.string.set_start_guard),
                value = context.getString(if (prefs.startGuard) R.string.on else R.string.off),
                onActivate = {
                    prefs.startGuard = !prefs.startGuard
                    reloadDetail("car_start_guard")
                }
            )
        )
    }

    /**
     * Ultimele din Car & unit: salutul de la pornire. Volumul lui, pe scara unității, nu depinde
     * de cât e dată unitatea; „Play greeting" îl pornește ca să-l reglezi (cerut 2026-11-17).
     */
    private fun greetingRows(): List<DetailRow> {
        val prefs = Services.prefs
        return listOf(
            DetailRow(
                id = "car_greeting_volume",
                title = context.getString(R.string.set_greeting_volume),
                value = "${prefs.greetingVolume} / ${KswVolume.MAX}",
                onActivate = {
                    val steps = KswVolume.GREETING_STEPS
                    val i = steps.indexOf(prefs.greetingVolume)
                    prefs.greetingVolume = steps[(if (i < 0) 0 else i + 1) % steps.size]
                    reloadDetail("car_greeting_volume")
                }
            ),
            DetailRow(
                id = "car_greeting_play",
                title = context.getString(R.string.set_greeting_play),
                onActivate = { ro.e92.launcher.media.GreetingSound.play(context, force = true) }
            )
        )
    }

    private fun carTypeValue(): String {
        val v = KswSettings.read(context, KswSettings.CAR_TYPE) ?: return "—"
        return "$v · ${KswSettings.CAR_TYPES[v] ?: "?"}"
    }

    private fun openPackage(pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent().setPackage(pkg).setAction(Intent.ACTION_MAIN)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { toast(R.string.app_not_set) }
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
        // Consola nu are înclinare: rândurile de tilt doar ocupau loc.
        return LauncherAction.entries.filter { it !in TILT_ACTIONS }.map { action ->
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
        // Se aplică pe loc, pe toată interfața: o apăsare trece la tema următoare.
        DetailRow(
            id = "sys_theme",
            title = context.getString(R.string.set_theme),
            value = context.getString(
                when (Services.prefs.theme) {
                    ro.e92.launcher.core.Prefs.THEME_RED -> R.string.theme_red
                    ro.e92.launcher.core.Prefs.THEME_MONO -> R.string.theme_mono
                    else -> R.string.theme_orange
                }
            ),
            onActivate = {
                Services.prefs.theme = (Services.prefs.theme + 1) % THEME_COUNT
                ro.e92.launcher.ui.ThemeFilter.apply(host.activity.window.decorView, Services.prefs.theme)
                reloadDetail("sys_theme")
            }
        ),
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
            onActivate = {
                    // Pe KSW: ecranul nostru Bluetooth → Phone, nu meniul vechi al vendorului.
                    if (Services.kswBt.isInstalled) host.push(BluetoothScreen(host))
                    else AppLaunch.openBluetooth(context)
                }
        ),
        DetailRow(
            id = "sys_notif",
            title = context.getString(R.string.set_notification_access),
            value = context.getString(
                if (Services.media.hasNotificationAccess()) R.string.granted
                else R.string.not_granted
            ),
            onActivate = { openSystem(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        ),
        DetailRow(
            id = "sys_update",
            title = context.getString(R.string.set_update),
            value = updateStatus ?: context.getString(R.string.set_update_hint),
            onActivate = { checkForUpdate() }
        )
    )

    /** Ultimul rezultat al verificării, afișat pe rând până la următoarea. */
    private var updateStatus: String? = null

    private fun checkForUpdate() {
        updateStatus = context.getString(R.string.update_checking)
        reloadDetail("sys_update")
        screenScope.launch {
            val result = SelfUpdate.check(context, Services.prefs.updateUrl)
            updateStatus = context.getString(
                when (result) {
                    SelfUpdate.Result.INSTALLING -> R.string.update_installing
                    SelfUpdate.Result.UP_TO_DATE -> R.string.update_up_to_date
                    SelfUpdate.Result.NEEDS_PERMISSION -> R.string.update_permission
                    SelfUpdate.Result.NO_SERVER -> R.string.update_no_server
                    SelfUpdate.Result.FAILED -> R.string.update_failed
                }
            )
            reloadDetail("sys_update")
        }
    }

    private fun nextCanMode(current: String): String = when (current) {
        Prefs.CAN_AUTO -> Prefs.CAN_BROADCAST
        Prefs.CAN_BROADCAST -> Prefs.CAN_GPS
        Prefs.CAN_GPS -> Prefs.CAN_MOCK
        else -> Prefs.CAN_AUTO
    }

    /** Un rând care trece prin [options] (texte din resurse) la fiecare apăsare. */
    private fun cycleRow(id: String, title: Int, options: IntArray, get: () -> Int, set: (Int) -> Unit) = DetailRow(
        id = id,
        title = context.getString(title),
        value = context.getString(options[get().coerceIn(0, options.size - 1)]),
        onActivate = {
            set((get() + 1) % options.size)
            reloadDetail(id)
        }
    )

    private fun openSystem(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    companion object {
        val TILT_ACTIONS = setOf(
            LauncherAction.TILT_UP, LauncherAction.TILT_DOWN, LauncherAction.TILT_LEFT, LauncherAction.TILT_RIGHT
        )
        const val NAV_CAR = "nav_car"
        const val FACTORY_PACKAGE = "com.szchoiceway.fatset"
        const val NAV_APPS = "nav_apps"
        const val NAV_BUTTONS = "nav_buttons"
        const val NAV_LAUNCHER = "nav_launcher"
        private const val THEME_COUNT = 3
        private val POPS_STYLES = intArrayOf(
            R.string.pops_style_deep, R.string.pops_style_video, R.string.pops_style_fin, R.string.pops_style_burble, R.string.pops_style_sec
        )
        private val POPS_TIMES = intArrayOf(R.string.pops_time_short, R.string.pops_time_mid, R.string.pops_time_long)
        private val SHIFT_SOUNDS = intArrayOf(
            R.string.off, R.string.pops_shift_pops, R.string.pops_shift_soft, R.string.pops_shift_short
        )
        const val NAV_SYSTEM = "nav_system"
    }
}
