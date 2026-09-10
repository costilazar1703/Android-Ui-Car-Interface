package ro.e92.launcher.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import ro.e92.launcher.R
import ro.e92.launcher.core.Services

/**
 * Linia curbă dintre rotița din stânga și cardul focusat din dreapta.
 *
 * Ocupă doar culoarul dintre cele două (vezi Guideline-urile din screen_home.xml),
 * deci nu are nevoie să știe nimic despre listă: primește o singură valoare —
 * înălțimea la care trebuie să ajungă, în coordonatele PROPRII — și desenează o
 * cubică de la mijlocul marginii din stânga (unde e centrul rotiței) până acolo.
 *
 * Curba se animă spre noua țintă în loc să sară: la rotirea prin meniu linia
 * „curge" spre cardul următor, ceea ce leagă vizual cele două jumătăți ale
 * ecranului. Cu animațiile oprite din Setări, valoarea se aplică direct.
 */
class ConnectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val accent = ContextCompat.getColor(context, R.color.glow_core)

    /** Path reutilizat — se reconstruiește la fiecare cadru, dar nu alocă. */
    private val path = Path()

    /** Ținta cerută de ecran. -1 = încă nimic focusat, nu desenăm nimic. */
    private var targetY = UNSET

    /** Valoarea desenată efectiv; o urmărește pe [targetY] prin animație. */
    private var drawnY = UNSET

    private var animator: ValueAnimator? = null

    private var density = context.resources.displayMetrics.density

    /**
     * @param y înălțimea capătului din dreapta, în coordonatele acestui view
     *          (ecranul face conversia din poziția rândului focusat).
     */
    fun setTargetY(y: Float) {
        if (y == targetY) return
        targetY = y

        // Primul focus: apare direct la poziția corectă, fără să vină din colț.
        if (drawnY == UNSET || !Services.prefs.animationsEnabled) {
            animator?.cancel()
            drawnY = y
            invalidate()
            return
        }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(drawnY, y).apply {
            duration = SLIDE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawnY = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        linePaint.strokeWidth = LINE_DP * density
        glowPaint.strokeWidth = GLOW_DP * density
    }

    override fun onDraw(canvas: Canvas) {
        if (drawnY == UNSET || width == 0) return

        val startX = 0f
        val startY = height / 2f
        val endX = width.toFloat()
        val endY = drawnY

        // Cubică cu punctele de control pe orizontală: pleacă și sosește exact
        // pe orizontală, deci se leagă curat de rotiță și de muchia cardului.
        path.reset()
        path.moveTo(startX, startY)
        path.cubicTo(
            startX + endX * CONTROL, startY,
            endX - endX * CONTROL, endY,
            endX, endY
        )

        glowPaint.color = withAlpha(accent, GLOW_ALPHA)
        canvas.drawPath(path, glowPaint)

        linePaint.color = accent
        canvas.drawPath(path, linePaint)

        // Punctul de sosire, chiar pe muchia cardului focusat.
        dotPaint.color = accent
        canvas.drawCircle(endX, endY, DOT_DP * density, dotPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun withAlpha(base: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))

    private companion object {
        const val UNSET = -1f
        const val SLIDE_MS = 180L

        const val LINE_DP = 1.5f
        const val GLOW_DP = 5f
        const val DOT_DP = 2.5f
        const val GLOW_ALPHA = 46

        /** Cât de „lungi" sunt mânerele cubicei, ca fracțiune din lățime. */
        const val CONTROL = 0.55f
    }
}
