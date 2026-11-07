package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import ro.e92.launcher.R

/** Arcul consumului din bord, 0-[MAX_L] l/100 km. Null = fără umplere. */
class ConsumptionArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var value: Float? = null
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 10f * density
    }
    private val track = context.getColor(R.color.stroke)
    private val fill = context.getColor(R.color.data_accent)
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val pad = paint.strokeWidth
        oval.set(pad, pad, width - pad, height - pad)
        paint.color = track
        canvas.drawArc(oval, START, SWEEP, false, paint)
        val v = value ?: return
        paint.color = fill
        canvas.drawArc(oval, START, SWEEP * (v / MAX_L).coerceIn(0f, 1f), false, paint)
    }

    private companion object {
        const val START = 135f
        const val SWEEP = 270f
        const val MAX_L = 25f
    }
}
