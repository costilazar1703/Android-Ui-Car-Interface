package ro.e92.launcher.input

/**
 * Acțiunile logice ale launcher-ului. Codul de UI nu vede niciodată keycode-uri —
 * doar acțiuni. Maparea keycode → acțiune se descoperă în Faza 0.2 și e editabilă
 * din Setări, fără recompilare.
 */
enum class LauncherAction(val label: String) {

    ROTARY_CW("Rotiță — dreapta"),
    ROTARY_CCW("Rotiță — stânga"),
    SELECT("Apăsare rotiță"),

    TILT_UP("Tilt sus"),
    TILT_DOWN("Tilt jos"),
    TILT_LEFT("Tilt stânga"),
    TILT_RIGHT("Tilt dreapta"),

    HOME("MENU / Home"),
    BACK("BACK"),
    OPTION("OPTION"),

    NAV("NAV"),
    MEDIA("CD / MEDIA"),
    PHONE("TEL"),
    RADIO("RADIO"),

    MEDIA_NEXT("Volan — următoarea"),
    MEDIA_PREV("Volan — anterioara"),
    MEDIA_PLAY_PAUSE("Volan — play/pause"),
    VOICE("Volan — voice"),

    APPS("App drawer"),
    DIAGNOSTICS("Diagnostic");

    companion object {
        fun byName(name: String): LauncherAction? = entries.firstOrNull { it.name == name }
    }
}
