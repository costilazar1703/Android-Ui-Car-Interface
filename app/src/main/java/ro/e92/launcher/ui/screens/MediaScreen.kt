package ro.e92.launcher.ui.screens

import android.content.Intent
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ScreenMediaBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.media.MediaHub
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost

/**
 * Media în stil „Now Playing" din iOS: coperta mare și rotunjită în stânga,
 * titlul, progresul și transportul lângă ea. Fără copertă de la sursă se arată
 * `media_cover_default`, nu un pătrat gol.
 *
 * **Sursa** (ce aplicație comandăm) e o pastilă mică sub copertă, vizibilă doar
 * când cântă cel puțin două aplicații; apăsată, trece la următoarea. Cu o singură
 * sursă n-ai ce alege, deci nu ocupă loc.
 *
 * Nu conține un player propriu. Tot ce cântă pe unitate — A2DP de pe telefon,
 * Spotify, YouTube Music, player-ul OEM — publică un MediaSession, iar noi
 * comandăm sesiunea activă.
 */
class MediaScreen(host: ScreenHost) : Screen(host) {
    override val backgroundRes: Int get() = R.drawable.bg_menu_media

    override val title: String get() = "MEDIA"

    private lateinit var binding: ScreenMediaBinding
    private var sessions: List<MediaHub.SessionInfo> = emptyList()
    private var focusedId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup) =
        ScreenMediaBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        // Colțurile din bg_cover_rounded decupează și imaginea, când există.
        binding.imgCover.clipToOutline = true

        Services.media.refreshSessions()

        screenScope.launch {
            Services.media.snapshot.collect { render(it) }
        }
        screenScope.launch {
            Services.media.sessions.collect { list ->
                val hadChip = sessions.size >= 2
                sessions = list
                renderSource()
                if (hadChip != list.size >= 2) host.rebuildFocus(focusedId)
            }
        }
        screenScope.launch {
            while (isActive) {
                renderProgress(Services.media.snapshot.value)
                delay(500)
            }
        }
    }

    override fun focusTargets(): List<FocusTarget> {
        val showSource = sessions.size >= 2
        val targets = mutableListOf(
            FocusTarget(
                id = "prev",
                view = binding.btnPrev,
                onActivate = { Services.media.previous() },
                onFocus = { if (it) focusedId = "prev" },
                left = if (showSource) "source" else null,
                right = "play"
            ),
            FocusTarget(
                id = "play",
                view = binding.btnPlay,
                onActivate = { Services.media.playPause() },
                onFocus = { if (it) focusedId = "play" },
                left = "prev",
                right = "next"
            ),
            FocusTarget(
                id = "next",
                view = binding.btnNext,
                onActivate = { Services.media.next() },
                onFocus = { if (it) focusedId = "next" },
                left = "play"
            )
        )
        if (showSource) {
            targets.add(
                0,
                FocusTarget(
                    id = "source",
                    view = binding.btnSource,
                    onActivate = { nextSource() },
                    onFocus = { if (it) focusedId = "source" },
                    right = "prev"
                )
            )
        }
        return targets
    }

    /** Butoanele de volan trebuie să funcționeze și când ecranul Media e deschis. */
    override fun onAction(action: LauncherAction): Boolean = when (action) {
        LauncherAction.MEDIA_NEXT -> { Services.media.next(); true }
        LauncherAction.MEDIA_PREV -> { Services.media.previous(); true }
        LauncherAction.MEDIA_PLAY_PAUSE -> { Services.media.playPause(); true }
        // Fără notification access nu se vede nicio sursă și nimic nu funcționează;
        // OPTION duce direct în ecranul unde se acordă.
        LauncherAction.OPTION -> { openNotificationAccess(); true }
        else -> false
    }

    /** Trece la aplicația următoare din listă și o fixează ca sursă. */
    private fun nextSource() {
        if (sessions.size < 2) return
        val current = Services.media.snapshot.value.packageName
        val i = sessions.indexOfFirst { it.packageName == current }
        Services.media.pinSession(sessions[(i + 1) % sessions.size].packageName)
    }

    private fun renderSource() {
        if (sessions.size < 2) {
            binding.btnSource.visibility = View.GONE
            return
        }
        val current = Services.media.snapshot.value.packageName
        val label = sessions.firstOrNull { it.packageName == current }?.label ?: sessions.first().label
        binding.btnSource.text = context.getString(R.string.media_source_chip, label)
        binding.btnSource.visibility = View.VISIBLE
    }

    private fun render(media: MediaHub.Snapshot) {
        binding.txtTitle.text = media.title ?: context.getString(R.string.media_no_session)
        binding.txtArtist.text = when {
            media.hasSession -> media.artist.orEmpty()
            Services.media.hasNotificationAccess() -> ""
            else -> context.getString(R.string.media_grant_access)
        }
        binding.txtAlbum.text = media.album.orEmpty()
        val art = media.artwork
        if (art != null) {
            binding.imgCover.setImageBitmap(art)
            binding.imgCover.setBackgroundResource(R.drawable.bg_cover_rounded)
        } else {
            // Coperta implicită are deja rama și colțurile ei, transparente în
            // afara ramei. Fără fundal: pătratul închis de dedesubt se vedea la
            // colțuri.
            binding.imgCover.setImageResource(R.drawable.media_cover_default)
            binding.imgCover.background = null
        }
        binding.btnPlay.setImageResource(
            if (media.isPlaying) R.drawable.ic_transport_pause else R.drawable.ic_transport_play
        )
        renderProgress(media)
        renderSource()
    }

    private fun renderProgress(media: MediaHub.Snapshot) {
        val duration = media.durationMs
        if (duration <= 0) {
            binding.progressTrack.progress = 0
            binding.txtElapsed.text = ""
            binding.txtRemaining.text = ""
            return
        }
        val position = media.positionNow()
        binding.progressTrack.progress =
            ((position.toFloat() / duration) * 1000).toInt().coerceIn(0, 1000)
        // Ca în iOS: cât a trecut în stânga, cât mai e în dreapta.
        binding.txtElapsed.text = formatTime(position)
        binding.txtRemaining.text = "-" + formatTime((duration - position).coerceAtLeast(0))
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    private fun openNotificationAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
