package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Modul cutiei pe Home, sub mașină („Integrated Gear Display", ales 2026-11-17), după schița
 * aleasă de utilizator:
 *  - în stânga, barele oblice cu aspect 3D (față în degrade, muchie luminată, umbră), apoi
 *    „DRIVING MODE" mic și dedesubt modul (COMFORT / DYNAMIC SPORT);
 *  - în dreapta, D sau DS într-o ramă fină cu latura stângă înclinată ca barele;
 *  - între ele o linie fină, iar jos linia podelei, cu o strălucire discretă în culoarea modului.
 *
 * La schimbare: litera veche se stinge, cea nouă intră alunecând câțiva pixeli, iar liniile se
 * aprind treptat de la stânga la dreapta. Desenat pe ceasul propriu (pe unitate scara
 * animațiilor Android poate fi 0).
 */
class DriveModeStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val orange = ContextCompat.getColor(context, R.color.glow_core)
    private val steel = Color.rgb(170, 184, 199)
    private val light = Color.rgb(230, 232, 236)
    private val dp = resources.displayMetrics.density

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.inter_semibold)
        textSize = 26f * dp
        letterSpacing = 0.02f
        textAlign = Paint.Align.CENTER
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.inter_semibold)
        textSize = 14f * dp
        letterSpacing = 0.22f
    }
    private val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.inter_semibold)
        textSize = 10f * dp
        letterSpacing = 0.3f
        color = Color.rgb(118, 122, 129)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private val kicker = context.getString(R.string.home_driving_mode)

    private var sport: Boolean? = null
    private var previous: Boolean? = null
    private var changedAt = 0L

    /** [animate] = schimbare văzută acum; prima valoare se arată direct. */
    fun setMode(value: Boolean?, animate: Boolean) {
        if (value == sport) return
        previous = sport
        sport = value
        visibility = if (value == null) GONE else VISIBLE
        changedAt = if (animate && previous != null) SystemClock.uptimeMillis() else 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = sport ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val t = if (changedAt == 0L) ANIM_MS else (SystemClock.uptimeMillis() - changedAt).toFloat()

        val accent = if (now) orange else steel
        val lineY = h - 3f * dp
        val lit = ease(t, 120f, 520f)
        val inT = ease(t, 90f, 320f)
        val slide = (1f - inT) * 10f * dp

        // ---- linia podelei: se aprinde de la stânga la dreapta, cu strălucire ----
        drawFloorLine(canvas, w, lineY, steel, if (now) 0.18f else 0.42f, 1f)
        drawFloorLine(canvas, w, lineY, accent, if (now) 1f else 0.6f, lit)

        // ---- stânga: barele 3D ----
        val barTop = lineY - 39f * dp
        val barBottom = lineY - 9f * dp
        drawBars(canvas, barTop, barBottom, if (now) orange else steel, now, inT)

        // ---- lângă bare: DRIVING MODE și modul ----
        val tx = BARS_W * dp + 12f * dp
        canvas.drawText(kicker, tx, barTop + 11f * dp, kickerPaint)
        val name = context.getString(if (now) R.string.mode_strip_sport else R.string.mode_strip_drive)
        namePaint.color = withAlpha(if (now) Color.WHITE else light, 0.35f + 0.65f * inT)
        canvas.drawText(name, tx + slide * 0.5f, barBottom - 1f * dp, namePaint)
        val textEnd = tx + maxOf(namePaint.measureText(name), kickerPaint.measureText(kicker))

        // ---- dreapta: D / DS într-o ramă fină, cu latura stângă înclinată ----
        val letter = if (now) "DS" else "D"
        val boxW = BOX_W * dp
        val boxRight = w - 1f * dp
        val boxLeft = boxRight - boxW
        val boxTop = barTop
        val boxBottom = barBottom + 2f * dp
        drawBox(canvas, boxLeft, boxTop, boxRight, boxBottom, accent, now, lit)

        val letterBase = (boxTop + boxBottom) / 2f + letterPaint.textSize * 0.36f
        // D e mai îngust: stă puțin mai la stânga, ca să pară centrat sub ramă.
        val letterX = boxLeft + boxW * (if (now) 0.56f else 0.5f)
        val old = previous
        if (old != null && t < 160f) {
            letterPaint.color = withAlpha(if (old) orange else light, 1f - t / 160f)
            canvas.drawText(if (old) "DS" else "D", letterX, letterBase, letterPaint)
        }
        letterPaint.color = withAlpha(if (now) orange else light, inT)
        canvas.drawText(letter, letterX + slide, letterBase, letterPaint)

        // ---- linia fină dintre text și ramă ----
        val from = textEnd + 14f * dp
        val to = boxLeft - 8f * dp
        if (to > from) {
            val midY = (barTop + barBottom) / 2f + 4f * dp
            fill.shader = LinearGradient(
                from, 0f, to, 0f,
                withAlpha(accent, 0.05f), withAlpha(accent, 0.55f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(from, midY - 0.5f * dp, from + (to - from) * lit, midY + 0.5f * dp, fill)
            fill.shader = null
        }

        if (t < ANIM_MS) postInvalidateOnAnimation()
    }

    /**
     * Trei bare înclinate, ca niște lamele de metal: o umbră decalată în spate, fața în degrade
     * (deschisă sus, închisă jos), o muchie luminată pe latura stângă și un luciu sus. La Sport
     * mai au un halou portocaliu. Prima e cea mai stinsă, ultima cea mai aprinsă.
     */
    private fun drawBars(canvas: Canvas, top: Float, bottom: Float, color: Int, glow: Boolean, a: Float) {
        val skew = 12f * dp
        val bar = 5.5f * dp
        val gap = 3.5f * dp
        for (i in 0..2) {
            val x = i * (bar + gap)
            // La Sport, din ce în ce mai portocalii: prima stinsă și fără contrast, ultima exact
            // nuanța lui DS, cu contrastul cel mai mare (cerut 2026-11-17).
            // Prima pornește de la jumătate (cerut 2026-11-17: „prima ca a doua").
            val k = 0.5f + 0.25f * i
            val strength = a * (if (glow) 0.3f + 0.7f * k else 0.45f + 0.275f * i)

            // Fără halou: la Sport barele sunt doar portocalii (cerut 2026-11-17).
            // Umbra din spate, decalată spre dreapta-jos.
            bar(x + 1.4f * dp, top + 1.4f * dp, bottom + 1.4f * dp, bar, skew)
            fill.color = Color.argb((150 * strength).toInt(), 0, 0, 0)
            canvas.drawPath(path, fill)

            // Fața.
            bar(x, top, bottom, bar, skew)
            fill.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(
                    withAlpha(blend(color, Color.WHITE, if (glow) 0.3f * k else 0.45f), strength),
                    withAlpha(color, strength),
                    withAlpha(blend(color, Color.BLACK, if (glow) 0.45f * (1f - k) else 0.55f), strength)
                ),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, fill)
            fill.shader = null

            // Muchia luminată din stânga și luciul de sus.
            stroke.strokeWidth = 0.9f * dp
            stroke.shader = LinearGradient(
                0f, top, 0f, bottom,
                withAlpha(Color.WHITE, 0.75f * strength), withAlpha(Color.WHITE, 0.05f), Shader.TileMode.CLAMP
            )
            canvas.drawLine(x + skew, top, x, bottom, stroke)
            canvas.drawLine(x + skew, top, x + skew + bar, top, stroke)
            stroke.shader = null
        }
    }

    /** Pune în [path] un paralelogram înclinat spre dreapta, cu baza la [x]. */
    private fun bar(x: Float, top: Float, bottom: Float, width: Float, skew: Float) {
        path.reset()
        path.moveTo(x + skew, top)
        path.lineTo(x + skew + width, top)
        path.lineTo(x + width, bottom)
        path.lineTo(x, bottom)
        path.close()
    }

    /**
     * Rama literei: doar latura stângă (înclinată ca barele) și cea de sus, care se stinge spre
     * dreapta (cerut 2026-11-17: fără laturile de jos și din dreapta).
     */
    private fun drawBox(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, accent: Int, glow: Boolean, lit: Float) {
        val skew = 9f * dp
        // Colțul din stânga sus, ușor rotunjit (cerut 2026-11-17).
        val cx = l + skew
        val len = kotlin.math.hypot(skew, b - t)
        val rc = CORNER * dp
        path.reset()
        path.moveTo(l, b)
        path.lineTo(cx - skew / len * rc, t + (b - t) / len * rc)
        path.quadTo(cx, t, cx + rc, t)
        path.lineTo(r, t)

        // Umbra ramei, la ambele moduri: portocalie la Sport, gri la Comfort.
        stroke.strokeWidth = 5f * dp
        stroke.color = withAlpha(accent, (if (glow) 0.12f else 0.16f) * lit)
        canvas.drawPath(path, stroke)
        stroke.strokeWidth = 1f * dp
        stroke.shader = LinearGradient(
            l, 0f, r, 0f,
            withAlpha(accent, 0.95f),
            withAlpha(accent, 0.03f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, stroke)
        stroke.shader = null
    }

    /** O linie care se stinge spre capete, cu un halou din câteva linii mai groase și mai slabe. */
    private fun drawFloorLine(canvas: Canvas, w: Float, y: Float, color: Int, strength: Float, reveal: Float) {
        if (reveal <= 0f || strength <= 0f) return
        val right = w * reveal
        for ((thick, a) in GLOW) {
            fill.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(withAlpha(color, a * strength), withAlpha(color, a * strength * 0.7f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            val half = thick * dp / 2f
            canvas.drawRect(0f, y - half, right, y + half, fill)
        }
        fill.shader = null
    }

    private fun withAlpha(color: Int, a: Float) =
        Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private fun ease(now: Float, from: Float, length: Float): Float {
        val x = ((now - from) / length).coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x) * (1f - x)
    }

    private companion object {
        const val ANIM_MS = 700f
        /** Lățimea grupului de bare (dp), de unde începe textul. */
        const val BARS_W = 38f
        /** Lățimea ramei literei (dp): încape și DS. */
        const val BOX_W = 62f
        /** Raza colțului din stânga sus al ramei (dp). */
        const val CORNER = 5f
        /** Grosimea în dp și opacitatea fiecărui strat al liniei de jos: miezul, apoi haloul. */
        val GLOW = listOf(8f to 0.06f, 4.5f to 0.12f, 1.4f to 0.95f)
    }
}
