package ro.e92.launcher.ui.widget

import ro.e92.launcher.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cadran cromat: fața din `gauge_face` (inel cromat, inel portocaliu, metal
 * periat), peste care gradațiile, cifrele, zona roșie și acul se desenează în cod.
 *
 * **Partea statică se compune o singură dată.** Fața, gradațiile, cifrele și zona
 * roșie nu se schimbă cât timp cadranul are aceeași mărime, așa că în
 * [onSizeChanged] se desenează toate într-un bitmap de mărimea view-ului. Poza
 * sursă (640×640) se eliberează imediat după. [onDraw] rămâne: un bitmap, textul
 * valorii și acul — zero alocări, ca înainte.
 *
 * De ce nu LAYER_TYPE_HARDWARE: acul se mișcă la fiecare cadru, deci layer-ul
 * s-ar re-randa mereu. Bitmap-ul static e exact partea care merită păstrată.
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
    private var minValue = 0f
    /** Unghiul lui [minValue] și cât acoperă scala, în grade Canvas (0° = ora 3). */
    private var startAngle = DEFAULT_START_ANGLE
    private var sweepAngle = DEFAULT_SWEEP_ANGLE
    private var labelSizeRatio = DEFAULT_LABEL_SIZE
    private var maxValue = 260f
    private var majorTickStep = 20f
    private var minorPerMajor = 2
    private var labelStep = 40f
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
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = NEEDLE
    }
    /** Halou moale sub ac: același contur, mai lat și translucid. Fără BlurMaskFilter,
     *  care ar forța randare software. */
    private val needleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = NEEDLE and 0x00FFFFFF or 0x55000000
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0C0C0C.toInt() }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = context.resources.getFont(R.font.inter_display_light)
        fontFeatureSettings = "tnum"
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9A9A9A.toInt()
        textAlign = Paint.Align.CENTER
        typeface = context.resources.getFont(R.font.inter_medium)
        letterSpacing = 0.12f
    }

    /** Pe cadranele mici, cifrele nu coboară sub mărimea asta, oricât de mic e cercul. */
    private val minTextPx = 10f * resources.displayMetrics.scaledDensity
    private val needlePath = Path()
    private var staticLayer: Bitmap? = null

    private var centerX = 0f
    private var centerY = 0f
    /** Raza interioară a feței, în interiorul inelului portocaliu. */
    private var dial = 0f
    private var valueBaseline = 0f
    private var unitBaseline = 0f

    private val stepRunnable = Runnable { stepAnimation() }

    // -------------------------------------------------------------------- API

    /**
     * @param minorPerMajor câte intervale mici are o diviziune mare
     * @param labelStep din cât în cât se scriu cifrele; implicit la fiecare
     *                  gradație mare
     * @param displayDivisor împarte valoarea brută înainte de afișare
     *                       (1000 pentru "x1000 rpm"); se aplică și cifrelor
     */
    fun configure(
        caption: String,
        unit: String,
        maxValue: Float,
        majorTickStep: Float,
        minValue: Float = 0f,
        minorPerMajor: Int = 2,
        labelStep: Float = majorTickStep,
        startAngle: Float = DEFAULT_START_ANGLE,
        sweepAngle: Float = DEFAULT_SWEEP_ANGLE,
        labelSizeRatio: Float = DEFAULT_LABEL_SIZE,
        redlineFrom: Float = Float.MAX_VALUE,
        displayDivisor: Float = 1f,
        decimals: Int = 0
    ) {
        this.caption = caption
        this.unit = unit
        this.minValue = minValue
        this.maxValue = maxValue
        this.majorTickStep = majorTickStep
        this.minorPerMajor = minorPerMajor.coerceAtLeast(1)
        this.labelStep = labelStep
        this.startAngle = startAngle
        this.sweepAngle = sweepAngle
        this.labelSizeRatio = labelSizeRatio
        this.redlineFrom = redlineFrom
        this.displayDivisor = displayDivisor
        this.decimals = decimals
        if (width > 0) buildGeometry(width, height)
        invalidate()
    }

    /** Valoare cunoscută. Textul se schimbă acum; acul se duce lin până acolo. */
    fun setValue(raw: Float) {
        val clamped = raw.coerceIn(minValue, maxValue)
        targetFraction = if (maxValue > minValue) (clamped - minValue) / (maxValue - minValue) else 0f
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
        centerX = w / 2f
        centerY = h / 2f
        dial = size / 2f * DIAL_RATIO

        valuePaint.textSize = size * 0.16f
        unitPaint.textSize = max(size * 0.042f, minTextPx)
        valueBaseline = centerY + dial * 0.58f
        unitBaseline = valueBaseline + unitPaint.textSize * 1.9f

        buildNeedle()
        hubPaint.shader = RadialGradient(
            centerX - dial * 0.03f, centerY - dial * 0.03f, dial * 0.09f,
            intArrayOf(0xFFF2F2F2.toInt(), 0xFF8C8C8C.toInt(), 0xFF2A2A2A.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        staticLayer?.recycle()
        staticLayer = buildStaticLayer(w, h, size)
    }

    /** Fața + zona roșie + gradații + cifre + titlu, compuse o dată. */
    private fun buildStaticLayer(w: Int, h: Int, size: Float): Bitmap? {
        val out = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return null
        val c = Canvas(out)

        decodeFace(size.toInt())?.let { face ->
            val half = size / 2f
            val dst = Rect(
                (centerX - half).roundToInt(), (centerY - half).roundToInt(),
                (centerX + half).roundToInt(), (centerY + half).roundToInt()
            )
            c.drawBitmap(face, null, dst, bitmapPaint)
            face.recycle()
        }

        if (maxValue <= minValue || majorTickStep <= 0f) return out

        // Zona roșie: o bandă îngustă chiar pe marginea gradațiilor.
        if (redlineFrom < maxValue) {
            val r = dial * TICK_OUTER - dial * 0.015f
            val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dial * 0.03f
                color = REDLINE
            }
            val oval = RectF(centerX - r, centerY - r, centerX + r, centerY + r)
            val from = (redlineFrom - minValue) / (maxValue - minValue)
            c.drawArc(oval, startAngle + sweepAngle * from, sweepAngle * (1f - from), false, band)
        }

        val major = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (size * 0.011f).coerceAtLeast(1.5f)
            strokeCap = Paint.Cap.BUTT
        }
        val minor = Paint(major).apply { strokeWidth = (size * 0.005f).coerceAtLeast(1f) }
        val minorStep = majorTickStep / minorPerMajor
        val count = ((maxValue - minValue) / minorStep).roundToInt()
        for (i in 0..count) {
            val value = minValue + i * minorStep
            val isMajor = i % minorPerMajor == 0
            val paint = if (isMajor) major else minor
            paint.color = when {
                value >= redlineFrom -> REDLINE
                isMajor -> TICK_MAJOR
                else -> TICK_MINOR
            }
            val inner = dial * if (isMajor) TICK_MAJOR_INNER else TICK_MINOR_INNER
            val a = angleOf(value)
            val cosA = cos(a)
            val sinA = sin(a)
            c.drawLine(
                centerX + inner * cosA, centerY + inner * sinA,
                centerX + dial * TICK_OUTER * cosA, centerY + dial * TICK_OUTER * sinA,
                paint
            )
        }

        val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = context.resources.getFont(R.font.inter_medium)
            textSize = max(size * labelSizeRatio, minTextPx)
            fontFeatureSettings = "tnum"
        }
        // Centrul optic al cifrei, nu baseline-ul: cifrele stau pe cerc.
        val halfCap = labels.textSize * 0.36f
        if (labelStep > 0f) {
            var value = minValue
            while (value <= maxValue + 0.01f) {
                labels.color = if (value >= redlineFrom) REDLINE else LABEL
                val a = angleOf(value)
                val r = dial * LABEL_RADIUS
                c.drawText(
                    labelText(value / displayDivisor),
                    centerX + r * cos(a), centerY + r * sin(a) + halfCap, labels
                )
                value += labelStep
            }
        }

        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = context.resources.getFont(R.font.inter_semibold)
            textSize = max(size * 0.045f, minTextPx)
            letterSpacing = 0.2f
            color = CAPTION
        }
        c.drawText(caption, centerX, centerY - dial * 0.38f, captionPaint)
        return out
    }

    private fun decodeFace(target: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            // 640 px e destul pentru orice cadran de pe un ecran de 480 px înălțime;
            // pe ecrane mici se decodează la jumătate.
            inSampleSize = if (target <= FACE_SOURCE_PX / 2) 2 else 1
        }
        return runCatching { BitmapFactory.decodeResource(resources, R.drawable.gauge_face, opts) }
            .getOrNull()
    }

    private fun angleOf(value: Float): Float = Math.toRadians(
        (startAngle + sweepAngle * (value - minValue) / (maxValue - minValue)).toDouble()
    ).toFloat()

    /** „1" pentru valori întregi, „0.5" pentru restul — scala de turbo are jumătăți. */
    private fun labelText(v: Float): String {
        val whole = v.roundToInt()
        return if (abs(v - whole) < 0.01f) whole.toString() else ((v * 10).roundToInt() / 10f).toString()
    }

    private fun buildNeedle() {
        // Ac construit orientat spre 0° (dreapta); rotația se face pe canvas.
        // Subțire și ascuțit, cu o coadă scurtă dincolo de butuc.
        val length = dial * NEEDLE_LENGTH
        val halfBase = dial * 0.028f
        needlePath.reset()
        needlePath.moveTo(centerX + length, centerY - dial * 0.004f)
        needlePath.lineTo(centerX + length, centerY + dial * 0.004f)
        needlePath.lineTo(centerX, centerY + halfBase)
        needlePath.lineTo(centerX - dial * 0.16f, centerY + halfBase * 0.6f)
        needlePath.lineTo(centerX - dial * 0.16f, centerY - halfBase * 0.6f)
        needlePath.lineTo(centerX, centerY - halfBase)
        needlePath.close()
        needleGlowPaint.strokeWidth = dial * 0.03f
    }

    override fun onDraw(canvas: Canvas) {
        if (dial <= 0f) return

        staticLayer?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }

        canvas.drawText(valueText, centerX, valueBaseline, valuePaint)
        canvas.drawText(unit, centerX, unitBaseline, unitPaint)

        val save = canvas.save()
        canvas.rotate(startAngle + sweepAngle * drawnFraction, centerX, centerY)
        canvas.drawPath(needlePath, needleGlowPaint)
        canvas.drawPath(needlePath, needlePaint)
        canvas.restoreToCount(save)

        canvas.drawCircle(centerX, centerY, dial * 0.09f, hubPaint)
        canvas.drawCircle(centerX, centerY, dial * 0.035f, hubCapPaint)
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
        staticLayer?.recycle()
        staticLayer = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (staticLayer == null && width > 0) buildGeometry(width, height)
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
        /** 150° … 390°: deschidere jos. Bordul E9x are arce mai lungi — vezi DashboardScreen. */
        const val DEFAULT_START_ANGLE = 150f
        const val DEFAULT_SWEEP_ANGLE = 240f
        const val DEFAULT_LABEL_SIZE = 0.050f

        /** Inelul portocaliu din `gauge_face` e la ~0.83 din rază; interiorul începe sub el. */
        const val DIAL_RATIO = 0.80f
        const val FACE_SOURCE_PX = 640

        // Fracțiuni din raza interioară [dial].
        const val TICK_OUTER = 0.94f
        const val TICK_MAJOR_INNER = 0.80f
        const val TICK_MINOR_INNER = 0.87f
        const val LABEL_RADIUS = 0.63f
        const val NEEDLE_LENGTH = 0.90f

        val NEEDLE = 0xFFFF6B00.toInt()
        val REDLINE = 0xFFE01010.toInt()
        val TICK_MAJOR = 0xFFF2F2F2.toInt()
        val TICK_MINOR = 0xB3D0D0D0.toInt()
        val LABEL = 0xE6FFFFFF.toInt()
        val CAPTION = 0xFFB0B0B0.toInt()

        const val SMOOTHING = 0.22f
        const val MIN_DELTA = 0.0015f
        /** ~30 fps: limita din bugetul de performanță pentru animația de ac. */
        const val FRAME_INTERVAL_MS = 33L

        const val NO_VALUE = "--"
    }
}
