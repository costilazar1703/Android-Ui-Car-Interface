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
 * Toate arată la fel (rotița în stânga, rândurile în dreapta) pentru că toate
 * extind [WheelMenuScreen]; diferă doar rândurile. Stau într-un singur fișier
 * fiindcă fiecare e de 15-30 de linii și le citești mai ușor una lângă alta
 * decât împrăștiate în zece fișiere.
 *
 * Regula comună: un rând care LANSEAZĂ ceva își arată în coloana din dreapta
 * starea reală — numele aplicației, „not bound" sau „not installed". Rândul nu
 * trebuie apăsat ca să afli că nu e configurat.
 */

// ============================================================== NAVIGATION ===

/**
 * Meniul cerut explicit: alegi între cele două aplicații de navigație, iar
 * atribuirea lor se face tot de aici — fără drum prin Setări.
 */
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
                onActivate = {
                    AppLaunch.launch(context, prefs.navPackage, menuTitle)
                }
            ),
            WheelEntry(
                id = "nav_maps",
                icon = R.drawable.ic_menu_navigation,
                title = context.getString(R.string.nav_pick_maps),
                value = AppLaunch.statusLabel(context, prefs.mapsPackage),
                onActivate = {
                    AppLaunch.launch(context, prefs.mapsPackage, menuTitle)
                }
            ),
            WheelEntry(
                id = "nav_assign_primary",
                icon = R.drawable.ic_menu_apps,
                title = context.getString(R.string.nav_assign_primary),
                onActivate = {
                    host.push(AppDrawerScreen(host) { entry ->
                        prefs.navPackage = entry.packageName
                        reload("nav_assign_primary")
                    })
                },
                onOption = {
                    prefs.navPackage = ""
                    reload("nav_assign_primary")
                }
            ),
            WheelEntry(
                id = "nav_assign_maps",
                icon = R.drawable.ic_menu_apps,
                title = context.getString(R.string.nav_assign_maps),
                onActivate = {
                    host.push(AppDrawerScreen(host) { entry ->
                        prefs.mapsPackage = entry.packageName
                        reload("nav_assign_maps")
                    })
                },
                onOption = {
                    prefs.mapsPackage = ""
                    reload("nav_assign_maps")
                }
            )
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
            ),
            WheelEntry(
                id = "media_apps",
                icon = R.drawable.ic_menu_apps,
                title = context.getString(R.string.menu_apps),
                onActivate = { host.push(AppDrawerScreen(host)) }
            )
        )
    }
}

// =============================================================== BLUETOOTH ===

/**
 * Meniul Bluetooth, cerut explicit cu rotiță.
 *
 * Împerecherea NU se face aici. Pe unitățile astea stack-ul Bluetooth e al
 * vendorului, iar dialogul lui de pairing e singurul care chiar leagă telefonul;
 * dacă l-am dubla, am avea două stări care se contrazic. Rândurile de pairing
 * duc, deci, în ecranul de sistem — dar din meniul nostru, nu de undeva din
 * adâncul Android-ului.
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
        ),
        WheelEntry(
            id = "bt_phone",
            icon = R.drawable.ic_menu_telephone,
            title = context.getString(R.string.menu_telephone),
            onActivate = { host.push(TelephoneScreen(host)) }
        ),
        WheelEntry(
            id = "bt_settings",
            icon = R.drawable.ic_menu_settings,
            title = context.getString(R.string.bt_settings),
            onActivate = { AppLaunch.openSystem(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
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
                icon = R.drawable.ic_tile_e92,
                title = context.getString(R.string.car_vendor_app),
                value = AppLaunch.statusLabel(context, prefs.carInfoPackage),
                onActivate = { AppLaunch.launch(context, prefs.carInfoPackage, menuTitle) }
            ),
            WheelEntry(
                id = "car_assign",
                icon = R.drawable.ic_menu_apps,
                title = context.getString(R.string.car_assign_app),
                onActivate = {
                    host.push(AppDrawerScreen(host) { entry ->
                        prefs.carInfoPackage = entry.packageName
                        reload("car_assign")
                    })
                },
                onOption = {
                    prefs.carInfoPackage = ""
                    reload("car_assign")
                }
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
            WheelEntry(
                id = "dash_assign",
                icon = R.drawable.ic_menu_apps,
                title = context.getString(R.string.car_assign_app),
                onActivate = {
                    host.push(AppDrawerScreen(host) { entry ->
                        prefs.dashboardPackage = entry.packageName
                        reload("dash_assign")
                    })
                },
                onOption = {
                    prefs.dashboardPackage = ""
                    reload("dash_assign")
                }
            )
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
                id = "cp_assign",
                icon = R.drawable.ic_menu_apps,
                title = context.getString(R.string.car_assign_app),
                onActivate = {
                    host.push(AppDrawerScreen(host) { entry ->
                        prefs.carPlayPackage = entry.packageName
                        reload("cp_assign")
                    })
                },
                onOption = {
                    prefs.carPlayPackage = ""
                    reload("cp_assign")
                }
            ),
            WheelEntry(
                id = "cp_bt",
                icon = R.drawable.ic_menu_bluetooth,
                title = context.getString(R.string.menu_bluetooth),
                onActivate = { host.push(BluetoothMenuScreen(host)) }
            )
        )
    }
}

// ========================================================= CONNECTEDDRIVE ===

class ConnectedDriveMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_connecteddrive)

    override fun entries(): List<WheelEntry> {
        val prefs = Services.prefs
        return listOf(
            WheelEntry(
                id = "cd_portal",
                icon = R.drawable.ic_menu_connecteddrive,
                title = context.getString(R.string.cd_portal),
                value = AppLaunch.statusLabel(context, prefs.browserPackage),
                onActivate = { host.dispatchConnectedDrive() }
            ),
            WheelEntry(
                id = "cd_browser",
                icon = R.drawable.ic_menu_apps,
                title = context.getString(R.string.cd_choose_browser),
                onActivate = {
                    host.push(AppDrawerScreen(host) { entry ->
                        prefs.browserPackage = entry.packageName
                        reload("cd_browser")
                    })
                }
            ),
            WheelEntry(
                id = "cd_weather",
                icon = R.drawable.ic_menu_weather,
                title = context.getString(R.string.menu_weather),
                onActivate = { host.push(WeatherMenuScreen(host)) }
            )
        )
    }
}

// ================================================================= WEATHER ===

/**
 * Cerut explicit ca „atribuire directă": rândul de sus lansează aplicația de
 * vreme, cel de jos o alege. Atât. Vremea nu se citește din niciun API propriu —
 * ar cere cheie și cont, iar aplicația pe care o ai deja pe tabletă o face mai
 * bine.
 */
class WeatherMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {

    override val menuTitle: String get() = context.getString(R.string.menu_weather)

    override fun entries(): List<WheelEntry> {
        val prefs = Services.prefs
        return listOf(
            WheelEntry(
                id = "weather_open",
                icon = R.drawable.ic_menu_weather,
                title = context.getString(R.string.weather_open),
                value = AppLaunch.statusLabel(context, prefs.weatherPackage),
                onActivate = { AppLaunch.launch(context, prefs.weatherPackage, menuTitle) }
            ),
            WheelEntry(
                id = "weather_assign",
                icon = R.drawable.ic_menu_apps,
                title = context.getString(R.string.weather_assign),
                onActivate = {
                    host.push(AppDrawerScreen(host) { entry ->
                        prefs.weatherPackage = entry.packageName
                        reload("weather_assign")
                    })
                },
                onOption = {
                    prefs.weatherPackage = ""
                    reload("weather_assign")
                }
            )
        )
    }
}
