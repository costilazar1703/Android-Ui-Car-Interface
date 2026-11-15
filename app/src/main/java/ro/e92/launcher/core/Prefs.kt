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

    /**
     * Piesa care cântă: stiva trimite numele doar când se schimbă melodia, deci
     * după o repornire a launcher-ului îl luăm de aici până la piesa următoare.
     */
    var lastSongTitle: String
        get() = sp.getString("last_song_title", "").orEmpty()
        set(value) = sp.edit().putString("last_song_title", value).apply()

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

    /** Stiva E92 anunță doar muzică și controale, fără apeluri și agendă. */
    var btMusicOnly: Boolean
        get() = sp.getBoolean(KEY_BT_MUSIC_ONLY, true)
        set(value) = sp.edit().putBoolean(KEY_BT_MUSIC_ONLY, value).apply()

    /**
     * Scena Drift Mode. Butonul DSC vine doar prin cadrele CAN, deci cere cadrele pornite
     * tot timpul cât merge motorul, nu doar în marșarier. Oprită implicit: costă.
     */
    /** Scoasă din setări 2026-11-15: mereu oprită, chiar dacă era pornită înainte. */
    var driftMode: Boolean
        get() = false
        set(value) = sp.edit().putBoolean(KEY_DRIFT_MODE, value).apply()

    /** Ultimul profil trimis stivei, ca să nu o repornim în buclă dacă nu-l ține minte. */
    var btProfileTried: Int
        get() = sp.getInt(KEY_BT_PROFILE_TRIED, 0)
        set(value) = sp.edit().putInt(KEY_BT_PROFILE_TRIED, value).apply()

    /** Marșarier: scena noastră în locul ferestrei KSW. */
    var reverseScene: Boolean
        get() = sp.getBoolean(KEY_REVERSE_SCENE, false)
        set(value) = sp.edit().putBoolean(KEY_REVERSE_SCENE, value).apply()

    /**
     * Ce îi spunem MCU-ului despre marșarier cât e pornită scena noastră: aceleași comenzi 0x6A
     * pe care le trimit meniurile KSW. Tipul camerei (0B: 0 nimic, 1 aftermarket, 2 360,
     * 3 originală), liniile de ghidaj (16) și radarul (17) desenate de MCU, și „original car
     * video display" (13), trimis doar dacă a fost setat (-1 = neatins). Se pot schimba din
     * DiagServer /mcu, pentru probe în mașină fără versiune nouă; rămân salvate.
     */
    var mcuCameraType: Int
        get() = sp.getInt(KEY_MCU_CAMERA, 0).coerceIn(0, 3)
        set(value) = sp.edit().putInt(KEY_MCU_CAMERA, value.coerceIn(0, 3)).apply()

    var mcuTrack: Int
        get() = sp.getInt(KEY_MCU_TRACK, 0).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_MCU_TRACK, value.coerceIn(0, 1)).apply()

    var mcuRadar: Int
        get() = sp.getInt(KEY_MCU_RADAR, 0).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_MCU_RADAR, value.coerceIn(0, 1)).apply()

    var mcuOrigVideo: Int
        get() = sp.getInt(KEY_MCU_ORIG_VIDEO, -1).coerceIn(-1, 1)
        set(value) = sp.edit().putInt(KEY_MCU_ORIG_VIDEO, value.coerceIn(-1, 1)).apply()

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
    /** Aplicația din care cântă telefonul (Bluetooth nu o spune): 0 notă, 1 Spotify, 2 YT Music, 3 YouTube. */
    var phoneMusicApp: Int
        get() = sp.getInt(KEY_PHONE_MUSIC_APP, 0)
        set(value) = sp.edit().putInt(KEY_PHONE_MUSIC_APP, value).apply()

    /** Bipurile senzorilor în scena de marșarier. Se aud doar cu radioul mașinii pornit. */
    var parkBeeps: Boolean
        get() = sp.getBoolean(KEY_PARK_BEEPS, true)
        set(value) = sp.edit().putBoolean(KEY_PARK_BEEPS, value).apply()

    /** Volumul unității de dinainte de marșarier, cât e scăzut; -1 = nu e scăzut. */
    var duckedFromVolume: Int
        get() = sp.getInt(KEY_DUCKED_FROM, -1)
        set(value) = sp.edit().putInt(KEY_DUCKED_FROM, value).apply()

    // ------------------------------------------------------------ Pops & Bangs
    /** Oprit implicit. */
    /** Scoase din setări 2026-11-15: mereu oprite, chiar dacă erau pornite înainte. */
    var popsEnabled: Boolean
        get() = false
        set(value) = sp.edit().putBoolean(KEY_POPS_ON, value).apply()

    /** 0 Video (din clipul ales), 1 Fin, 2 Burble, 3 Sec. */
    var popsStyle: Int
        get() = sp.getInt(KEY_POPS_STYLE, 0)
        set(value) = sp.edit().putInt(KEY_POPS_STYLE, value).apply()

    /** Volum fix al pops-urilor, 1..10 (10 % ... 100 %), independent de turație și de volumul media. */
    var popsVolume: Int
        get() = sp.getInt(KEY_POPS_VOLUME, 5)
        set(value) = sp.edit().putInt(KEY_POPS_VOLUME, value).apply()

    /** 0 scurtă, 1 medie, 2 lungă. */
    var popsDuration: Int
        get() = sp.getInt(KEY_POPS_TIME, 1)
        set(value) = sp.edit().putInt(KEY_POPS_TIME, value).apply()

    /** Turația peste care încep pops-urile, în rpm. */
    var popsRpm: Int
        get() = sp.getInt(KEY_POPS_RPM, 2500)
        set(value) = sp.edit().putInt(KEY_POPS_RPM, value).apply()

    /** Sunetul la schimbarea în sus: 0 oprit, 1 din pocnituri, 2 Moale, 3 Scurt. Doar cu Pops & Bangs pornit. */
    var shiftSound: Int
        get() = sp.getInt(KEY_SHIFT_SOUND, 1)
        set(value) = sp.edit().putInt(KEY_SHIFT_SOUND, value).apply()

    var theme: Int
        get() = sp.getInt(KEY_THEME, THEME_ORANGE)
        set(value) = sp.edit().putInt(KEY_THEME, value).apply()

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
        private const val KEY_BT_MUSIC_ONLY = "bt_music_only"
        // Cheie nouă: încercările vechi au trimis `PROFILE=`, pe care stiva îl refuză.
        private const val KEY_BT_PROFILE_TRIED = "bt_profile_tried_v2"
        private const val KEY_DRIFT_MODE = "drift_mode"
        private const val KEY_THEME = "ui_theme"
        private const val KEY_PHONE_MUSIC_APP = "phone_music_app"
        private const val KEY_PARK_BEEPS = "park_beeps"
        private const val KEY_DUCKED_FROM = "ducked_from_volume"
        private const val KEY_POPS_ON = "pops_on"
        private const val KEY_POPS_STYLE = "pops_style"
        private const val KEY_POPS_VOLUME = "pops_volume"
        private const val KEY_POPS_TIME = "pops_time"
        /** Cheie nouă la pragul de 2500 (2026-11-09): vechea valoare salvată (3000-4500) nu mai contează. */
        private const val KEY_POPS_RPM = "pops_rpm_2500"
        private const val KEY_SHIFT_SOUND = "shift_sound"

        /** Tema interfeței: portocaliu (originalul), roșu, alb-negru. */
        const val THEME_ORANGE = 0
        const val THEME_RED = 1
        const val THEME_MONO = 2
        private const val KEY_REVERSE_SCENE = "reverse_scene"
        private const val KEY_MCU_CAMERA = "mcu_camera_type"
        private const val KEY_MCU_TRACK = "mcu_track"
        private const val KEY_MCU_RADAR = "mcu_radar"
        private const val KEY_MCU_ORIG_VIDEO = "mcu_orig_video"
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
