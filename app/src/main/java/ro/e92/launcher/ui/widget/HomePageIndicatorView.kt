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
    private val house = 17f * density
    private val gap = 12f * density

    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent_focus) }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
    /** Casa nefocusată: doar conturul, aceeași culoare ca punctele stinse. */
    private val offLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    /** Streașina, peste corp: linia acoperișului iese puțin în afara pereților. */
    private val roofLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val housePath = Path()
    private val roofPath = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = house + pages * (gap + dot)
        setMeasuredDimension(w.toInt() + 2, house.toInt() + 2)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        drawHouse(canvas, 0f, cy, current == 0)
        var x = house + gap + dot / 2f
        for (p in 1..pages) {
            canvas.drawCircle(x, cy, dot / 2f, if (current == p) on else off)
            x += gap + dot
        }
    }

    /**
     * Casa cu detalii: corp cu colțuri rotunjite, ușă decupată și acoperișul ca o
     * linie separată cu streașină care iese peste pereți. Pe Home e plină în accentul temei,
     * pe paginile de dale doar conturată.
     */
    private fun drawHouse(canvas: Canvas, left: Float, cy: Float, active: Boolean) {
        val s = house
        val top = cy - s / 2f
        fun x(f: Float) = left + s * f
        fun y(f: Float) = top + s * f

        // Corpul (pereți + fronton), cu ușa scoasă din el (even-odd).
        housePath.reset()
        housePath.fillType = Path.FillType.EVEN_ODD
        housePath.moveTo(x(0.5f), y(0.12f))
        housePath.lineTo(x(0.86f), y(0.46f))
        housePath.lineTo(x(0.86f), y(0.92f))
        housePath.quadTo(x(0.86f), y(1f), x(0.78f), y(1f))
        housePath.lineTo(x(0.22f), y(1f))
        housePath.quadTo(x(0.14f), y(1f), x(0.14f), y(0.92f))
        housePath.lineTo(x(0.14f), y(0.46f))
        housePath.close()
        // Ușa, cu partea de sus rotunjită.
        housePath.moveTo(x(0.41f), y(1f))
        housePath.lineTo(x(0.41f), y(0.72f))
        housePath.quadTo(x(0.41f), y(0.6f), x(0.5f), y(0.6f))
        housePath.quadTo(x(0.59f), y(0.6f), x(0.59f), y(0.72f))
        housePath.lineTo(x(0.59f), y(1f))
        housePath.close()

        // Acoperișul: linia de deasupra, mai lată decât casa.
        roofPath.reset()
        roofPath.moveTo(x(0.02f), y(0.5f))
        roofPath.lineTo(x(0.5f), y(0.03f))
        roofPath.lineTo(x(0.98f), y(0.5f))

        if (active) {
            canvas.drawPath(housePath, on)
            roofLine.color = on.color
        } else {
            canvas.drawPath(housePath, offLine)
            roofLine.color = offLine.color
        }
        canvas.drawPath(roofPath, roofLine)
    }
}
