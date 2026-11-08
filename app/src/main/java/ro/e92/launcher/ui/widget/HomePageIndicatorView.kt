package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Indicatorul de jos, ca pe iOS: o casă plină pentru Home, apoi câte un punct pentru fiecare
 * pagină de dale. Cel curent e în accentul temei, celelalte gri, toate pline (nu contururi).
 */
class HomePageIndicatorView(context: Context, private val pages: Int) : View(context) {

    var current = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val dot = 7f * density
    private val house = 15f * density
    private val gap = 12f * density

    /** Colțuri ușor rotunjite pe toată casa, ca la simbolul house.fill. */
    private val rounded = CornerPathEffect(1.6f * density)
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_focus)
        pathEffect = rounded
    }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x73FFFFFF
        pathEffect = rounded
    }
    private val housePath = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = house + pages * (gap + dot)
        setMeasuredDimension(w.toInt() + 2, house.toInt() + 2)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        drawHouse(canvas, 0f, cy, if (current == 0) on else off)
        var x = house + gap + dot / 2f
        for (p in 1..pages) {
            canvas.drawCircle(x, cy, dot / 2f, if (current == p) on else off)
            x += gap + dot
        }
    }

    /** Casa plină: acoperiș cu streașină scurtă peste corp, ușa decupată. */
    private fun drawHouse(canvas: Canvas, left: Float, cy: Float, paint: Paint) {
        val s = house
        val top = cy - s / 2f
        fun x(f: Float) = left + s * f
        fun y(f: Float) = top + s * f
        housePath.reset()
        housePath.fillType = Path.FillType.EVEN_ODD
        housePath.moveTo(x(0.5f), y(0.02f))
        housePath.lineTo(x(1f), y(0.48f))
        housePath.lineTo(x(0.86f), y(0.48f))
        housePath.lineTo(x(0.86f), y(1f))
        housePath.lineTo(x(0.14f), y(1f))
        housePath.lineTo(x(0.14f), y(0.48f))
        housePath.lineTo(x(0f), y(0.48f))
        housePath.close()
        housePath.moveTo(x(0.41f), y(1f))
        housePath.lineTo(x(0.41f), y(0.66f))
        housePath.lineTo(x(0.59f), y(0.66f))
        housePath.lineTo(x(0.59f), y(1f))
        housePath.close()
        canvas.drawPath(housePath, paint)
    }
}
