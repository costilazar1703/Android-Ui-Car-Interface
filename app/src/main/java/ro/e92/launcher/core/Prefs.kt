package ro.e92.launcher.core

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences direct, nu DataStore: setările sunt câteva chei citite o dată
 * la pornire, iar DataStore ar adăuga o dependență și un strat de coroutine
 * pentru zero câștig aici.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Aplicația lansată de butonul hard NAV și de meniul Navigation. */
    var navPackage: String
        get() = sp.getString(KEY_NAV_PKG, DEFAULT_NAV_PKG) ?: DEFAULT_NAV_PKG
        set(value) = sp.edit().putString(KEY_NAV_PKG, value).apply()

    // ------------------------------------------------------------------------
    // Aplicații atribuite meniurilor care lansează software terț.
    //
    // Se stochează package name-ul, nu componenta: aplicațiile astea se
    // actualizează des, iar numele Activity-ului de lansare li se schimbă.
    // Gol = neatribuit; meniul spune asta explicit în loc să eșueze mut.
    // ------------------------------------------------------------------------

    /** Meniul „Apple CarPlay" — de obicei o aplicație de tip Autoplay/CarLink. */
    var carPlayPackage: String
        get() = sp.getString(KEY_CARPLAY_PKG, "") ?: ""
        set(value) = sp.edit().putString(KEY_CARPLAY_PKG, value).apply()

    /** Meniul „Dashboard". Dacă e gol, se deschid cadranele proprii ale launcher-ului. */
    var dashboardPackage: String
        get() = sp.getString(KEY_DASHBOARD_PKG, "") ?: ""
        set(value) = sp.edit().putString(KEY_DASHBOARD_PKG, value).apply()

    /** Meniul „Car Info" — aplicația de diagnoză/info a unității. */
    var carInfoPackage: String
        get() = sp.getString(KEY_CARINFO_PKG, "") ?: ""
        set(value) = sp.edit().putString(KEY_CARINFO_PKG, value).apply()

    /** Meniul "Weather" - aplicatia de vreme atribuita de utilizator. */
    var weatherPackage: String
        get() = sp.getString(KEY_WEATHER_PKG, "") ?: ""
        set(value) = sp.edit().putString(KEY_WEATHER_PKG, value).apply()

    /**
     * A doua aplicatie de harti, distincta de [navPackage]: meniul Navigation
     * ofera doua destinatii (de obicei Waze si Google Maps), iar utilizatorul
     * le alege separat.
     */
    var mapsPackage: String
        get() = sp.getString(KEY_MAPS_PKG, DEFAULT_MAPS_PKG) ?: DEFAULT_MAPS_PKG
        set(value) = sp.edit().putString(KEY_MAPS_PKG, value).apply()

    /** Browser-ul folosit de meniul ConnectedDrive și de opțiunea „Chrome". */
    var browserPackage: String
        get() = sp.getString(KEY_BROWSER_PKG, DEFAULT_BROWSER_PKG) ?: DEFAULT_BROWSER_PKG
        set(value) = sp.edit().putString(KEY_BROWSER_PKG, value).apply()

    var connectedDriveUrl: String
        get() = sp.getString(KEY_CD_URL, DEFAULT_CD_URL) ?: DEFAULT_CD_URL
        set(value) = sp.edit().putString(KEY_CD_URL, value).apply()

    /** Ultima pagină de meniu afișată — se restaurează la revenirea pe home. */
    var lastMenuPage: Int
        get() = sp.getInt(KEY_LAST_PAGE, 0)
        set(value) = sp.edit().putInt(KEY_LAST_PAGE, value).apply()

    /**
     * Numele din salutul de pornire: „Welcome back, Mr. <nume>". Gol = doar
     * „Welcome back".
     */
    var greetingName: String
        get() = sp.getString(KEY_GREETING_NAME, DEFAULT_GREETING_NAME) ?: DEFAULT_GREETING_NAME
        set(value) = sp.edit().putString(KEY_GREETING_NAME, value.trim()).apply()

    /** De unde ia butonul „Check for update" APK-ul: laptopul, pe hotspot. */
    var updateUrl: String
        get() = sp.getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
        set(value) = sp.edit().putString(KEY_UPDATE_URL, value.trim()).apply()

    /**
     * Tipul modulului Bluetooth pe care îl ține launcher-ul (Sys_BTDeviceType),
     * ales din Bluetooth → Phone. Implicit BC6; BC5 și Feiyitong rămân de
     * încercat dacă muzica tot cade.
     */
    var btModuleType: String
        get() = sp.getString(KEY_BT_MODULE_TYPE, KswSettings.BT_TYPE_BC6) ?: KswSettings.BT_TYPE_BC6
        set(value) = sp.edit().putString(KEY_BT_MODULE_TYPE, value).apply()

    /** Cine vorbește cu stiva Bluetooth: bTSuite (implicit) sau motorul nostru. */
    var btEngine: String
        get() = sp.getString(KEY_BT_ENGINE, BT_ENGINE_KSW) ?: BT_ENGINE_KSW
        set(value) = sp.edit().putString(KEY_BT_ENGINE, value).apply()

    /** Motorul nostru: launcher-ul nu mai duce nicăieri spre aplicația KSW. */
    val btOwnEngine: Boolean
        get() = btEngine == BT_ENGINE_E92

    /**
     * Volumul de bază al unității (volumul principal KSW, 0-40). Volumul real
     * îl dai din butonul radioului; la pornire unitatea e adusă cel puțin aici.
     */
    var unitVolume: Int
        get() = sp.getInt(KEY_UNIT_VOLUME, DEFAULT_UNIT_VOLUME)
        set(value) = sp.edit().putInt(KEY_UNIT_VOLUME, value).apply()

    /** Launcher-ul ține oprită „reluarea ultimei surse" a unității la pornire. */
    var blockKswReopen: Boolean
        get() = sp.getBoolean(KEY_BLOCK_KSW_REOPEN, true)
        set(value) = sp.edit().putBoolean(KEY_BLOCK_KSW_REOPEN, value).apply()

    /** La pornire, launcher-ul revine în față dacă îl acoperă ceva neatins de șofer. */
    var startGuard: Boolean
        get() = sp.getBoolean(KEY_START_GUARD, true)
        set(value) = sp.edit().putBoolean(KEY_START_GUARD, value).apply()

    /** Numele sub care telefonul vede unitatea pe Bluetooth. */
    var btUnitName: String
        get() = sp.getString(KEY_BT_NAME, DEFAULT_BT_NAME) ?: DEFAULT_BT_NAME
        set(value) = sp.edit().putString(KEY_BT_NAME, value.trim()).apply()

    /** "auto" | "mock" | "broadcast" | "gps" */
    var canMode: String
        get() = sp.getString(KEY_CAN_MODE, CAN_AUTO) ?: CAN_AUTO
        set(value) = sp.edit().putString(KEY_CAN_MODE, value).apply()

    /** Dezactivabil dacă tranzițiile costă frame-uri pe unitatea reală. */
    var animationsEnabled: Boolean
        get() = sp.getBoolean(KEY_ANIM, true)
        set(value) = sp.edit().putBoolean(KEY_ANIM, value).apply()

    /** Serializat ca "keycode:ACTION;keycode:ACTION". Gol = folosește default-urile. */
    var keymapRaw: String
        get() = sp.getString(KEY_KEYMAP, "") ?: ""
        set(value) = sp.edit().putString(KEY_KEYMAP, value).apply()

    /** Ordinea cardurilor de pe home, ca listă de id-uri separate prin virgulă. */
    var homeOrder: String
        get() = sp.getString(KEY_HOME_ORDER, "") ?: ""
        set(value) = sp.edit().putString(KEY_HOME_ORDER, value).apply()

    fun clearKeymap() = sp.edit().remove(KEY_KEYMAP).apply()

    // ------------------------------------------------------------------------
    // Backup: SettingsBackup lucreaza pe toate cheile, nu pe proprietatile de
    // mai sus, ca o cheie noua adaugata maine sa intre in backup fara sa-l
    // modifice cineva.
    // ------------------------------------------------------------------------

    fun snapshot(): Map<String, *> = sp.all.filterKeys { it !in VOLATILE_KEYS }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    companion object {
        /** Numele fisierului, folosit si de restaurarea care ruleaza inainte de Services. */
        const val FILE_NAME = "e92_launcher"

        /**
         * Chei care se schimba la fiecare apasare si nu spun nimic despre cum ai
         * configurat aplicatia. Fara exceptia asta, deschiderea oricarei dale ar
         * rescrie backup-ul pe disc.
         */
        val VOLATILE_KEYS = setOf("last_menu_page")

        const val CAN_AUTO = "auto"
        const val CAN_MOCK = "mock"
        const val CAN_BROADCAST = "broadcast"
        const val CAN_GPS = "gps"

        const val DEFAULT_NAV_PKG = "com.waze"
        const val DEFAULT_GREETING_NAME = "Lazar"
        const val DEFAULT_BT_NAME = "Lazar's BMW"
        /** Laptopul pe hotspot-ul iPhone (rețeaua 172.20.10.0/28) - vezi scripts/e92.ps1. */
        const val DEFAULT_UPDATE_URL = "http://172.20.10.8:8000/e92.apk"
        const val DEFAULT_BROWSER_PKG = "com.android.chrome"
        const val DEFAULT_MAPS_PKG = "com.google.android.apps.maps"
        const val DEFAULT_CD_URL = "https://www.bmw-connecteddrive.com"

        private const val KEY_NAV_PKG = "nav_package"
        private const val KEY_GREETING_NAME = "greeting_name"
        private const val KEY_BT_NAME = "bt_unit_name"
        private const val KEY_BT_MODULE_TYPE = "bt_module_type"
        private const val KEY_BT_ENGINE = "bt_engine"
        const val BT_ENGINE_KSW = "ksw"
        const val BT_ENGINE_E92 = "e92"
        private const val KEY_UNIT_VOLUME = "unit_volume"
        private const val KEY_BLOCK_KSW_REOPEN = "block_ksw_reopen"
        private const val KEY_START_GUARD = "start_guard"
        const val DEFAULT_UNIT_VOLUME = 30
        private const val KEY_UPDATE_URL = "update_url"
        private const val KEY_CARPLAY_PKG = "carplay_package"
        private const val KEY_DASHBOARD_PKG = "dashboard_package"
        private const val KEY_CARINFO_PKG = "carinfo_package"
        private const val KEY_BROWSER_PKG = "browser_package"
        private const val KEY_WEATHER_PKG = "weather_package"
        private const val KEY_MAPS_PKG = "maps_package"
        private const val KEY_CD_URL = "connecteddrive_url"
        private const val KEY_LAST_PAGE = "last_menu_page"
        private const val KEY_CAN_MODE = "can_mode"
        private const val KEY_ANIM = "animations"
        private const val KEY_KEYMAP = "keymap"
        private const val KEY_HOME_ORDER = "home_order"
    }
}
