package ro.e92.launcher.ui.screens

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import ro.e92.launcher.R
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost

/**
 * Confirmarea DSC oprit sau pornit, peste ecranul curent. Dispare singură.
 *
 * Animația merge pe ceasul ei, cadru cu cadru: pe unitate scara animațiilor Android
 * poate fi 0, iar atunci Animator-ele sar direct la final.
 */
class DriftScreen(host: ScreenHost, private val on: Boolean) : Screen(host) {

    override val title: String get() = context.getString(R.string.drift_title)
    override val isOverlay: Boolean get() = true

    private var dismissed = false
    private var startedAt = 0L
    private var ticker: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        inflater.inflate(R.layout.screen_drift, container, false)

    override fun onShow() {
        val view = root ?: return
        val photo = view.findViewById<ImageView>(R.id.driftPhoto)
        photo.setImageResource(if (on) R.drawable.bg_drift else R.drawable.bg_home)
        val message = view.findViewById<ImageView>(R.id.driftMessage)
        message.setImageResource(if (on) R.drawable.msg_drift_on else R.drawable.msg_drift_off)

        startedAt = SystemClock.uptimeMillis()
        val density = context.resources.displayMetrics.density
        val tick = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis() - startedAt
                photo.scaleX = PHOTO_START_SCALE - (PHOTO_START_SCALE - 1f) * ease(now, 0L, TOTAL_MS)
                photo.scaleY = photo.scaleX
                val entering = ease(now, MESSAGE_AT_MS, MESSAGE_MS)
                message.alpha = entering
                message.translationX = (entering - 1f) * MESSAGE_SLIDE_DP * density
                view.alpha = ease(now, 0L, FADE_IN_MS) - ease(now, TOTAL_MS - EXIT_MS, EXIT_MS)
                if (now >= TOTAL_MS) dismiss() else view.postOnAnimation(this)
            }
        }
        ticker = tick
        tick.run()
    }

    override fun onHide() {
        ticker?.let { root?.removeCallbacks(it) }
        ticker = null
    }

    override fun focusTargets(): List<FocusTarget> = emptyList()

    override fun onAction(action: LauncherAction): Boolean {
        dismiss()
        return false
    }

    /** 0 până la [from], 1 după [length] ms, cu încetinire spre final. */
    private fun ease(now: Long, from: Long, length: Long): Float {
        val t = ((now - from).toFloat() / length).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t)
    }

    private fun dismiss() {
        if (dismissed) return
        dismissed = true
        host.pop()
    }

    private companion object {
        const val TOTAL_MS = 3_400L
        const val PHOTO_START_SCALE = 1.07f
        const val FADE_IN_MS = 240L
        const val MESSAGE_AT_MS = 120L
        const val MESSAGE_MS = 460L
        const val MESSAGE_SLIDE_DP = 70f
        const val EXIT_MS = 320L
    }
}
