package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * Senzorii din spate: patru zone egale, câte una pentru fiecare senzor, de la
 * stânga la dreapta. Fundalul e transparent, doar cercuri subțiri și segmente mici
 * și stinse. Când te apropii apar liniuțe colorate, din exterior spre mașină: rare
 * departe, dese pe ultimii 50 cm.
 *
 * [distancesCm] rămâne null cât nu avem date reale: se vede doar fundalul, fără să
 * arate nici „liber", nici „obstacol".
 *
 * Distanța afișată urmărește valoarea primită cu viteză limitată, ca liniile să se
 * aprindă pe rând. Animația merge pe ceasul ei: pe unitate scara animațiilor e 0.
 */
class ParkSensorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var distancesCm: List<Int>? = null
        set(value) {
            field = value
            invalidate()
        }

    private val shown = FloatArray(ZONES) { FAR_CM.toFloat() }
    private var lastFrame = 0L

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val oval = RectF()

    /** Raza mijlocului fiecărei linii, de la mașină spre exterior. */
    private val radii = FloatArray(THRESHOLDS_CM.size)
    private val widths = FloatArray(THRESHOLDS_CM.size)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        var r = INNER_RADIUS_DP
        for (line in THRESHOLDS_CM.indices) {
            val fine = line < FINE_LINES
            val width = if (fine) FINE_WIDTH_DP else WIDE_WIDTH_DP
            radii[line] = (r + width / 2f) * density
            widths[line] = width * density
            r += width + if (fine) FINE_GAP_DP else WIDE_GAP_DP
        }
    }

    /** Unghiurile cresc în sensul acelor; 90 e în jos. Zona 0 e cea din stânga. */
    private fun zoneStart(zone: Int): Float = 90f + TOTAL_SWEEP / 2f - (zone + 1) * ZONE_SWEEP - zone * ZONE_GAP

    override fun onDraw(canvas: Canvas) {
        val moving = advance()
        val cx = width / 2f
        val cy = CENTER_Y_DP * density
        var pulsing = false

        // Cercurile subțiri din spate.
        stroke.color = RING
        stroke.strokeWidth = density
        for (i in 0 until RINGS) {
            setOval(cx, cy, (RING_FIRST_DP + i * RING_STEP_DP) * density)
            canvas.drawArc(oval, RING_START, RING_SWEEP, false, stroke)
        }

        for (zone in 0 until ZONES) {
            val distance = shown[zone]
            val start = zoneStart(zone)

            // Segmentele stinse; se retrag când apar liniile colorate.
            val idle = if (distance >= THRESHOLDS_CM.last()) 1f else IDLE_WHEN_ACTIVE
            for (line in IDLE_LINES) {
                setOval(cx, cy, radii[line])
                drawLine(canvas, start, IDLE, IDLE_WIDTH_DP * density, idle)
            }

            for (line in THRESHOLDS_CM.indices) {
                val threshold = THRESHOLDS_CM[line]
                // Se aprinde treptat în ultimii centimetri dinaintea pragului.
                val amount = ((threshold - distance) / LINE_FADE_CM + 1f).coerceIn(0f, 1f)
                if (amount <= 0f) continue
                val closest = line == 0 || distance > THRESHOLDS_CM[line - 1]
                var opacity = amount
                if (closest && distance <= PULSE_BELOW_CM) {
                    pulsing = true
                    opacity *= PULSE_MIN + (1f - PULSE_MIN) * (0.5f + 0.5f * sin(SystemClock.uptimeMillis() / PULSE_PERIOD))
                }
                val color = colorOf(threshold.toFloat())
                setOval(cx, cy, radii[line])
                // Strălucire doar pe linia cea mai apropiată: e cea care contează.
                if (closest) drawLine(canvas, start, color, widths[line] * GLOW_SCALE, GLOW_OPACITY * opacity)
                drawLine(canvas, start, color, widths[line], opacity)
            }
        }
        if (moving || pulsing) postInvalidateOnAnimation()
    }

    private fun setOval(cx: Float, cy: Float, radius: Float) {
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    private fun drawLine(canvas: Canvas, start: Float, color: Int, width: Float, opacity: Float) {
        stroke.color = color
        stroke.alpha = (Color.alpha(color) * opacity).toInt()
        stroke.strokeWidth = width
        canvas.drawArc(oval, start, ZONE_SWEEP, false, stroke)
    }

    /** Duce distanța afișată spre cea primită; @return true cât mai are de mers. */
    private fun advance(): Boolean {
        val now = SystemClock.uptimeMillis()
        val step = if (lastFrame == 0L) 0f else (now - lastFrame).coerceAtMost(MAX_FRAME_MS) * SPEED_CM_PER_MS
        lastFrame = now
        var moving = false
        val current = distancesCm
        for (zone in 0 until ZONES) {
            val target = (current?.getOrNull(zone) ?: FAR_CM).toFloat()
            val gap = target - shown[zone]
            if (abs(gap) <= step) {
                shown[zone] = target
            } else {
                shown[zone] += if (gap > 0) step else -step
                moving = true
            }
        }
        if (!moving) lastFrame = 0L
        return moving
    }

    private fun colorOf(cm: Float): Int = when {
        cm <= RED_BELOW_CM -> RED
        cm <= ORANGE_BELOW_CM -> ORANGE
        else -> AMBER
    }

    private companion object {
        const val ZONES = 4
        const val FAR_CM = 170

        /** Pragul fiecărei linii, de la mașină spre exterior. Primele sunt cele fine. */
        val THRESHOLDS_CM = intArrayOf(6, 11, 16, 22, 28, 35, 42, 50, 60, 80, 110, 150)
        const val FINE_LINES = 8
        const val LINE_FADE_CM = 3f

        const val RED_BELOW_CM = 16f
        const val ORANGE_BELOW_CM = 35f
        const val PULSE_BELOW_CM = 16f
        const val PULSE_PERIOD = 90f
        const val PULSE_MIN = 0.65f
        const val GLOW_SCALE = 3f
        const val GLOW_OPACITY = 0.22f

        const val SPEED_CM_PER_MS = 0.12f
        const val MAX_FRAME_MS = 50L

        /** Centrul e în mașină; raza trece de colțurile barei, ca zonele să iasă întregi. */
        const val CENTER_Y_DP = 150f
        const val INNER_RADIUS_DP = 128f
        const val FINE_WIDTH_DP = 2.6f
        const val FINE_GAP_DP = 2.4f
        const val WIDE_WIDTH_DP = 5.5f
        const val WIDE_GAP_DP = 3f

        const val ZONE_SWEEP = 24f
        const val ZONE_GAP = 2.5f
        const val TOTAL_SWEEP = ZONES * ZONE_SWEEP + (ZONES - 1) * ZONE_GAP

        const val RINGS = 5
        const val RING_FIRST_DP = 122f
        const val RING_STEP_DP = 22f
        const val RING_START = 14f
        const val RING_SWEEP = 152f
        val RING = Color.argb(26, 214, 218, 226)

        val IDLE_LINES = intArrayOf(1, 4, 7, 9, 11)
        const val IDLE_WIDTH_DP = 4f
        const val IDLE_WHEN_ACTIVE = 0.4f
        val IDLE = Color.argb(46, 214, 200, 180)

        val AMBER = Color.rgb(226, 150, 44)
        val ORANGE = Color.rgb(234, 96, 26)
        val RED = Color.rgb(228, 30, 18)
    }
}
