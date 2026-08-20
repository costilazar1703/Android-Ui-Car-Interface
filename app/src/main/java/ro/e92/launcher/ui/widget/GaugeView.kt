package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cadran BMW: fundal negru, gradații albe subțiri, ac roșu.
 *
 * Un singur [onDraw], zero alocări în el — toate Paint/Path/RectF și coordonatele
 * gradațiilor sunt pre-calculate în constructor și în [onSizeChanged].
 *
 * Notă despre LAYER_TYPE_HARDWARE: NU e setat intenționat. Un layer hardware ajută
 * când conținutul e static și se transformă (translate/alpha). Aici conținutul se
 * schimbă la fiecare frame, deci layer-ul ar însemna re-render în textură + blit,
 * adică fix costul pe care încercăm să-l evităm.
 *
 * Rata de update: textul numeric se schimbă doar când vine o valoare nouă din
 * repository (10 Hz), acul interpolează separat, plafonat la ~30 fps.
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ------------------------------------------------------------------ config
    private var maxValue = 260f
    private var majorTickStep = 20f
    private var redlineFrom = Float.MAX_VALUE
    private var caption = ""
    private var unit = ""
    private var displayDivisor = 1f
    private var decimals = 0

    // ------------------------------------------------------------------- stare
    private var targetFraction = 0f
    private var drawnFraction = 0f
    private var animating = false
    private var valueText = NO_VALUE

    // ------------------------------------------------------------------ pictură
    private val arcBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = 0xFF262626.toInt()
    }
    private val arcValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = 0xFFFFFFFF.toInt()
    }
    private val arcRedlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = 0xFFE60000.toInt()
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE60000.toInt()
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0A0A0A.toInt()
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A8A.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A8A.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }

    private val oval = RectF()
    private val needlePath = Path()
    /** x0,y0,x1,y1 per gradație — desenate dintr-un singur drawLines. */
    private var tickLines = FloatArray(0)

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var valueBaseline = 0f
    private var unitBaseline = 0f
    private var captionBaseline = 0f

    private val stepRunnable = Runnable { stepAnimation() }

    // -------------------------------------------------------------------- API

    /**
     * @param displayDivisor împarte valoarea brută înainte de afișare
     *                       (1000 pentru "x1000 rpm").
     */
    fun configure(
        caption: String,
        unit: String,
        maxValue: Float,
        majorTickStep: Float,
        redlineFrom: Float = Float.MAX_VALUE,
        displayDivisor: Float = 1f,
        decimals: Int = 0
    ) {
        this.caption = caption
        this.unit = unit
        this.maxValue = maxValue
        this.majorTickStep = majorTickStep
        this.redlineFrom = redlineFrom
        this.displayDivisor = displayDivisor
        this.decimals = decimals
        if (width > 0) buildGeometry(width, height)
        invalidate()
    }

    /** Valoare cunoscută. Textul se schimbă acum; acul se duce lin până acolo. */
    fun setValue(raw: Float) {
        val clamped = raw.coerceIn(0f, maxValue)
        targetFraction = if (maxValue > 0f) clamped / maxValue else 0f
        valueText = formatValue(clamped)
        startAnimation()
    }

    /** Nicio sursă nu livrează valoarea asta — acul cade la zero, textul devine "--". */
    fun setUnknown() {
        targetFraction = 0f
        valueText = NO_VALUE
        startAnimation()
    }

    // ------------------------------------------------------------------ desen

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildGeometry(w, h)
    }

    private fun buildGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        val size = min(w, h).toFloat()
        val strokeWidth = size * 0.055f
        arcBgPaint.strokeWidth = strokeWidth
        arcValuePaint.strokeWidth = strokeWidth
        arcRedlinePaint.strokeWidth = strokeWidth
        tickPaint.strokeWidth = (size * 0.008f).coerceAtLeast(1f)

        centerX = w / 2f
        centerY = h / 2f
        radius = size / 2f - strokeWidth / 2f - size * 0.02f

        oval.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        valuePaint.textSize = size * 0.30f
        unitPaint.textSize = size * 0.085f
        captionPaint.textSize = size * 0.085f

        // Baseline-uri pre-calculate: fără measureText/getFontMetrics în onDraw.
        valueBaseline = centerY + valuePaint.textSize * 0.30f
        unitBaseline = valueBaseline + unitPaint.textSize * 1.5f
        captionBaseline = centerY - valuePaint.textSize * 0.55f

        buildTicks()
        buildNeedle(size)
    }

    private fun buildTicks() {
        if (maxValue <= 0f || majorTickStep <= 0f) {
            tickLines = FloatArray(0)
            return
        }
        val count = (maxValue / majorTickStep).toInt() + 1
        val lines = FloatArray(count * 4)
        val inner = radius - radius * 0.16f
        val outer = radius - radius * 0.075f
        for (i in 0 until count) {
            val frac = (i * majorTickStep) / maxValue
            val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * frac).toDouble())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            lines[i * 4] = centerX + inner * cosA
            lines[i * 4 + 1] = centerY + inner * sinA
            lines[i * 4 + 2] = centerX + outer * cosA
            lines[i * 4 + 3] = centerY + outer * sinA
        }
        tickLines = lines
    }

    private fun buildNeedle(size: Float) {
        // Ac construit orientat spre 0° (dreapta); rotația se face pe canvas.
        val length = radius * 0.80f
        val halfBase = size * 0.018f
        needlePath.reset()
        needlePath.moveTo(centerX + length, centerY)
        needlePath.lineTo(centerX, centerY - halfBase)
        needlePath.lineTo(centerX - radius * 0.14f, centerY)
        needlePath.lineTo(centerX, centerY + halfBase)
        needlePath.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return

        canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE, false, arcBgPaint)

        if (drawnFraction > 0f) {
            canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE * drawnFraction, false, arcValuePaint)
        }

        if (redlineFrom < maxValue) {
            val redStart = redlineFrom / maxValue
            canvas.drawArc(
                oval,
                START_ANGLE + SWEEP_ANGLE * redStart,
                SWEEP_ANGLE * (1f - redStart),
                false,
                arcRedlinePaint
            )
        }

        if (tickLines.isNotEmpty()) canvas.drawLines(tickLines, tickPaint)

        canvas.drawText(caption, centerX, captionBaseline, captionPaint)
        canvas.drawText(valueText, centerX, valueBaseline, valuePaint)
        canvas.drawText(unit, centerX, unitBaseline, unitPaint)

        val save = canvas.save()
        canvas.rotate(START_ANGLE + SWEEP_ANGLE * drawnFraction, centerX, centerY)
        canvas.drawPath(needlePath, needlePaint)
        canvas.restoreToCount(save)

        canvas.drawCircle(centerX, centerY, radius * 0.055f, hubPaint)
    }

    // --------------------------------------------------------------- animație

    private fun startAnimation() {
        if (animating) return
        animating = true
        stepAnimation()
    }

    private fun stepAnimation() {
        val diff = targetFraction - drawnFraction
        if (abs(diff) < MIN_DELTA) {
            drawnFraction = targetFraction
            animating = false
            invalidate()
            return
        }
        // Smoothing exponențial: acul nu tremură la zgomotul din CAN, dar nici
        // nu rămâne vizibil în urmă la accelerări.
        drawnFraction += diff * SMOOTHING
        invalidate()
        postDelayed(stepRunnable, FRAME_INTERVAL_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(stepRunnable)
        animating = false
        super.onDetachedFromWindow()
    }

    private fun formatValue(value: Float): String {
        val scaled = value / displayDivisor
        return if (decimals <= 0) {
            scaled.roundToInt().toString()
        } else {
            val factor = if (decimals == 1) 10 else 100
            val rounded = (scaled * factor).roundToInt()
            "${rounded / factor}.${(rounded % factor).toString().padStart(decimals, '0')}"
        }
    }

    private companion object {
        /** 150° … 390°: deschidere jos, ca la ceasurile BMW. */
        const val START_ANGLE = 150f
        const val SWEEP_ANGLE = 240f

        const val SMOOTHING = 0.22f
        const val MIN_DELTA = 0.0015f
        /** ~30 fps: limita din bugetul de performanță pentru animația de ac. */
        const val FRAME_INTERVAL_MS = 33L

        const val NO_VALUE = "--"
    }
}
