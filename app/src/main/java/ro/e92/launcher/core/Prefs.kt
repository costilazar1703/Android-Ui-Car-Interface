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
        context.getSharedPreferences("e92_launcher", Context.MODE_PRIVATE)

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

    companion object {
        const val CAN_AUTO = "auto"
        const val CAN_MOCK = "mock"
        const val CAN_BROADCAST = "broadcast"
        const val CAN_GPS = "gps"

        const val DEFAULT_NAV_PKG = "com.waze"
        const val DEFAULT_BROWSER_PKG = "com.android.chrome"
        const val DEFAULT_MAPS_PKG = "com.google.android.apps.maps"
        const val DEFAULT_CD_URL = "https://www.bmw-connecteddrive.com"

        private const val KEY_NAV_PKG = "nav_package"
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
