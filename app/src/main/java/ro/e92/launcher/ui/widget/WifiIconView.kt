package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Wi-Fi în bara de sus: punctul și cele trei arce se aprind după semnal.
 *
 * [level]: -1 fără conexiune (totul stins), 0 doar punctul, 3 semnal plin.
 */
class WifiIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var level: Int = -1
        set(value) {
            val next = value.coerceIn(-1, 3)
            if (field == next) return
            field = next
            contentDescription = if (next < 0) null else "Wi-Fi $next"
            invalidate()
        }

    private val lit = ContextCompat.getColor(context, R.color.glow_core)
    private val unlit = Color.argb(70, 255, 255, 255)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.6f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    // De la cel mic la cel mare, în același viewport de 30 ca vechea iconiță.
    private val arcs = listOf(
        Path().apply { moveTo(12f, 21f); cubicTo(13.7f, 19.5f, 16.3f, 19.5f, 18f, 21f) },
        Path().apply { moveTo(8f, 17f); cubicTo(11.9f, 13.2f, 18.1f, 13.2f, 22f, 17f) },
        Path().apply { moveTo(3.5f, 12.5f); cubicTo(9.8f, 6.3f, 20.2f, 6.3f, 26.5f, 12.5f) }
    )

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width, height) / VIEWPORT
        canvas.save()
        canvas.translate((width - VIEWPORT * scale) / 2f, (height - VIEWPORT * scale) / 2f)
        canvas.scale(scale, scale)
        arcs.forEachIndexed { i, path ->
            arc.color = if (i < level) lit else unlit
            canvas.drawPath(path, arc)
        }
        dot.color = if (level >= 0) lit else unlit
        canvas.drawCircle(15f, 25f, 2f, dot)
        canvas.restore()
    }

    private companion object {
        const val VIEWPORT = 30f
    }
}
