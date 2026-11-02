package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R
import kotlin.math.sin

/**
 * Trei bare care „cântă" — semnul că ceva chiar rulează, ca în Apple Music.
 *
 * Nu e un analizor de spectru: n-avem acces la audio, iar nici nu e nevoie.
 * Barele urmează sinusoide cu perioade diferite, deci nu se repetă vizibil.
 *
 * Animația rulează doar cât [playing] e true și view-ul e pe ecran, la ~20 fps:
 * pe home stă mereu deschis, deci nu trebuie să țină GPU-ul treaz degeaba.
 * Pe pauză barele rămân jos, nemișcate.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var playing = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glow_core)
    }
    private val bar = RectF()
    private val frame = Runnable { invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w * 0.16f
        val barW = (w - 2 * gap) / BARS
        val r = barW / 2f
        val t = SystemClock.uptimeMillis() / 1000.0
        for (i in 0 until BARS) {
            val level = if (playing) {
                (0.55 + 0.45 * sin(t * SPEEDS[i] + PHASES[i])).toFloat()
            } else {
                REST
            }
            val x = i * (barW + gap)
            bar.set(x, h * (1f - level.coerceIn(REST, 1f)), x + barW, h)
            canvas.drawRoundRect(bar, r, r, paint)
        }
        if (playing && isShown) postOnAnimationDelayed(frame, FRAME_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val BARS = 3
        const val REST = 0.22f
        const val FRAME_MS = 50L
        val SPEEDS = doubleArrayOf(7.1, 9.7, 5.9)
        val PHASES = doubleArrayOf(0.0, 1.9, 4.1)
    }
}
