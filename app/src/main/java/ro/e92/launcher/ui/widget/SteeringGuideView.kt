package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import ro.e92.launcher.R
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Drumul roților din spate, cu marșarierul băgat: patru arce concentrice, câte două pe
 * roată, în jurul centrului de viraj. Centrul e pe linia punții din spate, la raza
 * ampatament / tan(unghiul roților). Fiind concentrice, arcele rămân paralele.
 *
 * [steering]: 1 = stânga, -1 = dreapta, null = nu desenăm nimic.
 */
class SteeringGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var steering: Float? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 2.5f * density
        color = context.getColor(R.color.data_accent)
        alpha = LINE_ALPHA
        pathEffect = DashPathEffect(floatArrayOf(DASH_DP * density, GAP_DP * density), 0f)
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val turn = steering ?: return
        val cx = width / 2f
        val wheelAngle = Math.toRadians((abs(turn) * MAX_WHEEL_DEG).toDouble())
        val radius = if (abs(turn) < STRAIGHT_BELOW) 0f else (WHEELBASE_DP * density / tan(wheelAngle)).toFloat()
        // Volanul la dreapta (valoare negativă) pune centrul de viraj în dreapta mașinii.
        val side = if (turn < 0f) 1f else -1f
        for (wheel in intArrayOf(-1, 1)) {
            for (edge in intArrayOf(-1, 1)) {
                val offset = (wheel * HALF_TRACK_DP + edge * HALF_WHEEL_DP) * density
                if (radius == 0f) drawStraight(cx + offset) else drawArc(cx, radius, side, offset)
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun drawStraight(x: Float) {
        path.rewind()
        path.moveTo(x, TOP_DP * density)
        path.lineTo(x, height.toFloat())
    }

    private fun drawArc(cx: Float, radius: Float, side: Float, offset: Float) {
        val centerX = cx + side * radius
        val axleY = AXLE_DP * density
        val top = TOP_DP * density
        val bottom = height.toFloat()
        val r = radius - side * offset
        var angle = asin(((top - axleY) / r).coerceIn(0f, 1f))
        val step = STEP_DP * density / r
        path.rewind()
        var first = true
        while (angle < MAX_ARC_RAD) {
            val x = centerX - side * r * cos(angle)
            val y = axleY + r * sin(angle)
            if (first) path.moveTo(x, y) else path.lineTo(x, y)
            first = false
            if (y > bottom) break
            angle += step
        }
    }

    private companion object {
        /** Spatele mașinii și puntea din spate, în scenă. */
        const val TOP_DP = 250f
        const val AXLE_DP = 200f
        /** Mașina desenată are 253 dp pentru 4,6 m: E92 are ampatament de 2,76 m. */
        const val WHEELBASE_DP = 152f
        /** Unghiul roților la volan maxim: estimat, de reglat după proba din mașină. */
        const val MAX_WHEEL_DEG = 35f
        const val STRAIGHT_BELOW = 0.005f
        const val HALF_TRACK_DP = 62f
        const val HALF_WHEEL_DP = 9f
        const val STEP_DP = 3f
        const val MAX_ARC_RAD = 1.57f
        const val DASH_DP = 11f
        const val GAP_DP = 8f
        const val LINE_ALPHA = 200
    }
}
