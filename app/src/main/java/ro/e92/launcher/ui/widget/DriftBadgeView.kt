package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Semnul discret de deasupra mașinii de pe Home cât sistemele sunt dezactivate (cerut 2026-11-17,
 * „să vadă și pasagerul"): steagul de curse în carouri ales de utilizator (res/drawable-nodpi/
 * drift_flag.png, fără contur și catarg, 2026-11-19; pe circuit n-ai sisteme), „DRIFT MODE" și o linie
 * fină dedesubt care se stinge spre capete. La apariție steagul se desfășoară de la stânga la
 * dreapta; apoi totul stă pe loc, fără animații care să consume.
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
    private val flag = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.drift_flag)
    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val flagDst = android.graphics.RectF()

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
        val flagH = FLAG_H * dp
        val flagW = flagH * flag.width / flag.height
        val textW = textPaint.measureText(text)
        val total = flagW + FLAG_GAP * dp + textW
        val x0 = (width - total) / 2f
        val base = h * 0.62f

        // Steagul, centrat pe înălțimea literelor; la apariție se desfășoară de la stânga.
        val reveal = if (on) {
            val x = (t / UNFURL_MS).coerceIn(0f, 1f)
            1f - (1f - x) * (1f - x) * (1f - x)
        } else 1f
        val cy = base - 4.5f * dp
        flagDst.set(x0, cy - flagH / 2f, x0 + flagW, cy + flagH / 2f)
        canvas.save()
        canvas.clipRect(x0, flagDst.top - 2f, x0 + flagW * reveal, flagDst.bottom + 2f)
        flagPaint.alpha = (255 * a).toInt()
        canvas.drawBitmap(flag, null, flagDst, flagPaint)
        canvas.restore()

        textPaint.alpha = (255 * a).toInt()
        canvas.drawText(text, x0 + flagW + FLAG_GAP * dp, base, textPaint)

        // Linia fină de dedesubt, portocalie la mijloc, stinsă spre capete.
        val ly = base + 8f * dp
        fill.shader = LinearGradient(
            x0, 0f, x0 + total, 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(orange, 0.7f * a), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(x0, ly, x0 + total, ly + 1f * dp, fill)
        fill.shader = null

        if (k < 1f || (on && t < UNFURL_MS)) postInvalidateOnAnimation()
    }

    private fun withAlpha(color: Int, a: Float) =
        Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val FADE_MS = 350f
        /** Cât se desfășoară steagul la apariție. */
        const val UNFURL_MS = 600f
        /** Înălțimea steagului (dp); lățimea urmează imaginea. */
        const val FLAG_H = 18f
        /** Spațiul dintre steag și text (dp). */
        const val FLAG_GAP = 5f
    }
}
