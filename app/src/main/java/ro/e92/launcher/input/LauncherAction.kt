package ro.e92.launcher.input

/**
 * Acțiunile logice ale launcher-ului. Codul de UI nu vede niciodată keycode-uri —
 * doar acțiuni. Maparea keycode → acțiune se descoperă în Faza 0.2 și e editabilă
 * din Setări, fără recompilare.
 */
enum class LauncherAction(val label: String) {

    ROTARY_CW("Knob — turn right"),
    ROTARY_CCW("Knob — turn left"),
    SELECT("Knob — press"),

    TILT_UP("Knob — tilt up"),
    TILT_DOWN("Knob — tilt down"),
    TILT_LEFT("Knob — tilt left"),
    TILT_RIGHT("Knob — tilt right"),

    HOME("MENU / Home"),
    BACK("BACK"),
    OPTION("OPTION"),

    NAV("NAV → Navigation menu"),
    MEDIA("MEDIA → Media menu"),
    PHONE("TEL"),
    RADIO("RADIO"),

    MEDIA_NEXT("Steering — next track"),
    MEDIA_PREV("Steering — previous track"),
    MEDIA_PLAY_PAUSE("Steering — play/pause"),
    VOICE("Steering — voice"),
    VOLUME_UP("Music volume +"),
    VOLUME_DOWN("Music volume -"),

    APPS("App drawer"),
    DIAGNOSTICS("Diagnostics");

    companion object {
        fun byName(name: String): LauncherAction? = entries.firstOrNull { it.name == name }
    }
}
