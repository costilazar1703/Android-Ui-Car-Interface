package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R
import kotlin.math.roundToInt

/**
 * Bara de volum: câte o treaptă pentru fiecare nivel, tot mai înaltă spre dreapta,
 * aprinse până la nivelul curent, cu un buton cromat care se trage cu degetul.
 */
class VolumeSliderView(context: Context, private val max: Int) : View(context) {

    /** Chemat la fiecare nivel nou ales de șofer, nu și la [setValue]. */
    var onChange: ((Int) -> Unit)? = null

    var value = 0
        private set

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.glow_core)
    private val idle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobFace = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = accent
    }
    private val knobDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val rect = RectF()

    private val knobRadius = KNOB_DP * density
    private val inset = knobRadius + HALO_DP * density

    fun setValue(level: Int) {
        val v = level.coerceIn(0, max)
        if (v != value) {
            value = v
            invalidate()
        }
    }

    private fun levelX(level: Float): Float =
        inset + (width - 2 * inset) * level / max

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        lit.shader = LinearGradient(
            inset, 0f, w - inset, 0f,
            0xFF7A2A08.toInt(), accent, Shader.TileMode.CLAMP
        )
        knobRim.shader = LinearGradient(
            0f, h / 2f - knobRadius, 0f, h / 2f + knobRadius,
            0xFFF2F2F2.toInt(), 0xFF5A5A5A.toInt(), Shader.TileMode.CLAMP
        )
        knobFace.shader = LinearGradient(
            0f, h / 2f - knobRadius, 0f, h / 2f + knobRadius,
            0xFF2C2C2C.toInt(), 0xFF0C0C0C.toInt(), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val mid = h / 2f
        val barW = (width - 2 * inset) / max * 0.56f
        val minH = 8f * density
        val maxH = h * 0.62f
        for (level in 1..max) {
            val x = levelX(level - 0.5f)
            val half = (minH + (maxH - minH) * level / max) / 2f
            rect.set(x - barW / 2f, mid - half, x + barW / 2f, mid + half)
            val corner = barW / 2f
            canvas.drawRoundRect(rect, corner, corner, if (level <= value) lit else idle)
        }
        val cx = levelX(value.toFloat())
        val glowR = knobRadius + HALO_DP * density
        halo.shader = RadialGradient(
            cx, mid, glowR,
            (0x90 shl 24) or (accent and 0xFFFFFF), accent and 0xFFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, mid, glowR, halo)
        canvas.drawCircle(cx, mid, knobRadius, knobRim)
        canvas.drawCircle(cx, mid, knobRadius - 3f * density, knobFace)
        canvas.drawCircle(cx, mid, knobRadius * 0.62f, knobRing)
        canvas.drawCircle(cx, mid, 3f * density, knobDot)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val fraction = (event.x - inset) / (width - 2 * inset)
                val level = (fraction * max).roundToInt().coerceIn(0, max)
                if (level != value) {
                    value = level
                    invalidate()
                    onChange?.invoke(level)
                }
            }
        }
        return true
    }

    private companion object {
        const val KNOB_DP = 26f
        const val HALO_DP = 16f
    }
}
