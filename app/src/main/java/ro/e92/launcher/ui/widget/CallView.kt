package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R
import ro.e92.launcher.core.KswBluetooth

/**
 * Apelul, peste orice ecran: cine sună și butoanele, în limbajul launcher-ului
 * (sticlă întunecată, rama portocalie pe butonul ales), nu ecranul vendorului.
 *
 *          INCOMING CALL
 *          Mama
 *          +40 7xx xxx xxx
 *
 *       ( Decline )   ( Answer )
 *
 * Se comandă din rotiță: rotirea alege butonul, apăsarea îl activează. Cu
 * degetul, oricare buton se apasă direct.
 */
class CallView(context: Context) : View(context) {

    enum class Button { ANSWER, DECLINE, HANG_UP, TO_PHONE }

    var onButton: ((Button) -> Unit)? = null

    private var state = KswBluetooth.State()
    private var buttons: List<Button> = emptyList()
    private var selected = 0

    private val accent = ContextCompat.getColor(context, R.color.glow_core)
    private val scrim = Paint().apply { color = 0xD9000000.toInt() }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33FFFFFF
    }
    private val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        typeface = context.resources.getFont(R.font.inter_semibold)
        letterSpacing = 0.32f
        textAlign = Paint.Align.CENTER
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = context.resources.getFont(R.font.inter_display_light)
        textAlign = Paint.Align.CENTER
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8A8A8.toInt()
        typeface = context.resources.getFont(R.font.inter_regular)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }
    private val buttonFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val buttonText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = context.resources.getFont(R.font.inter_semibold)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.06f
    }

    private val panelRect = RectF()
    private val buttonRects = ArrayList<RectF>()

    init {
        isClickable = true
        isFocusable = false
        defaultFocusHighlightEnabled = false
        alpha = 0f
    }

    fun show() = animate().alpha(1f).setDuration(FADE_MS).start()

    fun hide(then: () -> Unit) = animate().alpha(0f).setDuration(FADE_MS).withEndAction(then).start()

    fun update(s: KswBluetooth.State) {
        val callChanged = s.call != state.call
        state = s
        val wanted = when (s.call) {
            KswBluetooth.Call.INCOMING -> listOf(Button.DECLINE, Button.ANSWER)
            KswBluetooth.Call.OUTGOING -> listOf(Button.HANG_UP)
            KswBluetooth.Call.ACTIVE -> listOf(Button.TO_PHONE, Button.HANG_UP)
            KswBluetooth.Call.NONE -> emptyList()
        }
        if (callChanged || wanted != buttons) {
            buttons = wanted
            // Implicit butonul din dreapta: „Answer" pe apel primit, „Hang up" în
            // convorbire - o apăsare de rotiță face lucrul cel mai probabil.
            selected = wanted.lastIndex.coerceAtLeast(0)
        }
        invalidate()
    }

    fun move(step: Int) {
        if (buttons.isEmpty()) return
        selected = (selected + step).coerceIn(0, buttons.lastIndex)
        invalidate()
    }

    fun activate() {
        buttons.getOrNull(selected)?.let { onButton?.invoke(it) }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, scrim)

        val pw = w * 0.52f
        val ph = h * 0.74f
        panelRect.set((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f)
        val corner = h * 0.035f
        panel.shader = LinearGradient(
            0f, panelRect.top, 0f, panelRect.bottom,
            intArrayOf(0xF2161616.toInt(), 0xF20A0A0A.toInt()), null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(panelRect, corner, corner, panel)
        panelStroke.strokeWidth = resources.displayMetrics.density
        canvas.drawRoundRect(panelRect, corner, corner, panelStroke)

        val cx = w / 2f
        kickerPaint.textSize = h * 0.032f
        namePaint.textSize = h * 0.085f
        numberPaint.textSize = h * 0.042f

        val title = when (state.call) {
            KswBluetooth.Call.INCOMING -> context.getString(R.string.call_incoming)
            KswBluetooth.Call.OUTGOING -> context.getString(R.string.call_outgoing)
            KswBluetooth.Call.ACTIVE -> context.getString(R.string.call_active, elapsed())
            KswBluetooth.Call.NONE -> ""
        }
        canvas.drawText(title.uppercase(), cx, panelRect.top + ph * 0.16f, kickerPaint)

        val name = state.callName.ifBlank { state.callNumber.ifBlank { context.getString(R.string.call_unknown) } }
        canvas.drawText(name, cx, panelRect.top + ph * 0.36f, namePaint)
        if (state.callName.isNotBlank() && state.callNumber.isNotBlank()) {
            canvas.drawText(state.callNumber, cx, panelRect.top + ph * 0.48f, numberPaint)
        }

        drawButtons(canvas, cx, panelRect.top + ph * 0.74f, h)

        // Cronometrul convorbirii curge cât e deschis ecranul.
        if (state.call == KswBluetooth.Call.ACTIVE) postInvalidateDelayed(1_000L)
    }

    private fun drawButtons(canvas: Canvas, cx: Float, cy: Float, h: Float) {
        buttonRects.clear()
        if (buttons.isEmpty()) return
        val bw = h * 0.34f
        val bh = h * 0.13f
        val gap = h * 0.06f
        val total = buttons.size * bw + (buttons.size - 1) * gap
        var x = cx - total / 2f
        buttonText.textSize = h * 0.036f
        buttons.forEachIndexed { i, b ->
            val r = RectF(x, cy - bh / 2f, x + bw, cy + bh / 2f)
            buttonRects.add(r)
            val danger = b == Button.DECLINE || b == Button.HANG_UP
            buttonFill.color = when {
                b == Button.ANSWER -> accent
                danger -> 0xFF8E1508.toInt()
                else -> 0xFF262626.toInt()
            }
            canvas.drawRoundRect(r, bh / 2f, bh / 2f, buttonFill)
            if (i == selected) {
                buttonStroke.color = if (b == Button.ANSWER) Color.WHITE else accent
                buttonStroke.strokeWidth = resources.displayMetrics.density * 2.5f
                val o = buttonStroke.strokeWidth * 1.6f
                val ring = RectF(r.left - o, r.top - o, r.right + o, r.bottom + o)
                canvas.drawRoundRect(ring, bh / 2f + o, bh / 2f + o, buttonStroke)
            }
            val label = context.getString(
                when (b) {
                    Button.ANSWER -> R.string.call_answer
                    Button.DECLINE -> R.string.call_decline
                    Button.HANG_UP -> R.string.call_hang_up
                    Button.TO_PHONE -> R.string.call_to_phone
                }
            )
            canvas.drawText(label, r.centerX(), r.centerY() + buttonText.textSize * 0.36f, buttonText)
            x += bw + gap
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val i = buttonRects.indexOfFirst { it.contains(event.x, event.y) }
            if (i >= 0) {
                selected = i
                invalidate()
                activate()
            }
        }
        return true
    }

    private fun elapsed(): String {
        val start = state.callStartedAt
        if (start == 0L) return "00:00"
        val s = (SystemClock.elapsedRealtime() - start) / 1000
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private companion object {
        const val FADE_MS = 200L
    }
}
