package ro.e92.launcher.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sursa de adevăr pentru "ce cântă acum", indiferent de unde vine: A2DP de pe
 * telefon, Spotify, YouTube Music sau player-ul OEM. Toate publică un MediaSession,
 * deci un singur strat le acoperă pe toate — cel mai mare câștig cu cel mai puțin cod.
 *
 * Necesită "Notification access" acordat manual o dată; fără el
 * [MediaSessionManager.getActiveSessions] aruncă SecurityException.
 */
class MediaHub(private val context: Context) {

    data class Snapshot(
        val packageName: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val artwork: Bitmap? = null,
        val isPlaying: Boolean = false,
        val durationMs: Long = 0L,
        /** Poziția la momentul [positionUpdatedAt]; interpolează cu [positionNow]. */
        val positionMs: Long = 0L,
        val playbackSpeed: Float = 1f,
        val positionUpdatedAt: Long = 0L
    ) {
        val hasSession: Boolean get() = packageName != null

        /** Poziția estimată acum, fără a interoga controller-ul la fiecare frame. */
        fun positionNow(): Long {
            if (!isPlaying || positionUpdatedAt == 0L) return positionMs
            val elapsed = SystemClock.elapsedRealtime() - positionUpdatedAt
            return (positionMs + elapsed * playbackSpeed).toLong().coerceAtMost(
                if (durationMs > 0) durationMs else Long.MAX_VALUE
            )
        }
    }

    data class SessionInfo(val packageName: String, val label: String, val isPlaying: Boolean)

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val manager: MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private val listenerComponent = ComponentName(context, E92NotificationListener::class.java)

    private var controller: MediaController? = null
    /** Pachetul preferat manual din ecranul Media; are prioritate dacă e încă activ. */
    private var pinnedPackage: String? = null
    private var started = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refreshSessions()
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { refreshSessions() }

    fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { it.startsWith("${context.packageName}/") }
    }

    fun start() {
        if (started) return
        val mgr = manager ?: return
        try {
            mgr.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent, handler)
            started = true
            refreshSessions()
        } catch (e: SecurityException) {
            Log.i(TAG, "Notification access neacordat — media rămâne gol", e)
        }
    }

    fun stop() {
        if (!started) return
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        detachController()
        started = false
    }

    fun refreshSessions() {
        val mgr = manager ?: return
        val active = try {
            mgr.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            emptyList()
        }

        _sessions.value = active.map {
            SessionInfo(
                packageName = it.packageName,
                label = labelFor(it.packageName),
                isPlaying = it.playbackState?.state == PlaybackState.STATE_PLAYING
            )
        }

        // Preferăm sesiunea fixată manual, apoi cea care chiar cântă, apoi prima.
        val chosen = active.firstOrNull { it.packageName == pinnedPackage }
            ?: active.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: active.firstOrNull()

        if (chosen?.sessionToken != controller?.sessionToken) {
            detachController()
            controller = chosen
            chosen?.registerCallback(controllerCallback, handler)
        }
        publish()
    }

    fun pinSession(packageName: String) {
        pinnedPackage = packageName
        refreshSessions()
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun next() = controller?.transportControls?.skipToNext() ?: Unit

    fun previous() = controller?.transportControls?.skipToPrevious() ?: Unit

    private fun detachController() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _snapshot.value = Snapshot()
            return
        }
        val md = c.metadata
        val ps = c.playbackState

        _snapshot.value = Snapshot(
            packageName = c.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            artwork = pickArtwork(md),
            isPlaying = ps?.state == PlaybackState.STATE_PLAYING,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = ps?.position ?: 0L,
            playbackSpeed = ps?.playbackSpeed ?: 1f,
            positionUpdatedAt = ps?.lastPositionUpdateTime ?: 0L
        )
    }

    /**
     * Bitmap-ul vine direct din metadata sesiunii — nu descărcăm nimic, deci nu e
     * nevoie de Glide/Coil pentru un singur use-case. Downscale-ul se face în View
     * prin scaleType, iar imaginile absurd de mari le refuzăm aici.
     */
    private fun pickArtwork(md: MediaMetadata?): Bitmap? {
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        if (bmp.width > MAX_ART_PX || bmp.height > MAX_ART_PX) {
            return runCatching {
                Bitmap.createScaledBitmap(bmp, MAX_ART_PX, MAX_ART_PX, true)
            }.getOrNull()
        }
        return bmp
    }

    private fun labelFor(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private companion object {
        const val TAG = "MediaHub"
        /** Coverul afișat are ~150 dp; peste 512 px e risipă de heap pe 2 GB. */
        const val MAX_ART_PX = 512
    }
}
