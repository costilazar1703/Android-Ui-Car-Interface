package ro.e92.launcher.ui.screens

import android.provider.Settings
import ro.e92.launcher.R
import ro.e92.launcher.core.AppLaunch
import ro.e92.launcher.core.Services
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.ui.ScreenHost

/**
 * Meniurile de nivel 2 — câte unul pentru fiecare dală din grila ID6.
 *
 * **Exact trei rânduri fiecare.** Nu e o limită tehnică, e regula de compoziție
 * a întregii interfețe: oricare dală ai apăsa, ecranul care se deschide arată la
 * fel. Înainte un meniu avea două rânduri și altul cinci, iar interfața părea că
 * se rearanjează singură la fiecare apăsare.
 *
 * Ce nu încape în trei coboară un nivel. Al treilea rând e de obicei poarta spre
 * restul: un ecran split cu categorii, sau Setările deschise DIRECT pe secțiunea
 * potrivită (vezi [SettingsScreen.initialNavId]) — nu pe prima, urmată de o
 * căutare.
 *
 * Toate arată la fel și pentru că toate extind [WheelMenuScreen]; diferă doar
 * rândurile. Stau într-un singur fișier fiindcă fiecare e de 15-25 de linii și
 * se citesc mai ușor una lângă alta decât împrăștiate în zece fișiere.
 *
 * Regula comună: un rând care LANSEAZĂ ceva își arată în coloana din dreapta
 * starea reală — numele aplicației, „not bound" sau „not installed". Nu trebuie
 * apăsat ca să afli că nu e configurat.
 */

// ============================================================== NAVIGATION ===

class NavigationMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_navigation)

    override fun entries(): List<WheelEntry> {
        val prefs = Services.prefs
        return listOf(
            WheelEntry(
                id = "nav_primary",
                icon = R.drawable.ic_menu_navigation,
                title = context.getString(R.string.nav_pick_waze),
                value = AppLaunch.statusLabel(context, prefs.navPackage),
                onActivate = { AppLaunch.launch(context, prefs.navPackage, menuTitle) }
            ),
            WheelEntry(
                id = "nav_maps",
                icon = R.drawable.ic_menu_navigation,
                title = context.getString(R.string.nav_pick_maps),
                value = AppLaunch.statusLabel(context, prefs.mapsPackage),
                onActivate = { AppLaunch.launch(context, prefs.mapsPackage, menuTitle) }
            ),
            // Atribuirea celor două aplicații se face în Setări → Assigned apps,
            // unde stau oricum toate celelalte. Aici ar fi fost două rânduri în
            // plus care fac același lucru.
            settingsEntry(host, context, SettingsScreen.NAV_APPS)
        )
    }
}

// =================================================================== MEDIA ===

class MediaMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_media)

    override fun entries(): List<WheelEntry> {
        val media = Services.media.snapshot.value
        return listOf(
            WheelEntry(
                id = "media_now",
                icon = R.drawable.ic_menu_media,
                title = context.getString(R.string.media_now_playing),
                value = media.title ?: context.getString(R.string.media_no_session),
                onActivate = { host.push(MediaScreen(host)) }
            ),
            WheelEntry(
                id = "media_bt",
                icon = R.drawable.ic_menu_bluetooth,
                title = context.getString(R.string.menu_bluetooth),
                onActivate = { host.push(BluetoothMenuScreen(host)) }
            ),
            WheelEntry(
                id = "media_radio",
                icon = R.drawable.ic_menu_media,
                title = context.getString(R.string.media_oem_radio),
                onActivate = { host.dispatch(LauncherAction.RADIO) }
            )
        )
    }
}

// =============================================================== BLUETOOTH ===

/**
 * Împerecherea NU se face aici. Pe unitățile astea stack-ul Bluetooth e al
 * vendorului, iar dialogul lui de pairing e singurul care chiar leagă telefonul;
 * dacă l-am dubla, am avea două stări care se contrazic. Rândul de pairing duce,
 * deci, în ecranul de sistem — dar din meniul nostru, nu de undeva din adâncul
 * Android-ului.
 *
 * „Paired devices" deschide ecranul split, care ține mai departe și setările de
 * Bluetooth și starea accesului la notificări.
 */
class BluetoothMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_bluetooth)

    override fun entries(): List<WheelEntry> = listOf(
        WheelEntry(
            id = "bt_pair_new",
            icon = R.drawable.ic_menu_bluetooth,
            title = context.getString(R.string.bt_connect_new),
            onActivate = { AppLaunch.openSystem(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
        ),
        WheelEntry(
            id = "bt_paired",
            icon = R.drawable.ic_menu_bluetooth,
            title = context.getString(R.string.bt_paired),
            onActivate = { host.push(BluetoothScreen(host)) }
        ),
        WheelEntry(
            id = "bt_music",
            icon = R.drawable.ic_menu_media,
            title = context.getString(R.string.bt_audio),
            value = Services.media.snapshot.value.title.orEmpty(),
            onActivate = { host.push(MediaScreen(host)) }
        )
    )
}

// ================================================================ CAR INFO ===

class CarInfoMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_car_info)

    override fun entries(): List<WheelEntry> {
        val prefs = Services.prefs
        return listOf(
            WheelEntry(
                id = "car_gauges",
                icon = R.drawable.ic_menu_dashboard,
                title = context.getString(R.string.car_live_data),
                onActivate = { host.push(DashboardScreen(host)) }
            ),
            WheelEntry(
                id = "car_app",
                icon = R.drawable.ic_menu_car_info,
                title = context.getString(R.string.car_vendor_app),
                value = AppLaunch.statusLabel(context, prefs.carInfoPackage),
                onActivate = { AppLaunch.launch(context, prefs.carInfoPackage, menuTitle) }
            ),
            WheelEntry(
                id = "car_diag",
                icon = R.drawable.ic_menu_settings,
                title = context.getString(R.string.set_diagnostics),
                onActivate = { host.push(DiagnosticsScreen(host)) }
            )
        )
    }
}

// =============================================================== DASHBOARD ===

class DashboardMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_dashboard)

    override fun entries(): List<WheelEntry> {
        val prefs = Services.prefs
        return listOf(
            WheelEntry(
                id = "dash_builtin",
                icon = R.drawable.ic_menu_dashboard,
                title = context.getString(R.string.set_dashboard_builtin),
                onActivate = { host.push(DashboardScreen(host)) }
            ),
            WheelEntry(
                id = "dash_app",
                icon = R.drawable.ic_menu_apps,
                title = context.getString(R.string.set_dashboard_pkg),
                value = AppLaunch.statusLabel(context, prefs.dashboardPackage),
                onActivate = { AppLaunch.launch(context, prefs.dashboardPackage, menuTitle) }
            ),
            settingsEntry(host, context, SettingsScreen.NAV_APPS)
        )
    }
}

// ================================================================= CARPLAY ===

class CarPlayMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_carplay)

    override fun entries(): List<WheelEntry> {
        val prefs = Services.prefs
        return listOf(
            WheelEntry(
                id = "cp_launch",
                icon = R.drawable.ic_menu_carplay,
                title = context.getString(R.string.cp_launch),
                value = AppLaunch.statusLabel(context, prefs.carPlayPackage),
                onActivate = { AppLaunch.launch(context, prefs.carPlayPackage, menuTitle) }
            ),
            WheelEntry(
                id = "cp_bt",
                icon = R.drawable.ic_menu_bluetooth,
                title = context.getString(R.string.menu_bluetooth),
                onActivate = { host.push(BluetoothMenuScreen(host)) }
            ),
            settingsEntry(host, context, SettingsScreen.NAV_APPS)
        )
    }
}

// ========================================================= CONNECTEDDRIVE ===

class ConnectedDriveMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_connecteddrive)

    override fun entries(): List<WheelEntry> = listOf(
        WheelEntry(
            id = "cd_portal",
            icon = R.drawable.ic_menu_connecteddrive,
            title = context.getString(R.string.cd_portal),
            value = AppLaunch.statusLabel(context, Services.prefs.browserPackage),
            onActivate = { host.dispatchConnectedDrive() }
        ),
        WheelEntry(
            id = "cd_weather",
            icon = R.drawable.ic_menu_weather,
            title = context.getString(R.string.menu_weather),
            onActivate = { host.push(WeatherMenuScreen(host)) }
        ),
        settingsEntry(host, context, SettingsScreen.NAV_APPS)
    )
}

// ================================================================= WEATHER ===

/**
 * Vremea nu se citește din niciun API propriu — ar cere cheie și cont, iar
 * aplicația de pe tabletă o face mai bine. Meniul doar o lansează și o atribuie.
 */
class WeatherMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_weather)

    override fun entries(): List<WheelEntry> = listOf(
        WheelEntry(
            id = "weather_open",
            icon = R.drawable.ic_menu_weather,
            title = context.getString(R.string.weather_open),
            value = AppLaunch.statusLabel(context, Services.prefs.weatherPackage),
            onActivate = {
                AppLaunch.launch(context, Services.prefs.weatherPackage, menuTitle)
            }
        ),
        WheelEntry(
            id = "weather_cd",
            icon = R.drawable.ic_menu_connecteddrive,
            title = context.getString(R.string.menu_connecteddrive),
            onActivate = { host.dispatchConnectedDrive() }
        ),
        settingsEntry(host, context, SettingsScreen.NAV_APPS)
    )
}

// ================================================================ SETTINGS ===

/**
 * Setările primesc și ele rotița.
 *
 * Erau singurul meniu care se deschidea direct într-un ecran split, și se vedea:
 * apăsai o dală identică cu celelalte și primeai altă interfață. Acum au aceeași
 * poartă ca tot restul — trei categorii — iar fiecare duce în ecranul split
 * deschis DIRECT pe secțiunea ei.
 */
class SettingsMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_settings)

    override fun entries(): List<WheelEntry> = listOf(
        WheelEntry(
            id = "set_apps",
            icon = R.drawable.ic_menu_apps,
            title = context.getString(R.string.set_nav_assigned),
            onActivate = { host.push(SettingsScreen(host, SettingsScreen.NAV_APPS)) }
        ),
        WheelEntry(
            id = "set_buttons",
            icon = R.drawable.ic_menu_car_info,
            title = context.getString(R.string.set_nav_buttons),
            onActivate = { host.push(SettingsScreen(host, SettingsScreen.NAV_BUTTONS)) }
        ),
        WheelEntry(
            id = "set_system",
            icon = R.drawable.ic_menu_settings,
            title = context.getString(R.string.set_nav_system),
            onActivate = { host.push(SettingsScreen(host, SettingsScreen.NAV_SYSTEM)) }
        )
    )
}

// ------------------------------------------------------------------ comun ---

/**
 * Al treilea rând al majorității meniurilor: poarta spre Setări, deschise fix pe
 * categoria cerută. Definit o singură dată pentru că altfel ar fi fost copiat în
 * cinci locuri, cu cinci ocazii să ajungă să arate spre altceva.
 */
private fun settingsEntry(
    host: ScreenHost,
    context: android.content.Context,
    navId: String
) = WheelEntry(
    id = "row_settings",
    icon = R.drawable.ic_menu_settings,
    title = context.getString(R.string.menu_settings),
    onActivate = { host.push(SettingsScreen(host, navId)) }
)
