package ro.e92.launcher.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Rotița iDrive desenată în stânga meniului principal.
 *
 * NU e un control: nu primește focus și nu consumă evenimente. E ancora vizuală
 * care arată UNDE ești în cele 10 meniuri — un inel cu câte un marcaj per meniu
 * și un segment aprins care se rotește odată cu focusul, exact ca lumina de pe
 * rotița fizică din bord.
 *
 * Tot ce se desenează e geometrie simplă (cercuri, linii, un arc). Fără
 * BlurMaskFilter: pe API 27 nu e accelerat hardware și ar forța un layer software
 * la fiecare cadru. Difuzia se face din 3 arce concentrice cu alfa descrescătoare
 * — arată la fel și costă nimic.
 */
class IDriveWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rimFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // BUTT, nu ROUND: un cap rotund pe un arc scurt și gros iese în afara
        // sectorului și transformă segmentul aprins într-o pastilă.
        strokeCap = Paint.Cap.BUTT
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val centerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val rimOuterColor = color(R.color.wheel_rim_outer)
    private val rimInnerColor = color(R.color.wheel_rim_inner)
    private val notchColor = color(R.color.wheel_notch)
    private val centerColor = color(R.color.wheel_center)
    private val accentColor = color(R.color.glow_core)

    /** Arcul aprins, reutilizat: onDraw nu alocă niciodată. */
    private val arcBounds = RectF()

    private var itemCount = 1
    private var selectedIndex = 0

    /**
     * Unghiul desenat efectiv. Diferit de cel al lui [selectedIndex] cât timp
     * animația rulează — de aici senzația de rotiță care se așază, nu care sare.
     */
    private var drawnAngle = ANGLE_TOP
    private var animator: ValueAnimator? = null

    // Geometrie recalculată doar la onSizeChanged.
    private var cx = 0f
    private var cy = 0f
    private var rRim = 0f
    private var rNotchOuter = 0f
    private var rNotchInner = 0f
    private var rCenter = 0f

    /**
     * Sincronizează rotița cu focusul.
     *
     * @param index indexul meniului focusat
     * @param total numărul total de meniuri (câte marcaje are inelul)
     */
    fun setSelection(index: Int, total: Int) {
        if (total <= 0) return
        val changed = index != selectedIndex || total != itemCount
        itemCount = total
        selectedIndex = index
        if (!changed) return

        val target = angleOf(index)
        if (!Services.prefs.animationsEnabled) {
            animator?.cancel()
            drawnAngle = target
        } else {
            animateTo(target)
        }

        // invalidate() NECONDIȚIONAT, chiar dacă unghiul nu s-a mișcat.
        //
        // Numărul de marcaje se schimbă odată cu meniul, iar primul rând al
        // oricărui meniu e tot la -90°: intrând dintr-un meniu de 5 în unul de 4,
        // unghiul rămâne identic, [animateTo] nu are ce anima și iese imediat.
        // Fără linia asta inelul ar rămâne desenat cu marcajele meniului
        // anterior — sau, la prima afișare, cu unul singur.
        invalidate()
    }

    /**
     * Rotim pe drumul scurt: de la meniul 10 la meniul 1 rotița merge înainte
     * 36 de grade, nu înapoi 324. Fără asta, wrap-around-ul listei ar arăta ca
     * o derapare de o tură completă.
     */
    private fun animateTo(target: Float) {
        var delta = (target - drawnAngle) % FULL_CIRCLE
        if (delta > 180f) delta -= FULL_CIRCLE
        if (delta < -180f) delta += FULL_CIRCLE
        if (delta == 0f) return

        val from = drawnAngle
        animator?.cancel()
        animator = ValueAnimator.ofFloat(from, from + delta).apply {
            duration = SPIN_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawnAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun angleOf(index: Int): Float =
        ANGLE_TOP + FULL_CIRCLE * index / itemCount

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f

        // Marginea lăsată liberă e cea în care respiră glow-ul marcajului; fără
        // ea arcul aprins ar fi tăiat de marginea view-ului.
        val radius = min(w, h) / 2f - GLOW_MARGIN_RATIO * min(w, h)
        if (radius <= 0f) {
            rRim = 0f
            return
        }
        rRim = radius
        rNotchOuter = radius * 0.90f
        rNotchInner = radius * 0.76f
        rCenter = radius * 0.60f

        rimStroke.strokeWidth = radius * 0.045f
        notchPaint.strokeWidth = radius * 0.030f
        // Marcajul aprins e tot un marcaj, doar puțin mai gros — nu un element
        // de altă formă. Diferența de culoare face treaba, nu diferența de masă.
        markerPaint.strokeWidth = radius * 0.045f
        centerStroke.strokeWidth = radius * 0.020f

        rimFill.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(rimInnerColor, rimInnerColor, rimOuterColor),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )

        val rArc = (rNotchOuter + rNotchInner) / 2f
        arcBounds.set(cx - rArc, cy - rArc, cx + rArc, cy + rArc)
    }

    override fun onDraw(canvas: Canvas) {
        if (rRim <= 0f) return

        // 1. Corpul rotiței.
        canvas.drawCircle(cx, cy, rRim, rimFill)
        rimStroke.color = rimOuterColor
        canvas.drawCircle(cx, cy, rRim, rimStroke)

        // 2. Câte un marcaj stins pentru fiecare meniu.
        notchPaint.color = notchColor
        for (i in 0 until itemCount) {
            drawTick(canvas, angleOf(i), rNotchInner, rNotchOuter, notchPaint)
        }

        // 3. Segmentul aprins: trei arce suprapuse, de la difuz la aprins.
        //    Lățimile sunt sub-unitare față de banda marcajelor — un arc mai lat
        //    decât banda pe care stă nu mai arată a segment de inel, ci a pată.
        // Sectorul e proporțional cu numărul de intrări, dar PLAFONAT: un meniu
        // cu 3 rânduri ar da un sector de 120°, iar segmentul aprins ar acoperi
        // o treime de inel — ar arăta ca un indicator de încărcare, nu ca marcajul
        // poziției curente. Marcajul trebuie să spună „ești aici", nu „felia ta
        // e atât de mare".
        val sweep = minOf((FULL_CIRCLE / itemCount) * SECTOR_FILL, MAX_SWEEP_DEG)
        val start = drawnAngle - sweep / 2f
        val arcWidth = rNotchOuter - rNotchInner
        for (layer in 0 until GLOW_LAYERS) {
            val t = layer / (GLOW_LAYERS - 1f)          // 0 = stratul exterior, difuz
            glowPaint.strokeWidth = arcWidth * (GLOW_WIDTH_MAX - GLOW_WIDTH_SPAN * t)
            glowPaint.color = withAlpha(accentColor, (28 + 172 * t).toInt())
            canvas.drawArc(arcBounds, start, sweep, false, glowPaint)
        }

        // 4. Marcajul meniului focusat: aceeași lungime ca marcajele stinse, ca
        //    inelul să rămână regulat — se schimbă doar culoarea și grosimea.
        markerPaint.color = accentColor
        drawTick(canvas, drawnAngle, rNotchInner, rNotchOuter, markerPaint)

        // 5. Discul central — fundalul pe care stau iconița și eticheta din layout.
        centerPaint.color = centerColor
        canvas.drawCircle(cx, cy, rCenter, centerPaint)
        centerStroke.color = rimOuterColor
        canvas.drawCircle(cx, cy, rCenter, centerStroke)
    }

    private fun drawTick(
        canvas: Canvas,
        angleDeg: Float,
        rInner: Float,
        rOuter: Float,
        paint: Paint
    ) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        canvas.drawLine(
            cx + c * rInner, cy + s * rInner,
            cx + c * rOuter, cy + s * rOuter,
            paint
        )
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)

    private fun withAlpha(base: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(base), Color.green(base), Color.blue(base))

    private companion object {
        /** Canvas-ul măsoară unghiurile de la ora 3; -90 pune indexul 0 sus. */
        const val ANGLE_TOP = -90f
        const val FULL_CIRCLE = 360f

        const val SPIN_MS = 220L
        const val GLOW_LAYERS = 3

        /** Cât din sectorul unui meniu e acoperit de arcul aprins. */
        const val SECTOR_FILL = 0.86f

        /** Plafonul sectorului aprins, oricat de putine intrari ar avea meniul. */
        const val MAX_SWEEP_DEG = 34f

        /** Lățimile arcelor, ca fracțiune din banda marcajelor: 1.5 → 0.45. */
        const val GLOW_WIDTH_MAX = 1.5f
        const val GLOW_WIDTH_SPAN = 1.05f

        const val GLOW_MARGIN_RATIO = 0.055f
    }
}
