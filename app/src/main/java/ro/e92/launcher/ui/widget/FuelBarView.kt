package ro.e92.launcher.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Bara de rezervor de sub autonomie, în stilul clusterelor noi BMW/Mercedes:
 * o pistă subțire, umplută cu un gradient portocaliu care se aprinde spre
 * capăt, secționată în zece trepte ca un indicator, cu un punct de lumină pe
 * nivelul curent. Sub [LOW_FRACTION] trece pe roșu.
 *
 * Totul e desenat din shadere create la onSizeChanged; onDraw nu alocă.
 */
class FuelBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(34, 255, 255, 255) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val accent = ContextCompat.getColor(context, R.color.glow_core)

    private val track = RectF()
    private val fill = RectF()
    private var radius = 0f
    private var gapWidth = 0f

    /** Nivelul desenat (0..1); urmărește [target] prin animație. */
    private var shown = 0f
    private var target = 0f
    private var animator: ValueAnimator? = null

    /** @param fraction nivelul rezervorului, 0..1 */
    fun setLevel(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        if (f == target) return
        target = f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, f).apply {
            duration = ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Pista e mai subțire decât view-ul: restul înălțimii e loc pentru
        // haloul punctului de pe nivel.
        val barH = h * 0.5f
        val top = (h - barH) / 2f
        track.set(0f, top, w.toFloat(), top + barH)
        radius = barH / 2f
        gapWidth = resources.displayMetrics.density * 1.2f
        fillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.rgb(110, 26, 4), accent, Color.rgb(255, 150, 90)),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        lowPaint.shader = LinearGradient(
            0f, 0f, w * LOW_FRACTION * 2f, 0f,
            intArrayOf(Color.rgb(120, 10, 10), Color.rgb(235, 45, 35)),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (track.width() <= 0f) return
        canvas.drawRoundRect(track, radius, radius, trackPaint)

        val level = shown
        if (level > 0f) {
            fill.set(track.left, track.top, track.left + track.width() * level, track.bottom)
            canvas.drawRoundRect(fill, radius, radius, if (level < LOW_FRACTION) lowPaint else fillPaint)
        }

        // Treptele: fante fine peste toată pista, ca la un indicator de bord.
        for (i in 1 until STEPS) {
            val x = track.left + track.width() * i / STEPS
            canvas.drawRect(x - gapWidth / 2f, track.top, x + gapWidth / 2f, track.bottom, gapPaint)
        }

        // Punctul de lumină pe nivel: halou + miez, la capătul umplerii.
        if (level > 0f) {
            val x = (track.left + track.width() * level).coerceAtMost(track.right - radius)
            val cy = track.centerY()
            val tint = if (level < LOW_FRACTION) Color.rgb(255, 80, 60) else Color.rgb(255, 170, 110)
            capPaint.color = Color.argb(70, Color.red(tint), Color.green(tint), Color.blue(tint))
            canvas.drawCircle(x, cy, height / 2f, capPaint)
            capPaint.color = Color.rgb(255, 225, 200)
            canvas.drawCircle(x, cy, radius * 0.9f, capPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val STEPS = 10
        const val LOW_FRACTION = 0.15f
        const val ANIM_MS = 600L
    }
}
