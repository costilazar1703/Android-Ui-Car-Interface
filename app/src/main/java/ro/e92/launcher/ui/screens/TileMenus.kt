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
    override val backgroundRes: Int get() = R.drawable.bg_menu_nav


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
            // CarPlay e a treia navigație din mașină (Apple Maps, Waze prin
            // telefon). Atribuirea aplicațiilor rămâne în Setări → Assigned apps.
            WheelEntry(
                id = "nav_carplay",
                icon = R.drawable.ic_menu_carplay,
                title = context.getString(R.string.menu_carplay),
                value = AppLaunch.statusLabel(context, prefs.carPlayPackage),
                onActivate = { AppLaunch.launch(context, prefs.carPlayPackage, menuTitle) }
            )
        )
    }
}

// =================================================================== MEDIA ===

class MediaMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {
    override val backgroundRes: Int get() = R.drawable.bg_menu_media


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
    override val backgroundRes: Int get() = R.drawable.bg_menu_phone


    override val menuTitle: String get() = context.getString(R.string.menu_bluetooth)

    override fun entries(): List<WheelEntry> = listOfNotNull(
        // Primul: la fiecare drum se deschide o dată aplicația KSW, play acolo,
        // apoi HOME - cât ecranul ei există în spate, muzica nu e oprită.
        // Cu motorul E92 nu mai are ce căuta aici.
        WheelEntry(
            id = "bt_open_ksw",
            icon = R.drawable.ic_menu_bluetooth,
            title = context.getString(R.string.set_open_ksw_bt),
            onActivate = { AppLaunch.openBluetooth(context) }
        ).takeUnless { Services.prefs.btOwnEngine },
        WheelEntry(
            id = "bt_pair_new",
            icon = R.drawable.ic_menu_bluetooth,
            title = context.getString(R.string.bt_connect_new),
            onActivate = {
                // Pe KSW: unitatea devine vizibilă și rămânem în meniul nostru,
                // pe ecranul care arată starea împerecherii.
                if (Services.kswBt.isInstalled) {
                    Services.kswBt.makeVisible()
                    host.push(BluetoothScreen(host))
                } else {
                    AppLaunch.openBluetooth(context)
                }
            }
        ),
        // Al doilea, la un pas de rotiță: e butonul de folosit când telefonul
        // nu vede unitatea.
        WheelEntry(
            id = "bt_reset",
            icon = R.drawable.ic_menu_bluetooth,
            title = context.getString(R.string.bt_reset),
            onActivate = { AppLaunch.resetBluetooth(context) }
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
    override val backgroundRes: Int get() = R.drawable.bg_menu


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
                id = "car_trip",
                icon = R.drawable.ic_menu_navigation,
                title = context.getString(R.string.trip_title),
                value = String.format(java.util.Locale.US, "%.0f km", Services.trip.drive.value.distanceKm),
                onActivate = { host.push(TripScreen(host)) }
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
    override val backgroundRes: Int get() = R.drawable.bg_menu


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
    override val backgroundRes: Int get() = R.drawable.bg_menu_phone


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
    override val backgroundRes: Int get() = R.drawable.bg_menu_sky


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
    override val backgroundRes: Int get() = R.drawable.bg_menu_sky


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
    override val backgroundRes: Int get() = R.drawable.bg_menu_tech


    override val menuTitle: String get() = context.getString(R.string.menu_settings)

    override fun entries(): List<WheelEntry> = listOf(
        // Primul: reparațiile unității (Bluetooth, volum, tip mașină).
        WheelEntry(
            id = "set_car",
            icon = R.drawable.ic_menu_car_info,
            title = context.getString(R.string.set_nav_car),
            onActivate = { host.push(SettingsScreen(host, SettingsScreen.NAV_CAR)) }
        ),
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

// =============================================================== TELEPHONE ===

/**
 * Pe KSW apelurile trec prin modulul unității, deci rândurile folosesc
 * comenzile lui (reapelare) și ecranul nostru Bluetooth; agenda și tastatura
 * rămân în ecranul split [TelephoneScreen].
 */
class TelephoneMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {
    override val backgroundRes: Int get() = R.drawable.bg_menu_phone

    override val menuTitle: String get() = context.getString(R.string.menu_telephone)

    override fun entries(): List<WheelEntry> = listOf(
        WheelEntry(
            id = "tel_redial",
            icon = R.drawable.ic_menu_telephone,
            title = context.getString(R.string.tel_redial),
            onActivate = { Services.kswBt.redial() }
        ),
        WheelEntry(
            id = "tel_phone",
            icon = R.drawable.ic_menu_bluetooth,
            title = context.getString(R.string.tel_connected_phone),
            value = connectedPhone(),
            onActivate = { host.push(BluetoothScreen(host)) }
        ),
        WheelEntry(
            id = "tel_more",
            icon = R.drawable.ic_menu_telephone,
            title = context.getString(R.string.tel_contacts_dialpad),
            onActivate = { host.push(TelephoneScreen(host)) }
        )
    )

    private fun connectedPhone(): String =
        Services.kswBt.state.value.phoneName.ifBlank { context.getString(R.string.tel_no_phone) }
}

// ================================================================ MESSAGES ===

class MessagesMenuScreen(host: ScreenHost) : WheelMenuScreen(host) {
    override val backgroundRes: Int get() = R.drawable.bg_menu_phone

    override val menuTitle: String get() = context.getString(R.string.menu_messages)

    override fun entries(): List<WheelEntry> = listOf(
        WheelEntry(
            id = "msg_inbox",
            icon = R.drawable.ic_menu_messages,
            title = context.getString(R.string.msg_inbox),
            onActivate = { host.push(MessagesScreen(host)) }
        ),
        WheelEntry(
            id = "msg_app",
            icon = R.drawable.ic_menu_messages,
            title = context.getString(R.string.msg_open_app),
            onActivate = {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_APP_MESSAGING)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    runCatching { context.startActivity(intent) }
                } else {
                    host.push(AppDrawerScreen(host))
                }
            }
        ),
        WheelEntry(
            id = "msg_phone",
            icon = R.drawable.ic_menu_bluetooth,
            title = context.getString(R.string.tel_connected_phone),
            value = Services.kswBt.state.value.phoneName
                .ifBlank { context.getString(R.string.tel_no_phone) },
            onActivate = { host.push(BluetoothScreen(host)) }
        )
    )
}
