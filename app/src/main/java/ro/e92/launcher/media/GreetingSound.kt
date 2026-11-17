package ro.e92.launcher.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import ro.e92.launcher.R
import ro.e92.launcher.core.EventLog
import ro.e92.launcher.core.Prefs
import ro.e92.launcher.core.Services

/**
 * Salutul vocal de la pornire (2026-11-17): un sunet de pornire ca la mașinile noi, apoi vocea
 * „Welcome back, Mr. Lazar", într-un singur fișier (res/raw/greeting_bmw.mp3): un pad cald de 3 s care
 * urmează intrarea din [ro.e92.launcher.ui.widget.BootIntroView], apoi vocea, puțin încetinită.
 *
 * Se aude doar cu radioul mașinii pe sursa unității, ca orice sunet al ei. Nu cere focus audio,
 * ca să nu oprească muzica de pe telefon. Vocea spune un nume fix: dacă numele din setări e altul,
 * nu pornește deloc, ca să nu spună alt nume decât cel de pe ecran.
 */
object GreetingSound {

    private const val TAG = "Greeting"

    fun play(context: Context) {
        val name = Services.prefs.greetingName.trim()
        if (!name.equals(Prefs.DEFAULT_GREETING_NAME, ignoreCase = true)) return
        runCatching {
            val afd = context.resources.openRawResourceFd(R.raw.greeting_bmw)
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                prepare()
                start()
            }
        }.onFailure { EventLog.log(TAG, "salutul vocal nu pornește: ${it.message}") }
    }
}
