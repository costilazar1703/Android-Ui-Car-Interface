package ro.e92.launcher.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.cos

/**
 * Rotița iDrive desenată în stânga meniului principal.
 *
 * NU e un control: nu primește focus și nu consumă evenimente. E ancora vizuală
 * care arată UNDE ești în meniu: o ramă cromată și un inel gros secționat în
 * blocuri; blocurile din dreptul rândului selectat se aprind portocaliu, iar
 * lumina alunecă pe ele odată cu focusul. Mijlocul rămâne gol, pentru iconița
 * și eticheta din layout.
 *
 * Pe cadru: un cerc, rama și [SEGMENTS] arce scurte, cu shadere create la
 * onSizeChanged — nimic alocat în onDraw. Fără BlurMaskFilter: pe API 27 nu e
 * accelerat hardware și ar forța un layer software la fiecare cadru; haloul e
 * făcut din două arce cu alfa mică.
 */
class IDriveWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // BUTT: fiecare bloc se termină drept, nu ca o pastilă.
        strokeCap = Paint.Cap.BUTT
    }
    private val knurlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val accentColor = color(R.color.glow_core)

    /** Dreptunghiuri reutilizate: onDraw nu alocă niciodată. */
    private val ringBounds = RectF()
    private val bezelBounds = RectF()

    /** Tonul stins al fiecărui bloc, cu lumina din stânga-sus; fix pe mărime. */
    private val segmentIdle = IntArray(SEGMENTS)

    /**
     * Zimții din interiorul blocurilor: capetele fiecărei linii și culoarea ei,
     * calculate o dată pe mărime ca onDraw să nu facă trigonometrie.
     */
    private val knurlCoords = FloatArray(KNURL_LINES * 4)
    private val knurlColor = IntArray(KNURL_LINES)

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
    private var rRingOuter = 0f
    private var rRingInner = 0f

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

        // Marginea lăsată liberă e cea în care respiră umbra și haloul.
        val radius = min(w, h) / 2f - GLOW_MARGIN_RATIO * min(w, h)
        if (radius <= 0f) {
            rOuter = 0f
            return
        }
        rOuter = radius
        rBezel = radius * 0.955f
        rRingOuter = radius * 0.89f
        rRingInner = radius * 0.76f

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

        val rMid = (rRingOuter + rRingInner) / 2f
        ringBounds.set(cx - rMid, cy - rMid, cx + rMid, cy + rMid)
        segmentPaint.strokeWidth = rRingOuter - rRingInner
        hairline.strokeWidth = radius * 0.012f

        // Zimții: câte KNURL_PER_SEGMENT linii fine în fiecare bloc, alternativ
        // luminate și umbrite, cu lumina din stânga-sus ca pe ramă.
        knurlPaint.strokeWidth = radius * 0.014f
        val inset = (rRingOuter - rRingInner) * 0.12f
        val usable = SEGMENT_SWEEP - SEGMENT_GAP
        for (i in 0 until SEGMENTS) {
            val blockStart = segmentStart(i) + SEGMENT_GAP / 2f
            for (k in 0 until KNURL_PER_SEGMENT) {
                val line = i * KNURL_PER_SEGMENT + k
                val angle = blockStart + usable * (k + 0.5f) / KNURL_PER_SEGMENT
                val rad = Math.toRadians(angle.toDouble())
                val c = cos(rad).toFloat()
                val sn = kotlin.math.sin(rad).toFloat()
                knurlCoords[line * 4] = cx + c * (rRingInner + inset)
                knurlCoords[line * 4 + 1] = cy + sn * (rRingInner + inset)
                knurlCoords[line * 4 + 2] = cx + c * (rRingOuter - inset)
                knurlCoords[line * 4 + 3] = cy + sn * (rRingOuter - inset)
                val light = 0.5f + 0.5f * cos(Math.toRadians((angle + 135f).toDouble())).toFloat()
                knurlColor[line] = if (k % 2 == 0) {
                    Color.argb((22 + 46 * light).toInt(), 255, 255, 255)
                } else {
                    Color.argb(120, 0, 0, 0)
                }
            }
        }

        // Blocurile stinse: puțin mai deschise spre stânga-sus, ca rama.
        for (i in 0 until SEGMENTS) {
            val mid = Math.toRadians((segmentStart(i) + SEGMENT_SWEEP / 2f + 135f).toDouble())
            val light = 0.5f + 0.5f * cos(mid).toFloat()
            val v = (30 + 22 * light).toInt()
            segmentIdle[i] = Color.rgb(v, v, v + 3)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (rOuter <= 0f) return

        // 1. Umbra și corpul (mijlocul închis, sub iconiță).
        canvas.drawCircle(cx, cy, rOuter * 1.06f, shadowPaint)
        fill.color = BODY
        canvas.drawCircle(cx, cy, rOuter, fill)

        // 2. Rama cromată, cu un fir închis pe muchia exterioară.
        canvas.drawCircle(cx, cy, rBezel, bezelPaint)
        hairline.color = EDGE_DARK
        canvas.drawCircle(cx, cy, rOuter, hairline)

        // 3. Haloul de sub blocurile aprinse, ca lumina să pară că trece prin
        //    ele, nu că e vopsită pe ele.
        val spread = spreadDeg()
        glowPaint.strokeWidth = segmentPaint.strokeWidth * 1.2f
        glowPaint.color = withAlpha(accentColor, 34)
        canvas.drawArc(ringBounds, drawnAngle - spread * 1.3f, spread * 2.6f, false, glowPaint)
        glowPaint.strokeWidth = segmentPaint.strokeWidth * 1.06f
        glowPaint.color = withAlpha(accentColor, 46)
        canvas.drawArc(ringBounds, drawnAngle - spread * 0.8f, spread * 1.6f, false, glowPaint)

        // 4. Inelul secționat. Fiecare bloc se aprinde după cât de aproape e de
        //    unghiul curent — în animație lumina curge de la un bloc la altul.
        val sweep = SEGMENT_SWEEP - SEGMENT_GAP
        for (i in 0 until SEGMENTS) {
            val start = segmentStart(i)
            val f = litFactor(start + SEGMENT_SWEEP / 2f, spread)
            segmentPaint.color = if (f <= 0f) segmentIdle[i] else mix(segmentIdle[i], accentColor, f)
            canvas.drawArc(ringBounds, start + SEGMENT_GAP / 2f, sweep, false, segmentPaint)
            for (k in 0 until KNURL_PER_SEGMENT) {
                val line = i * KNURL_PER_SEGMENT + k
                knurlPaint.color = knurlColor[line]
                canvas.drawLine(
                    knurlCoords[line * 4], knurlCoords[line * 4 + 1],
                    knurlCoords[line * 4 + 2], knurlCoords[line * 4 + 3],
                    knurlPaint
                )
            }
            if (f > 0.75f) {
                // Miezul cald al blocurilor din centru: o bandă subțire pe mijloc.
                glowPaint.strokeWidth = segmentPaint.strokeWidth * 0.28f
                glowPaint.color = withAlpha(LED_CORE, ((f - 0.75f) * 4f * 220).toInt())
                canvas.drawArc(ringBounds, start + SEGMENT_GAP / 2f, sweep, false, glowPaint)
            }
        }

        // 5. Muchiile inelului, ca să pară îngropat între ramă și mijloc.
        hairline.color = EDGE_DARK
        canvas.drawCircle(cx, cy, rRingOuter, hairline)
        canvas.drawCircle(cx, cy, rRingInner, hairline)

        // 6. Cele două inele fine din mijloc: unul gri, unul portocaliu stins.
        hairline.color = INNER_RING
        canvas.drawCircle(cx, cy, rRingInner * 0.92f, hairline)
        hairline.color = withAlpha(accentColor, 70)
        canvas.drawCircle(cx, cy, rRingInner * 0.86f, hairline)

        // Reflexia luminii pe rama cromată, chiar în dreptul ei.
        glowPaint.strokeWidth = bezelPaint.strokeWidth * 0.35f
        glowPaint.color = withAlpha(accentColor, 80)
        canvas.drawArc(bezelBounds, drawnAngle - spread * 0.5f, spread, false, glowPaint)
    }

    private fun segmentStart(i: Int): Float = ANGLE_TOP - SEGMENT_SWEEP / 2f + SEGMENT_SWEEP * i

    /**
     * Cât de lată e pata de lumină: proporțională cu un rând din meniu, dar
     * plafonată — într-un meniu cu 3 rânduri ar acoperi o treime din inel și ar
     * arăta a bară de încărcare, nu a „ești aici".
     */
    private fun spreadDeg(): Float =
        (FULL_CIRCLE / itemCount * 0.5f).coerceIn(MIN_SPREAD_DEG, MAX_SPREAD_DEG)

    /** 1 în dreptul unghiului curent, 0 dincolo de [spread]; curbă moale. */
    private fun litFactor(angle: Float, spread: Float): Float {
        var d = abs((angle - drawnAngle) % FULL_CIRCLE)
        if (d > 180f) d = FULL_CIRCLE - d
        if (d >= spread) return 0f
        val t = 1f - d / spread
        return t * t * (3f - 2f * t)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)

    private fun withAlpha(base: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(base), Color.green(base), Color.blue(base))

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private companion object {
        /** Canvas-ul măsoară unghiurile de la ora 3; -90 pune indexul 0 sus. */
        const val ANGLE_TOP = -90f
        const val FULL_CIRCLE = 360f

        const val SPIN_MS = 220L

        /** Câte blocuri are inelul; 24 = câte un bloc la 15°. */
        const val SEGMENTS = 24
        const val SEGMENT_SWEEP = FULL_CIRCLE / SEGMENTS
        const val SEGMENT_GAP = 2.6f

        /** Zimții din fiecare bloc; 4 × 24 = 96, cât avea coroana zimțată. */
        const val KNURL_PER_SEGMENT = 4
        const val KNURL_LINES = SEGMENTS * KNURL_PER_SEGMENT

        const val MIN_SPREAD_DEG = 14f
        const val MAX_SPREAD_DEG = 26f

        const val GLOW_MARGIN_RATIO = 0.055f

        val BODY = Color.rgb(12, 12, 14)
        val EDGE_DARK = Color.argb(200, 0, 0, 0)

        // Crom: aceleași tonuri reci ca iconițele dalelor.
        val CHROME_DEEP = Color.rgb(34, 34, 38)
        val CHROME_DARK = Color.rgb(62, 62, 68)
        val CHROME_MID = Color.rgb(120, 120, 128)
        val CHROME_LIGHT = Color.rgb(196, 196, 204)
        val CHROME_HI = Color.rgb(232, 232, 238)

        val LED_CORE = Color.rgb(255, 196, 150)
        val INNER_RING = Color.rgb(70, 70, 76)
    }
}
