package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R
import kotlin.math.roundToInt

/**
 * Bara de volum dintr-un rând de meniu: o linie subțire care se aprinde până la
 * nivelul curent, cu un buton mic de tras, ca la iDrive-ul nou. Pe rândul
 * selectat, fundalul e portocaliu, așa că linia aprinsă devine albă.
 */
class VolumeLineView(context: Context, private val max: Int) : View(context) {

    /** Chemat la fiecare nivel ales de șofer, nu și la [setValue]. */
    var onChange: ((Int) -> Unit)? = null

    var value = 0
        private set

    /** Rotița iDrive mișcă acum bara: butonul se mărește, ca să se vadă. */
    var adjusting = false
        set(on) {
            field = on
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.glow_core)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = LINE_DP * density
        color = 0x38FFFFFF
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = LINE_DP * density
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt() }
    private val inset = (KNOB_DP + HALO_DP) * density

    init {
        isDuplicateParentStateEnabled = true
    }

    fun setValue(level: Int) {
        value = level.coerceIn(0, max)
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private fun onSelectedRow(): Boolean =
        drawableState.contains(android.R.attr.state_activated)

    override fun onDraw(canvas: Canvas) {
        val mid = height / 2f
        val start = inset
        val end = width - inset
        val x = start + (end - start) * value / max
        val lit = if (onSelectedRow()) 0xFFFFFFFF.toInt() else accent
        val rgb = lit and 0xFFFFFF

        canvas.drawLine(start, mid, end, mid, track)
        if (x > start) {
            // Se aprinde treptat spre buton, ca o lumină care urcă pe linie.
            fill.shader = LinearGradient(
                start, 0f, x, 0f, (0x50 shl 24) or rgb, lit, Shader.TileMode.CLAMP
            )
            canvas.drawLine(start, mid, x, mid, fill)
        }
        val radius = (if (adjusting) KNOB_ADJUST_DP else KNOB_DP) * density
        val glow = radius + HALO_DP * density
        halo.shader = RadialGradient(
            x, mid, glow, (0x80 shl 24) or rgb, rgb, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, mid, glow, halo)
        canvas.drawCircle(x, mid, radius, knob)
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
        const val LINE_DP = 2.5f
        const val KNOB_DP = 7f
        const val KNOB_ADJUST_DP = 9.5f
        const val HALO_DP = 7f
    }
}
