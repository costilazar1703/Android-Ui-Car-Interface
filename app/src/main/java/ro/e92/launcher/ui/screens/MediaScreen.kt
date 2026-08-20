package ro.e92.launcher.ui.screens

import android.content.Intent
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
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
 * Media în stil BMW NBT: coverul și metadata în stânga, lista de surse în dreapta.
 *
 * Nu conține un player propriu. Tot ce cântă pe unitate — A2DP de pe telefon,
 * Spotify, YouTube Music, player-ul OEM — publică un MediaSession, iar noi
 * comandăm sesiunea activă. Player-ul local de pe USB e pasul următor
 * (vezi Open Question 9: dacă sursa e exclusiv Bluetooth, nici nu e nevoie de el).
 */
class MediaScreen(host: ScreenHost) : Screen(host) {

    override val title: String get() = "MEDIA"

    private lateinit var binding: ScreenMediaBinding
    private lateinit var adapter: RowAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup) =
        ScreenMediaBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        binding.lblList.text = context.getString(R.string.media_sources)

        adapter = RowAdapter { row -> Services.media.pinSession(row.id) }
        binding.listMedia.apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            itemAnimator = null
            adapter = this@MediaScreen.adapter
        }

        Services.media.refreshSessions()

        screenScope.launch {
            Services.media.snapshot.collect { render(it) }
        }
        screenScope.launch {
            Services.media.sessions.collect { sessions ->
                adapter.submit(
                    sessions.map { s ->
                        Row(
                            id = s.packageName,
                            title = s.label,
                            subtitle = if (s.isPlaying) "playing" else "paused"
                        )
                    }
                )
                updateEmptyState(sessions.isEmpty())
            }
        }
        screenScope.launch {
            while (isActive) {
                renderProgress(Services.media.snapshot.value)
                delay(500)
            }
        }
    }

    override fun focusTargets(): List<FocusTarget> = listOf(
        FocusTarget(
            id = "prev",
            view = binding.btnPrev,
            onActivate = { Services.media.previous() },
            right = "play"
        ),
        FocusTarget(
            id = "play",
            view = binding.btnPlay,
            onActivate = { Services.media.playPause() },
            left = "prev",
            right = "next"
        ),
        FocusTarget(
            id = "next",
            view = binding.btnNext,
            onActivate = { Services.media.next() },
            left = "play",
            right = "list"
        ),
        FocusTarget(
            id = "list",
            view = binding.listMedia,
            onActivate = { adapter.activateSelected() },
            onRotate = { steps ->
                val moved = adapter.moveSelection(steps)
                if (moved) binding.listMedia.scrollToPosition(adapter.selectedIndex)
                moved
            },
            left = "next"
        )
    )

    /** Butoanele de volan trebuie să funcționeze și când ecranul Media e deschis. */
    override fun onAction(action: LauncherAction): Boolean = when (action) {
        LauncherAction.MEDIA_NEXT -> { Services.media.next(); true }
        LauncherAction.MEDIA_PREV -> { Services.media.previous(); true }
        LauncherAction.MEDIA_PLAY_PAUSE -> { Services.media.playPause(); true }
        // Fără notification access lista e goală și nimic nu funcționează;
        // OPTION duce direct în ecranul unde se acordă.
        LauncherAction.OPTION -> { openNotificationAccess(); true }
        else -> false
    }

    private fun render(media: MediaHub.Snapshot) {
        binding.txtTitle.text = media.title ?: context.getString(R.string.media_no_session)
        binding.txtArtist.text = media.artist.orEmpty()
        binding.txtAlbum.text = media.album.orEmpty()
        binding.imgCover.setImageBitmap(media.artwork)
        binding.btnPlay.text = if (media.isPlaying) "❚❚" else "▶"
        renderProgress(media)
    }

    private fun renderProgress(media: MediaHub.Snapshot) {
        val duration = media.durationMs
        if (duration <= 0) {
            binding.progressTrack.progress = 0
            binding.txtPosition.text = ""
            return
        }
        val position = media.positionNow()
        binding.progressTrack.progress =
            ((position.toFloat() / duration) * 1000).toInt().coerceIn(0, 1000)
        binding.txtPosition.text = "${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun updateEmptyState(empty: Boolean) {
        if (!empty) {
            binding.txtListEmpty.visibility = View.GONE
            return
        }
        binding.txtListEmpty.visibility = View.VISIBLE
        binding.txtListEmpty.text = if (Services.media.hasNotificationAccess()) {
            context.getString(R.string.media_no_session)
        } else {
            context.getString(R.string.media_grant_access)
        }
    }

    override fun onDestroy() {
        binding.listMedia.adapter = null
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
