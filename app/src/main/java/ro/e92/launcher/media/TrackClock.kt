package ro.e92.launcher.media

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ro.e92.launcher.core.Services

/**
 * Cât a cântat din piesa curentă, pentru muzica din telefon.
 *
 * Modulul Bluetooth trimite titlul și durata (linia MI), dar nu și poziția. O socotim noi:
 * de la zero la fiecare piesă nouă, cronometrul merge doar cât cântă (nu și pe pauză).
 * E o estimare: dacă te conectezi în mijlocul unei piese, pornește de la zero.
 */
object TrackClock {

    private var title = ""
    private var accumulatedMs = 0L
    private var runningSince = 0L

    /** Din [Services.init]: urmărește tot timpul, ca o piesă schimbată în alt meniu să reseteze. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            Services.kswBt.state.collect { onState(it.songTitle, it.audioStreaming) }
        }
    }

    @Synchronized
    private fun onState(songTitle: String, playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (songTitle != title) {
            title = songTitle
            accumulatedMs = 0L
            runningSince = if (playing) now else 0L
            return
        }
        if (playing && runningSince == 0L) runningSince = now
        if (!playing && runningSince != 0L) {
            accumulatedMs += now - runningSince
            runningSince = 0L
        }
    }

    @Synchronized
    fun positionMs(): Long =
        accumulatedMs + if (runningSince != 0L) SystemClock.elapsedRealtime() - runningSince else 0L
}
