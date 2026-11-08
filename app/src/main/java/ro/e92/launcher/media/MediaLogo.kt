package ro.e92.launcher.media

import androidx.annotation.DrawableRes
import ro.e92.launcher.R
import ro.e92.launcher.core.KswBluetooth
import ro.e92.launcher.core.Services

/**
 * Logo-ul aplicației din care cântă muzica, pentru meniul Media și bara de jos.
 *
 * O aplicație de pe unitate se recunoaște după pachet. Muzica din telefon vine pe
 * Bluetooth doar cu titlu, artist și album, fără numele aplicației, așa că acolo
 * logo-ul e cel ales de șofer ([Prefs.phoneMusicApp], schimbat din bara de jos).
 */
object MediaLogo {

    /** Ordinea în care se trece prin ele la atingere; 0 = nota muzicală obișnuită. */
    private val PHONE_APPS = intArrayOf(
        R.drawable.ic_menu_media,
        R.drawable.ic_audio_spotify,
        R.drawable.ic_audio_ytmusic,
        R.drawable.ic_audio_youtube
    )

    @DrawableRes
    fun current(): Int {
        val pkg = Services.media.snapshot.value.packageName ?: return R.drawable.ic_menu_media
        return when {
            pkg == KswBluetooth.PACKAGE -> PHONE_APPS[Services.prefs.phoneMusicApp.coerceIn(0, PHONE_APPS.size - 1)]
            "spotify" in pkg -> R.drawable.ic_audio_spotify
            "youtube.music" in pkg -> R.drawable.ic_audio_ytmusic
            "youtube" in pkg -> R.drawable.ic_audio_youtube
            else -> R.drawable.ic_menu_media
        }
    }

    /** Doar pentru muzica din telefon: trece la următoarea aplicație. false = alt izvor, nimic de ales. */
    fun cyclePhoneApp(): Boolean {
        if (Services.media.snapshot.value.packageName != KswBluetooth.PACKAGE) return false
        Services.prefs.phoneMusicApp = (Services.prefs.phoneMusicApp + 1) % PHONE_APPS.size
        return true
    }
}
