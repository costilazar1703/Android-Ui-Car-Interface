package ro.e92.launcher.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
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
 * care arată UNDE ești în meniu — desenată ca butonul fizic din consolă:
 * ramă cromată, coroană zimțată, pista cu câte un marcaj per rând pe care
 * alunecă lumina portocalie și un dom de sticlă în mijloc.
 *
 * Tot ce se desenează e geometrie simplă cu shadere (gradient radial, liniar,
 * sweep), toate create la onSizeChanged. Fără BlurMaskFilter: pe API 27 nu e
 * accelerat hardware și ar forța un layer software la fiecare cadru. Difuzia
 * luminii se face din arce concentrice cu alfa descrescătoare.
 */
class IDriveWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val knurlBase = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val domePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // BUTT, nu ROUND: un cap rotund pe un arc scurt și gros iese în afara
        // sectorului și transformă segmentul aprins într-o pastilă.
        strokeCap = Paint.Cap.BUTT
    }

    private val notchColor = color(R.color.wheel_notch)
    private val accentColor = color(R.color.glow_core)

    /** Dreptunghiuri reutilizate: onDraw nu alocă niciodată. */
    private val arcBounds = RectF()
    private val bezelBounds = RectF()
    private val specularBounds = RectF()

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
    private var rOuter = 0f
    private var rBezel = 0f
    private var rKnurlOuter = 0f
    private var rKnurlInner = 0f
    private var rTrackOuter = 0f
    private var rTrackInner = 0f
    private var rDome = 0f

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

        // Marginea lăsată liberă e cea în care respiră umbra și glow-ul; fără
        // ea arcul aprins ar fi tăiat de marginea view-ului.
        val radius = min(w, h) / 2f - GLOW_MARGIN_RATIO * min(w, h)
        if (radius <= 0f) {
            rOuter = 0f
            return
        }
        rOuter = radius
        rBezel = radius * 0.955f          // mijlocul ramei cromate
        rKnurlOuter = radius * 0.91f
        rKnurlInner = radius * 0.80f
        rTrackOuter = radius * 0.77f
        rTrackInner = radius * 0.66f
        rDome = radius * 0.60f

        // Umbra de sub buton: îl ridică de pe fundal fără blur.
        shadowPaint.shader = RadialGradient(
            cx, cy + radius * 0.04f, radius * 1.06f,
            intArrayOf(Color.argb(170, 0, 0, 0), Color.argb(170, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )

        // Rama cromată: lumina vine din stânga-sus, ca pe dalele cromate.
        bezelPaint.strokeWidth = radius * 0.075f
        bezelPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                CHROME_DARK, CHROME_MID, CHROME_LIGHT, CHROME_MID, CHROME_DARK,
                CHROME_DEEP, CHROME_MID, CHROME_HI, CHROME_MID, CHROME_DEEP, CHROME_DARK
            ),
            floatArrayOf(0f, 0.12f, 0.22f, 0.32f, 0.42f, 0.52f, 0.60f, 0.68f, 0.78f, 0.90f, 1f)
        )
        bezelBounds.set(cx - rBezel, cy - rBezel, cx + rBezel, cy + rBezel)

        // Coroana zimțată: baza închisă, peste care vin dinții în onDraw.
        knurlBase.strokeWidth = rKnurlOuter - rKnurlInner
        knurlBase.shader = RadialGradient(
            cx, cy, rKnurlOuter,
            intArrayOf(KNURL_INNER, KNURL_INNER, KNURL_OUTER),
            floatArrayOf(0f, rKnurlInner / rKnurlOuter, 1f),
            Shader.TileMode.CLAMP
        )

        // Pista luminii: un șanț închis, mai negru spre interior.
        trackPaint.strokeWidth = rTrackOuter - rTrackInner
        trackPaint.shader = RadialGradient(
            cx, cy, rTrackOuter,
            intArrayOf(TRACK_DEEP, TRACK_DEEP, TRACK_EDGE),
            floatArrayOf(0f, rTrackInner / rTrackOuter, 1f),
            Shader.TileMode.CLAMP
        )

        // Domul de sticlă din mijloc, cu reflexia moale de sus.
        domePaint.shader = RadialGradient(
            cx, cy - rDome * 0.25f, rDome * 1.25f,
            intArrayOf(DOME_CENTER, DOME_EDGE),
            null,
            Shader.TileMode.CLAMP
        )
        specularBounds.set(cx - rDome * 0.72f, cy - rDome * 0.94f, cx + rDome * 0.72f, cy - rDome * 0.12f)
        specularPaint.shader = LinearGradient(
            0f, specularBounds.top, 0f, specularBounds.bottom,
            Color.argb(46, 255, 255, 255), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        hairline.strokeWidth = radius * 0.012f
        linePaint.strokeWidth = radius * 0.014f

        val rArc = (rTrackOuter + rTrackInner) / 2f
        arcBounds.set(cx - rArc, cy - rArc, cx + rArc, cy + rArc)
    }

    override fun onDraw(canvas: Canvas) {
        if (rOuter <= 0f) return

        // 1. Umbra și corpul.
        canvas.drawCircle(cx, cy, rOuter * 1.06f, shadowPaint)
        fill.color = BODY
        canvas.drawCircle(cx, cy, rOuter, fill)

        // 2. Rama cromată, cu câte un fir închis pe fiecare muchie.
        canvas.drawCircle(cx, cy, rBezel, bezelPaint)
        hairline.color = EDGE_DARK
        canvas.drawCircle(cx, cy, rOuter, hairline)
        canvas.drawCircle(cx, cy, rKnurlOuter + bezelPaint.strokeWidth * 0.02f, hairline)

        // 3. Coroana zimțată: dinți fini, alternativ luminați și umbriți.
        //    Lumina scade spre dreapta-jos, ca pe ramă.
        canvas.drawCircle(cx, cy, (rKnurlOuter + rKnurlInner) / 2f, knurlBase)
        for (i in 0 until KNURL_TEETH) {
            val angle = FULL_CIRCLE * i / KNURL_TEETH
            val light = 0.5f + 0.5f * cos(Math.toRadians((angle + 135f).toDouble())).toFloat()
            linePaint.color = if (i % 2 == 0) {
                Color.argb((18 + 40 * light).toInt(), 255, 255, 255)
            } else {
                Color.argb(120, 0, 0, 0)
            }
            drawTick(canvas, angle, rKnurlInner + linePaint.strokeWidth, rKnurlOuter - linePaint.strokeWidth, linePaint)
        }

        // 4. Pista și marcajele: câte unul per rând, plus câte unul mic între ele.
        canvas.drawCircle(cx, cy, (rTrackOuter + rTrackInner) / 2f, trackPaint)
        hairline.color = TRACK_LIP
        canvas.drawCircle(cx, cy, rTrackOuter, hairline)
        val bandInset = (rTrackOuter - rTrackInner) * 0.22f
        linePaint.color = notchColor
        for (i in 0 until itemCount) {
            drawTick(canvas, angleOf(i), rTrackInner + bandInset, rTrackOuter - bandInset, linePaint)
        }
        if (itemCount in 2..MAX_MINOR_TICKS) {
            linePaint.color = withAlpha(notchColor, 110)
            val mid = (rTrackOuter + rTrackInner) / 2f
            val minor = (rTrackOuter - rTrackInner) * 0.12f
            for (i in 0 until itemCount) {
                drawTick(canvas, angleOf(i) + FULL_CIRCLE / itemCount / 2f, mid - minor, mid + minor, linePaint)
            }
        }

        // 5. Lumina de pe pistă: o coadă largă și slabă, apoi straturi tot mai
        //    înguste și aprinse — arată ca un LED văzut prin plastic fumuriu.
        //    Sectorul e PLAFONAT: într-un meniu cu 3 rânduri ar ocupa o treime
        //    din inel și ar arăta a bară de încărcare, nu a „ești aici".
        val sweep = minOf((FULL_CIRCLE / itemCount) * SECTOR_FILL, MAX_SWEEP_DEG)
        val band = rTrackOuter - rTrackInner
        for (layer in 0 until TAIL_LAYERS) {
            val t = layer / (TAIL_LAYERS - 1f)               // 0 = cel mai larg
            val s = sweep * (2.6f - 1.6f * t)
            glowPaint.strokeWidth = band * (0.95f - 0.25f * t)
            glowPaint.color = withAlpha(accentColor, (10 + 22 * t).toInt())
            canvas.drawArc(arcBounds, drawnAngle - s / 2f, s, false, glowPaint)
        }
        for (layer in 0 until GLOW_LAYERS) {
            val t = layer / (GLOW_LAYERS - 1f)
            glowPaint.strokeWidth = band * (0.80f - 0.45f * t)
            glowPaint.color = withAlpha(accentColor, (70 + 185 * t).toInt())
            canvas.drawArc(arcBounds, drawnAngle - sweep / 2f, sweep, false, glowPaint)
        }
        // Miezul alb-cald al LED-ului, doar pe mijlocul segmentului.
        glowPaint.strokeWidth = band * 0.14f
        glowPaint.color = LED_CORE
        canvas.drawArc(arcBounds, drawnAngle - sweep * 0.3f, sweep * 0.6f, false, glowPaint)

        // Reflexia LED-ului pe rama cromată, chiar în dreptul lui.
        glowPaint.strokeWidth = bezelPaint.strokeWidth * 0.35f
        glowPaint.color = withAlpha(accentColor, 80)
        canvas.drawArc(bezelBounds, drawnAngle - sweep * 0.4f, sweep * 0.8f, false, glowPaint)

        // 6. Domul de sticlă: fundalul pe care stau iconița și eticheta.
        canvas.drawCircle(cx, cy, rDome, domePaint)
        hairline.color = DOME_RING
        canvas.drawCircle(cx, cy, rDome, hairline)
        hairline.color = withAlpha(accentColor, 60)
        canvas.drawCircle(cx, cy, rDome * 0.93f, hairline)
        canvas.drawOval(specularBounds, specularPaint)
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
        const val TAIL_LAYERS = 4
        const val KNURL_TEETH = 96

        /** Peste atâtea rânduri marcajele mici dintre ele ar face pista zgomotoasă. */
        const val MAX_MINOR_TICKS = 12

        /** Cât din sectorul unui meniu e acoperit de arcul aprins. */
        const val SECTOR_FILL = 0.86f

        /** Plafonul sectorului aprins, oricat de putine intrari ar avea meniul. */
        const val MAX_SWEEP_DEG = 30f

        const val GLOW_MARGIN_RATIO = 0.055f

        val BODY = Color.rgb(14, 14, 16)
        val EDGE_DARK = Color.argb(200, 0, 0, 0)

        // Crom: aceleași tonuri reci ca iconițele dalelor.
        val CHROME_DEEP = Color.rgb(34, 34, 38)
        val CHROME_DARK = Color.rgb(62, 62, 68)
        val CHROME_MID = Color.rgb(120, 120, 128)
        val CHROME_LIGHT = Color.rgb(196, 196, 204)
        val CHROME_HI = Color.rgb(232, 232, 238)

        val KNURL_INNER = Color.rgb(20, 20, 23)
        val KNURL_OUTER = Color.rgb(38, 38, 42)

        val TRACK_DEEP = Color.rgb(6, 6, 7)
        val TRACK_EDGE = Color.rgb(16, 16, 18)
        val TRACK_LIP = Color.argb(70, 255, 255, 255)

        val DOME_CENTER = Color.rgb(30, 30, 34)
        val DOME_EDGE = Color.rgb(9, 9, 10)
        val DOME_RING = Color.rgb(70, 70, 76)

        val LED_CORE = Color.rgb(255, 196, 150)
    }
}
