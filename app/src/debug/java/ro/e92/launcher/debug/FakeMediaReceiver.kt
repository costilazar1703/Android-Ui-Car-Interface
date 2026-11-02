package ro.e92.launcher.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * Sesiune media falsă pentru emulator:
 *
 *   adb shell am broadcast -a ro.e92.launcher.debug.FAKE_MEDIA --es cmd play [--ez art true]
 *   adb shell am broadcast -a ro.e92.launcher.debug.FAKE_MEDIA --es cmd pause|next|stop
 *
 * MediaHub o vede ca pe orice altă sesiune (cere notification access, ca în mașină).
 */
class FakeMediaReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val withArt = intent.getBooleanExtra("art", false)
        when (intent.getStringExtra("cmd")) {
            "play" -> play(context.applicationContext, withArt)
            "pause" -> setState(PlaybackState.STATE_PAUSED)
            "next" -> { track = (track + 1) % TRACKS.size; play(context.applicationContext, withArt) }
            "stop" -> { session?.release(); session = null }
        }
    }

    private fun play(context: Context, withArt: Boolean) {
        val s = session ?: MediaSession(context, "FakeMedia").also {
            it.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = setState(PlaybackState.STATE_PLAYING)
                override fun onPause() = setState(PlaybackState.STATE_PAUSED)
                override fun onSkipToNext() { track = (track + 1) % TRACKS.size; play(context, withArt) }
                override fun onSkipToPrevious() = setState(PlaybackState.STATE_PLAYING)
            })
            session = it
        }
        val (title, artist, album) = TRACKS[track]
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 245_000L)
        if (withArt) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art())
        s.setMetadata(md.build())
        s.isActive = true
        setState(PlaybackState.STATE_PLAYING)
    }

    private fun setState(state: Int) {
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, 83_000L, 1f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun art(): Bitmap {
        val b = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, 400f, 400f, 0xFF2B5876.toInt(), 0xFF4E4376.toInt(), Shader.TileMode.CLAMP)
        }
        Canvas(b).drawRect(0f, 0f, 400f, 400f, p)
        return b
    }

    private companion object {
        var session: MediaSession? = null
        var track = 0
        val TRACKS = listOf(
            Triple("Midnight City", "M83", "Hurry Up, We're Dreaming"),
            Triple("Nightcall", "Kavinsky", "OutRun"),
            Triple("Blinding Lights", "The Weeknd", "After Hours")
        )
    }
}
