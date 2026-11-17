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

    /** Salutul curent, ca o a doua apăsare din setări să nu-l suprapună. */
    private var player: MediaPlayer? = null
    /** Volumul unității dinainte de salut, de pus la loc când se termină. */
    private var restoreTo: Int? = null

    /** [force] = din setări („Play greeting"): pornește oricare ar fi numele. */
    fun play(context: Context, force: Boolean = false) {
        val name = Services.prefs.greetingName.trim()
        if (!force && !name.equals(Prefs.DEFAULT_GREETING_NAME, ignoreCase = true)) return
        stop(context)
        // Volumul salutului nu depinde de cât de încet e unitatea: o punem la volumul ales
        // doar cât vorbește, fără să-l salvăm, apoi revine.
        val app = context.applicationContext
        val now = ro.e92.launcher.core.KswVolume.current(app)
        val wanted = Services.prefs.greetingVolume
        if (now != null && now != wanted) {
            restoreTo = now
            ro.e92.launcher.core.KswVolume.setLive(app, wanted)
        }
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
                setOnCompletionListener { done(app) }
                setOnErrorListener { _, _, _ -> done(app); true }
                prepare()
                start()
            }.also { player = it }
        }.onFailure {
            EventLog.log(TAG, "salutul vocal nu pornește: ${it.message}")
            done(app)
        }
    }

    fun stop(context: Context) {
        if (player == null && restoreTo == null) return
        runCatching { player?.stop() }
        done(context.applicationContext)
    }

    private fun done(context: Context) {
        runCatching { player?.release() }
        player = null
        restoreTo?.let { ro.e92.launcher.core.KswVolume.setLive(context, it) }
        restoreTo = null
    }
}
