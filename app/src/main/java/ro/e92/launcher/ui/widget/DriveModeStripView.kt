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
 * Modul cutiei pe Home, sub mașină („Integrated Gear Display", ales 2026-11-17): fără card, o
 * literă mare în stânga (D sau DS), numele modului mic în dreapta, o linie fină între ele și
 * o linie jos care continuă podeaua de sub mașină, cu o strălucire discretă în culoarea modului.
 *
 * La schimbare: litera veche se stinge repede, cea nouă intră alunecând câțiva pixeli, iar
 * linia de jos se aprinde treptat de la stânga la dreapta. Desenat pe ceasul propriu, ca
 * restul animațiilor (pe unitate scara animațiilor Android poate fi 0).
 */
class DriveModeStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val orange = ContextCompat.getColor(context, R.color.glow_core)
    private val steel = Color.rgb(170, 184, 199)
    private val density = resources.displayMetrics.density

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.inter_semibold)
        textSize = 40f * density
        letterSpacing = 0.02f
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.inter_semibold)
        textSize = 12.5f * density
        letterSpacing = 0.3f
        textAlign = Paint.Align.RIGHT
    }
    private val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.inter_semibold)
        textSize = 9.5f * density
        letterSpacing = 0.3f
        textAlign = Paint.Align.RIGHT
        color = Color.rgb(110, 114, 121)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val kicker = context.getString(R.string.home_driving_mode)

    private var sport: Boolean? = null
    private var previous: Boolean? = null
    private var changedAt = 0L

    /** [animate] = schimbare văzută acum; prima valoare se arată direct. */
    fun setMode(value: Boolean?, animate: Boolean) {
        if (value == sport) return
        previous = sport
        sport = value
        visibility = if (value == null) GONE else VISIBLE
        changedAt = if (animate && previous != null) SystemClock.uptimeMillis() else 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = sport ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val t = if (changedAt == 0L) ANIM_MS else (SystemClock.uptimeMillis() - changedAt).toFloat()

        val accent = if (now) orange else steel
        val lineY = h - 3f * density

        // ---- linia de jos: se aprinde de la stânga la dreapta, cu strălucire ----
        val lit = ease(t, 120f, 520f)
        drawFloorLine(canvas, w, lineY, steel, if (now) 0.18f else 0.42f, 1f)
        drawFloorLine(canvas, w, lineY, accent, if (now) 1f else 0.6f, lit)

        // ---- litera: cea veche se stinge, cea nouă intră alunecând ----
        val letterBase = lineY - 12f * density
        val old = previous
        if (old != null && t < 160f) {
            letterPaint.color = withAlpha(if (old) orange else Color.rgb(230, 232, 236), 1f - t / 160f)
            canvas.drawText(if (old) "DS" else "D", 0f, letterBase, letterPaint)
        }
        val inT = ease(t, 90f, 320f)
        letterPaint.color = withAlpha(if (now) orange else Color.rgb(230, 232, 236), inT)
        val letter = if (now) "DS" else "D"
        val slide = (1f - inT) * 11f * density
        canvas.drawText(letter, slide, letterBase, letterPaint)

        // ---- dreapta: DRIVING MODE mic, dedesubt numele modului ----
        val name = context.getString(if (now) R.string.mode_strip_sport else R.string.mode_strip_drive)
        val nameBase = letterBase - 1f * density
        kickerPaint.alpha = 255
        canvas.drawText(kicker, w, nameBase - namePaint.textSize - 6f * density, kickerPaint)
        namePaint.color = withAlpha(if (now) Color.WHITE else steel, 0.4f + 0.6f * inT)
        canvas.drawText(name, w, nameBase, namePaint)

        // ---- linia fină dintre literă și text ----
        val letterEnd = letterPaint.measureText(if (now) "DS" else "D") + 14f * density
        val textStart = w - namePaint.measureText(name) - 16f * density
        if (textStart > letterEnd) {
            val midY = nameBase - namePaint.textSize * 0.36f
            linePaint.shader = LinearGradient(
                letterEnd, 0f, textStart, 0f,
                withAlpha(accent, 0.55f), withAlpha(accent, 0.05f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(letterEnd, midY - 0.5f * density, letterEnd + (textStart - letterEnd) * lit, midY + 0.5f * density, linePaint)
            linePaint.shader = null
        }

        if (t < ANIM_MS) postInvalidateOnAnimation()
    }

    /** O linie care se stinge spre capete, cu un halou din câteva linii mai groase și mai slabe. */
    private fun drawFloorLine(canvas: Canvas, w: Float, y: Float, color: Int, strength: Float, reveal: Float) {
        if (reveal <= 0f || strength <= 0f) return
        val right = w * reveal
        for ((thick, a) in GLOW) {
            linePaint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(withAlpha(color, a * strength), withAlpha(color, a * strength * 0.7f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            val half = thick * density / 2f
            canvas.drawRect(0f, y - half, right, y + half, linePaint)
        }
        linePaint.shader = null
    }

    private fun withAlpha(color: Int, a: Float) =
        Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun ease(now: Float, from: Float, length: Float): Float {
        val x = ((now - from) / length).coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x) * (1f - x)
    }

    private companion object {
        const val ANIM_MS = 700f
        /** Grosimea în dp și opacitatea fiecărui strat al liniei de jos: miezul, apoi haloul. */
        val GLOW = listOf(8f to 0.06f, 4.5f to 0.12f, 1.4f to 0.95f)
    }
}
