package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Indicatorul de jos, ca pe iOS: o casă pentru ecranul Home, apoi câte un punct pentru
 * fiecare pagină de dale. Cel curent e în accentul temei, restul gri. Fără bară sub el.
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
    private val house = 14f * density
    private val gap = 12f * density

    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent_focus) }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
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

    /** O casă simplă: acoperiș în triunghi peste un pătrat, ca iconița Home de pe iOS. */
    private fun drawHouse(canvas: Canvas, left: Float, cy: Float, paint: Paint) {
        val s = house
        val top = cy - s / 2f
        housePath.reset()
        housePath.moveTo(left + s / 2f, top)                 // vârful acoperișului
        housePath.lineTo(left + s, top + s * 0.48f)
        housePath.lineTo(left + s * 0.86f, top + s * 0.48f)
        housePath.lineTo(left + s * 0.86f, top + s)
        housePath.lineTo(left + s * 0.14f, top + s)
        housePath.lineTo(left + s * 0.14f, top + s * 0.48f)
        housePath.lineTo(left, top + s * 0.48f)
        housePath.close()
        canvas.drawPath(housePath, paint)
    }
}
