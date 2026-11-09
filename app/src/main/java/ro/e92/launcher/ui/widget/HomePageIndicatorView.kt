package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Indicatorul de jos, ca pe iOS: casa (din setul de iconițe ales de șofer) pentru Home,
 * apoi câte un punct pentru fiecare pagină de dale. Cel curent e în accentul temei, restul gri.
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

    private val accent = ContextCompat.getColor(context, R.color.accent_focus)
    private val dim = 0x73FFFFFF
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim }
    private val houseIcon = ContextCompat.getDrawable(context, R.drawable.ic_home_house)!!.mutate()
    private val accentFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
    private val dimFilter = PorterDuffColorFilter(dim, PorterDuff.Mode.SRC_IN)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = house + pages * (gap + dot)
        setMeasuredDimension(w.toInt() + 2, house.toInt() + 2)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        houseIcon.colorFilter = if (current == 0) accentFilter else dimFilter
        houseIcon.setBounds(0, (cy - house / 2f).toInt(), house.toInt(), (cy + house / 2f).toInt())
        houseIcon.draw(canvas)
        var x = house + gap + dot / 2f
        for (p in 1..pages) {
            canvas.drawCircle(x, cy, dot / 2f, if (current == p) on else off)
            x += gap + dot
        }
    }
}
