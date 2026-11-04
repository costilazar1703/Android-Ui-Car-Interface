package ro.e92.launcher.ui.screens

import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import ro.e92.launcher.R
import ro.e92.launcher.core.KswVolume
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost
import ro.e92.launcher.ui.widget.VolumeSliderView

/**
 * Volumul unității: bară de tras cu degetul, iar rotița iDrive o mișcă treaptă cu
 * treaptă. Nivelul ajunge la unitate prin [KswVolume], cel mult o dată la
 * [APPLY_MS], ca o tragere rapidă să nu trimită zeci de comenzi.
 */
class VolumeScreen(host: ScreenHost) : Screen(host) {

    override val title: String get() = context.getString(R.string.media_volume)
    override val backgroundRes: Int get() = R.drawable.bg_menu_media

    private lateinit var number: TextView
    private lateinit var slider: VolumeSliderView
    private var applied: Int? = null

    private val apply = Runnable {
        val v = slider.value
        if (v != applied) {
            applied = v
            KswVolume.set(context, v)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View {
        val density = context.resources.displayMetrics.density
        val start = KswVolume.current(context)
        applied = start
        slider = VolumeSliderView(context, KswVolume.MAX).apply {
            setValue(start ?: 0)
            onChange = { level -> onLevel(level) }
        }
        number = TextView(context).apply {
            typeface = ResourcesCompat.getFont(context, R.font.inter_display_light) ?: Typeface.DEFAULT
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 64f
            gravity = Gravity.CENTER
            includeFontPadding = false
            text = (start ?: 0).toString()
        }
        val label = TextView(context).apply {
            typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
            setTextColor(context.getColor(R.color.accent_focus))
            textSize = 13f
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            text = context.getString(R.string.media_volume).uppercase()
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((48 * density).toInt(), (22 * density).toInt(), (48 * density).toInt(), (10 * density).toInt())
            addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = (10 * density).toInt()
            })
        }
    }

    private fun onLevel(level: Int) {
        number.text = level.toString()
        slider.removeCallbacks(apply)
        slider.postDelayed(apply, APPLY_MS)
    }

    private fun step(delta: Int) {
        val next = (slider.value + delta).coerceIn(0, KswVolume.MAX)
        slider.setValue(next)
        onLevel(next)
    }

    override fun onHide() {
        // Ultimul nivel ales pleacă și dacă ai ieșit imediat după ce l-ai tras.
        slider.removeCallbacks(apply)
        apply.run()
    }

    override fun onAction(action: LauncherAction): Boolean = when (action) {
        LauncherAction.ROTARY_CW, LauncherAction.TILT_RIGHT -> { step(+1); true }
        LauncherAction.ROTARY_CCW, LauncherAction.TILT_LEFT -> { step(-1); true }
        LauncherAction.SELECT -> { host.pop(); true }
        else -> false
    }

    private companion object {
        const val APPLY_MS = 120L
    }
}
