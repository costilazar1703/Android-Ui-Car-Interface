package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Semnul discret de deasupra mașinii de pe Home cât sistemele sunt dezactivate (cerut 2026-11-17,
 * „să vadă și pasagerul"): trei bare oblice portocalii, ca la afișajul modului, „DRIFT MODE" și o
 * linie fină dedesubt care se stinge spre capete. Apare și dispare lin; altfel stă pe loc,
 * fără animații care să consume.
 */
class DriftBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val orange = ContextCompat.getColor(context, R.color.glow_core)
    private val dp = resources.displayMetrics.density
    private val text = context.getString(R.string.home_drift_badge)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.inter_semibold)
        textSize = 13f * dp
        letterSpacing = 0.32f
        color = Color.rgb(236, 237, 240)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var on = false
    private var changedAt = 0L

    init {
        visibility = INVISIBLE
    }

    fun setOn(value: Boolean) {
        if (value == on) return
        on = value
        changedAt = SystemClock.uptimeMillis()
        if (value) visibility = VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - changedAt).toFloat()
        val k = (t / FADE_MS).coerceIn(0f, 1f)
        val a = if (on) 1f - (1f - k) * (1f - k) else (1f - k) * (1f - k)
        if (a <= 0f && !on) {
            visibility = INVISIBLE
            return
        }

        val h = height.toFloat()
        val barsW = 3 * 4f * dp + 2 * 3f * dp + 7f * dp
        val textW = textPaint.measureText(text)
        val total = barsW + 10f * dp + textW
        val x0 = (width - total) / 2f
        val base = h * 0.62f

        // Barele: din ce în ce mai aprinse, ultima nuanța portocalie a interfeței.
        val top = base - 13f * dp
        val bottom = base + 1f * dp
        for (i in 0..2) {
            val x = x0 + i * (4f * dp + 3f * dp)
            path.reset()
            path.moveTo(x + 7f * dp, top)
            path.lineTo(x + 11f * dp, top)
            path.lineTo(x + 4f * dp, bottom)
            path.lineTo(x, bottom)
            path.close()
            fill.color = withAlpha(orange, a * (0.5f + 0.25f * i))
            canvas.drawPath(path, fill)
        }

        textPaint.alpha = (255 * a).toInt()
        canvas.drawText(text, x0 + barsW + 10f * dp, base, textPaint)

        // Linia fină de dedesubt, portocalie la mijloc, stinsă spre capete.
        val ly = base + 8f * dp
        fill.shader = LinearGradient(
            x0, 0f, x0 + total, 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(orange, 0.7f * a), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(x0, ly, x0 + total, ly + 1f * dp, fill)
        fill.shader = null

        if (k < 1f) postInvalidateOnAnimation()
    }

    private fun withAlpha(color: Int, a: Float) =
        Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val FADE_MS = 350f
    }
}
