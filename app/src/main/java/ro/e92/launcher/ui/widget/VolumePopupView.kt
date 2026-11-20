package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Volumul pe ecran la + / − de pe volan (cerut 2026-11-19): o bandă subțire jos, aproape de marginea ecranului,
 * ca la mașinile noi: difuzor mic și linia din meniul Media care se aprinde portocaliu,
 * fără cifre, lungă pe mijlocul ecranului. La fiecare apăsare bara alunecă lin la noul
 * nivel; după [HOLD_MS] fără apăsări pastila dispare. Nu prinde atingeri și nu mută focusul.
 * Desenată pe ceasul propriu (pe unitate scara animațiilor poate fi 0).
 */
class VolumePopupView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.glow_core)

    private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = 0x38FFFFFF
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt() }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()

    private var max = 40
    private var target = 0f
    private var shown = 0f
    private var lastFrame = 0L
    private var showAt = 0L
    private var lastPressAt = 0L
    private var visible = false

    init {
        isClickable = false
        isFocusable = false
        visibility = INVISIBLE
    }

    /** Volumul s-a schimbat la [level] din [maxLevel]: pastila apare sau rămâne, bara alunecă. */
    fun show(level: Int, maxLevel: Int) {
        max = maxLevel.coerceAtLeast(1)
        val now = SystemClock.uptimeMillis()
        if (!visible) {
            visible = true
            showAt = now
            shown = level.toFloat()
            visibility = VISIBLE
            bringToFront()
        }
        target = level.coerceIn(0, max).toFloat()
        lastPressAt = now
        lastFrame = now
        invalidate()
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    override fun onDraw(canvas: Canvas) {
        if (!visible) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(0L, 50L)
        lastFrame = now
        // Bara urmează nivelul lin, ca la o rotire de buton.
        shown += (target - shown) * (1f - Math.exp(-dt / 70.0).toFloat())
        if (kotlin.math.abs(target - shown) < 0.01f) shown = target

        val enter = ease((now - showAt) / ENTER_MS)
        val sinceLast = (now - lastPressAt).toFloat()
        val exit = ease((sinceLast - HOLD_MS) / EXIT_MS)
        val a = enter * (1f - exit)
        if (sinceLast > HOLD_MS + EXIT_MS) {
            visible = false
            visibility = INVISIBLE
            return
        }

        // Lungă, dar nu cât tot ecranul (cerut 2026-11-19): de 1,6 ori mai scurtă, pe mijloc.
        val w = minOf(POPUP_W * dp, width - 2 * SIDE_DP * dp)
        val h = POPUP_H * dp
        val cx = width / 2f
        val bottom = height - BOTTOM_GAP * dp + (1f - enter) * 20f * dp + exit * 12f * dp
        box.set(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
        // Colțuri mici, nu pastilă (cerut 2026-11-19).
        val r = CORNER_DP * dp

        // Umbra în straturi (setShadowLayer nu merge cu accelerare hardware pe 8.1).
        for (i in 1..3) {
            shadow.alpha = (8 * a).toInt()
            val g = i * 3f * dp
            canvas.drawRoundRect(box.left - g, box.top - g, box.right + g, box.bottom + g, r + g, r + g, shadow)
        }
        glass.shader = null
        glass.color = Color.argb((150 * a).toInt(), 14, 15, 18)
        canvas.drawRoundRect(box, r, r, glass)
        edge.strokeWidth = 1f * dp
        edge.shader = null
        edge.color = Color.argb((34 * a).toInt(), 255, 255, 255)
        canvas.drawRoundRect(box, r, r, edge)

        // Difuzorul, mic, cu undele aprinse după nivel (0, 1, 2 sau 3).
        val icx = box.left + 20f * dp
        drawSpeaker(canvas, icx, box.centerY(), a)

        // Linia din meniul Media, fină.
        val start = icx + 24f * dp
        val end = box.right - 20f * dp
        val mid = box.centerY()
        val x = start + (end - start) * shown / max
        val rgb = accent and 0xFFFFFF
        track.strokeWidth = LINE_DP * dp
        track.alpha = (0x38 * a).toInt()
        canvas.drawLine(start, mid, end, mid, track)
        if (x > start) {
            fill.strokeWidth = LINE_DP * dp
            fill.shader = LinearGradient(start, 0f, x, 0f, (0x50 shl 24) or rgb, accent, Shader.TileMode.CLAMP)
            fill.alpha = (255 * a).toInt()
            canvas.drawLine(start, mid, x, mid, fill)
        }
        // Fără buton: doar un punct de lumină la capătul liniei aprinse.
        val glow = 6f * dp
        halo.shader = RadialGradient(x, mid, glow, (((0x90 * a).toInt()) shl 24) or rgb, rgb, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, mid, glow, halo)
        knob.alpha = (255 * a).toInt()
        canvas.drawCircle(x, mid, 2.2f * dp, knob)


        if (shown != target || exit < 1f) postInvalidateOnAnimation()
    }

    /** Difuzor în griul etichetelor din bară (#B8B8B8); undele se aprind după nivel, mut = un X. */
    private fun drawSpeaker(canvas: Canvas, cx: Float, cy: Float, a: Float) {
        val s = dp * 0.62f
        icon.strokeWidth = 1.6f * dp
        icon.color = Color.argb((240 * a).toInt(), 184, 184, 184)
        iconFill.color = icon.color
        path.reset()
        path.moveTo(cx - 11f * s, cy - 4.5f * s)
        path.lineTo(cx - 6f * s, cy - 4.5f * s)
        path.lineTo(cx + 1f * s, cy - 10f * s)
        path.lineTo(cx + 1f * s, cy + 10f * s)
        path.lineTo(cx - 6f * s, cy + 4.5f * s)
        path.lineTo(cx - 11f * s, cy + 4.5f * s)
        path.close()
        canvas.drawPath(path, iconFill)
        val level = target / max
        if (level <= 0f) {
            canvas.drawLine(cx + 6f * s, cy - 5f * s, cx + 14f * s, cy + 5f * s, icon)
            canvas.drawLine(cx + 14f * s, cy - 5f * s, cx + 6f * s, cy + 5f * s, icon)
            return
        }
        val waves = when {
            level < 0.34f -> 1
            level < 0.67f -> 2
            else -> 3
        }
        for (i in 1..3) {
            val rr = (3f + 4.5f * i) * s
            icon.color = if (i <= waves) Color.argb((240 * a).toInt(), 184, 184, 184)
            else Color.argb((60 * a).toInt(), 184, 184, 184)
            canvas.drawArc(cx + 1f * s - rr, cy - rr, cx + 1f * s + rr, cy + rr, -45f, 90f, false, icon)
        }
    }

    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x) * (1f - x)
    }

    private companion object {
        /** Marginea din stânga și din dreapta (dp). */
        const val SIDE_DP = 40f
        const val POPUP_W = 750f
        const val POPUP_H = 40f
        /** Jos, aproape de marginea ecranului (cerut 2026-11-19). */
        const val BOTTOM_GAP = 10f
        const val ENTER_MS = 220f
        const val EXIT_MS = 300f
        /** Cât stă pe ecran după ultima apăsare. */
        const val HOLD_MS = 2_000f
        const val LINE_DP = 2f
        const val CORNER_DP = 6f
    }
}
