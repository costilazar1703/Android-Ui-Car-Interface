package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Placa din spatele autonomiei: textură diagonală fină, tăiată
 * oblic, și linii subțiri care continuă după tăietură. Desenate în cod, ca să
 * rămână clare la orice lățime și să nu mai adauge un bitmap în memorie.
 */
private const val HATCH_ALPHA = 0x30
private const val LINE_ALPHA = 0x48
private const val HATCH_GAP_DP = 5f

private fun hatch(canvas: Canvas, width: Float, height: Float, gap: Float, paint: Paint) {
    var x = -height
    while (x < width) {
        canvas.drawLine(x, height, x + height, 0f, paint)
        x += gap
    }
}

private fun fadeX(from: Float, to: Float, color: Int, alpha: Int): LinearGradient {
    val rgb = color and 0xFFFFFF
    return LinearGradient(from, 0f, to, 0f, (alpha shl 24) or rgb, rgb, Shader.TileMode.CLAMP)
}

/** Sub autonomie, sus în stânga: textura se stinge spre tăietura oblică. */
class TopPlateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.glow_core)
    private val base = Paint()
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density }
    private val plate = Path()
    private val outline = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val width = w.toFloat()
        val height = h.toFloat()
        val cutTop = width * 0.78f
        val cutBottom = cutTop - height * 0.8f
        plate.rewind()
        plate.moveTo(0f, 0f)
        plate.lineTo(cutTop, 0f)
        plate.lineTo(cutBottom, height)
        plate.lineTo(0f, height)
        plate.close()
        outline.rewind()
        outline.moveTo(cutBottom, height - density / 2f)
        outline.lineTo(cutTop, density / 2f)
        outline.lineTo(width, density / 2f)
        base.shader = fadeX(0f, cutTop, 0x000000, 0x90)
        hatchPaint.shader = fadeX(0f, cutTop, 0xFFFFFF, HATCH_ALPHA)
        line.shader = LinearGradient(
            cutBottom, 0f, width, 0f, (LINE_ALPHA shl 24) or 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        glow.shader = fadeX(0f, cutBottom, accent, 0xB0)
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        canvas.drawPath(plate, base)
        canvas.save()
        canvas.clipPath(plate)
        hatch(canvas, width, height, HATCH_GAP_DP * density, hatchPaint)
        canvas.restore()
        canvas.drawPath(outline, line)
        canvas.drawLine(0f, height - density, width, height - density, glow)
    }
}
