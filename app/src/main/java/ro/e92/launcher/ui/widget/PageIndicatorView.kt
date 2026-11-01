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
 * Indicatorul de pagină al grilei principale: segmente subțiri, ca barele de
 * „stories" din iOS, în loc de puncte.
 *
 * Progresul curge CONTINUU prin toate dalele, nu doar prin pagina curentă.
 * Poziția e un singur număr — pagină + cât din pagină ai parcurs — iar fiecare
 * segment arată cât din el e acoperit. Rotind de la prima la a șasea dală,
 * primul segment se umple treptat; pe pagina a doua începe al doilea, iar primul
 * rămâne plin. Se vede dintr-o privire unde ești din 12, nu doar pe ce pagină.
 *
 * Valoarea desenată urmărește ținta printr-o animație scurtă: umplerea curge,
 * nu sare de la o dală la alta.
 */
class PageIndicatorView(context: Context, private val pageCount: Int) : View(context) {

    private val density = resources.displayMetrics.density
    private val segW = SEGMENT_DP * density
    private val segH = HEIGHT_DP * density
    private val gap = GAP_DP * density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glow_core)
    }
    private val rect = RectF()

    /** Poziția desenată: pagină + fracțiunea parcursă din ea, 0..pageCount. */
    private var drawn = 0f
    private var animator: ValueAnimator? = null

    /**
     * @param index indexul dalei focusate, 0-based
     * @param perPage câte dale are o pagină
     */
    fun setPosition(index: Int, perPage: Int) {
        val target = index / perPage + (index % perPage + 1f) / perPage
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (pageCount * segW + (pageCount - 1) * gap).toInt()
        setMeasuredDimension(w, (segH * 4).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val r = segH / 2f
        for (i in 0 until pageCount) {
            val x = i * (segW + gap)
            rect.set(x, cy - r, x + segW, cy + r)
            canvas.drawRoundRect(rect, r, r, track)
            val f = (drawn - i).coerceIn(0f, 1f)
            if (f > 0f) {
                rect.right = x + segW * f
                canvas.drawRoundRect(rect, r, r, fill)
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SEGMENT_DP = 58f
        const val HEIGHT_DP = 3.5f
        const val GAP_DP = 8f
        const val ANIM_MS = 220L
    }
}
