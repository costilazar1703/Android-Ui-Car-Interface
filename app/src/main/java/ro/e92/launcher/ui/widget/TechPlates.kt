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
 * Plăcile din spatele informațiilor permanente: textură diagonală fină, tăiată
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

/**
 * Bara de jos, pe toate ecranele: mijlocul e neted, cu telefonul, pagina și muzica;
 * capetele sunt plăci ca aceea de sus din stânga, tăiate oblic, pentru Home și Back.
 * Treapta din mijloc, cu linia caldă, marchează pagina curentă.
 */
class BottomBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.glow_core)
    private val base = Paint()
    private val hatchLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private val hatchRight = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private val plateFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x70000000 }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (LINE_ALPHA shl 24) or 0xFFFFFF
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density }
    private val glowLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density }
    private val glowRight = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density }
    private val shape = Path()
    private val edge = Path()
    private val plateLeft = Path()
    private val plateRight = Path()
    private val cuts = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val width = w.toFloat()
        val height = h.toFloat()
        val center = width / 2f
        val half = STEP_HALF_DP * density
        val slant = STEP_SLANT_DP * density
        val stepY = STEP_DP * density
        // Marginea de sus: joasă la capete, ridicată la mijloc.
        edge.rewind()
        edge.moveTo(0f, stepY)
        edge.lineTo(center - half - slant, stepY)
        edge.lineTo(center - half, density / 2f)
        edge.lineTo(center + half, density / 2f)
        edge.lineTo(center + half + slant, stepY)
        edge.lineTo(width, stepY)
        shape.rewind()
        shape.addPath(edge)
        shape.lineTo(width, height)
        shape.lineTo(0f, height)
        shape.close()
        base.shader = LinearGradient(
            0f, 0f, 0f, height, 0xB0060606.toInt(), 0xF0060606.toInt(), Shader.TileMode.CLAMP
        )
        // Plăcile de la capete, tăiate oblic ca aceea de sus.
        val cutTop = PLATE_DP * density
        val cutBottom = cutTop - (height - stepY) * 0.8f
        plateLeft.rewind()
        plateLeft.moveTo(0f, stepY)
        plateLeft.lineTo(cutTop, stepY)
        plateLeft.lineTo(cutBottom, height)
        plateLeft.lineTo(0f, height)
        plateLeft.close()
        plateRight.rewind()
        plateRight.moveTo(width, stepY)
        plateRight.lineTo(width - cutTop, stepY)
        plateRight.lineTo(width - cutBottom, height)
        plateRight.lineTo(width, height)
        plateRight.close()
        cuts.rewind()
        cuts.moveTo(cutTop, stepY)
        cuts.lineTo(cutBottom, height)
        cuts.moveTo(width - cutTop, stepY)
        cuts.lineTo(width - cutBottom, height)
        hatchLeft.shader = fadeX(0f, cutTop, 0xFFFFFF, PLATE_HATCH_ALPHA)
        hatchRight.shader = fadeX(width, width - cutTop, 0xFFFFFF, PLATE_HATCH_ALPHA)
        glowLeft.shader = fadeX(0f, cutTop, accent, 0xB0)
        glowRight.shader = fadeX(width, width - cutTop, accent, 0xB0)
        glow.shader = LinearGradient(
            center - half, 0f, center + half, 0f,
            intArrayOf(accent and 0xFFFFFF, (0xC0 shl 24) or (accent and 0xFFFFFF), accent and 0xFFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        canvas.drawPath(shape, base)
        canvas.drawPath(plateLeft, plateFill)
        canvas.drawPath(plateRight, plateFill)
        canvas.save()
        canvas.clipPath(plateLeft)
        hatch(canvas, width, height, HATCH_GAP_DP * density, hatchLeft)
        canvas.restore()
        canvas.save()
        canvas.clipPath(plateRight)
        hatch(canvas, width, height, HATCH_GAP_DP * density, hatchRight)
        canvas.restore()
        canvas.drawPath(edge, line)
        canvas.drawPath(cuts, line)
        val half = STEP_HALF_DP * density
        val center = width / 2f
        val cut = PLATE_DP * density
        canvas.drawLine(center - half, density, center + half, density, glow)
        canvas.drawLine(0f, height - density, cut, height - density, glowLeft)
        canvas.drawLine(width - cut, height - density, width, height - density, glowRight)
    }

    private companion object {
        const val STEP_DP = 8f
        const val STEP_HALF_DP = 200f
        const val STEP_SLANT_DP = 14f
        const val PLATE_DP = 128f
        const val PLATE_HATCH_ALPHA = 0x48
    }
}
