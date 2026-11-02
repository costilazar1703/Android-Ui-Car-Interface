package ro.e92.launcher.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import ro.e92.launcher.R
import ro.e92.launcher.core.Services

/**
 * Indicatorul de pagină al grilei principale: câte un grup pe pagină, fiecare
 * împărțit în atâtea segmente subțiri câte dale are pagina. Segmentul dalei
 * focusate e portocaliu, restul sunt piste stinse - se vede dintr-o privire și
 * pagina, și dala din ea.
 *
 * Lumina nu sare de la un segment la altul: poziția desenată e continuă și
 * fiecare segment se aprinde după cât de aproape e de ea, deci portocaliul
 * alunecă pe pistă când rotești, inclusiv peste golul dintre pagini.
 */
class PageIndicatorView(context: Context, private val pageCount: Int) : View(context) {

    private val density = resources.displayMetrics.density
    private val cellW = CELL_DP * density
    private val cellGap = CELL_GAP_DP * density
    private val segH = HEIGHT_DP * density
    private val pageGap = PAGE_GAP_DP * density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glow_core)
    }
    private val rect = RectF()

    private var perPage = DEFAULT_PER_PAGE

    /** Indexul desenat al dalei, continuu între două dale vecine. */
    private var drawn = 0f
    private var animator: ValueAnimator? = null

    /**
     * @param index indexul dalei focusate, 0-based
     * @param perPage câte dale are o pagină
     */
    fun setPosition(index: Int, perPage: Int) {
        if (perPage != this.perPage && perPage > 0) {
            this.perPage = perPage
            requestLayout()
        }
        val target = index.toFloat()
        if (target == drawn) return
        animator?.cancel()
        if (!Services.prefs.animationsEnabled) {
            drawn = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(drawn, target).apply {
            duration = ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private val pageW: Float get() = perPage * cellW + (perPage - 1) * cellGap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (pageCount * pageW + (pageCount - 1) * pageGap).toInt()
        setMeasuredDimension(w, (segH * 4).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val r = segH / 2f
        for (page in 0 until pageCount) {
            val x0 = page * (pageW + pageGap)
            for (cell in 0 until perPage) {
                val x = x0 + cell * (cellW + cellGap)
                rect.set(x, cy - r, x + cellW, cy + r)
                canvas.drawRoundRect(rect, r, r, track)
                val glow = 1f - kotlin.math.abs(drawn - (page * perPage + cell))
                if (glow > 0f) {
                    fill.alpha = (255 * glow).toInt()
                    canvas.drawRoundRect(rect, r, r, fill)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DEFAULT_PER_PAGE = 6
        const val CELL_DP = 26f
        const val CELL_GAP_DP = 4f
        const val PAGE_GAP_DP = 24f
        const val HEIGHT_DP = 5f
        const val ANIM_MS = 220L
    }
}
