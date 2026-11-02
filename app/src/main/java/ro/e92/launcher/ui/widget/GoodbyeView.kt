package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.content.ContextCompat
import ro.e92.launcher.R
import ro.e92.launcher.core.TripComputer
import java.util.Locale

/**
 * Rămas-bunul de la oprirea motorului: aceeași fotografie și aceleași fonturi
 * ca salutul de pornire, ca cele două să pară capetele aceleiași povești, plus
 * rezumatul drumului abia încheiat.
 *
 *     BMW iDRIVE
 *     Goodbye, Mr. Lazar.
 *     42.3 km   ·   48 min   ·   53 km/h   ·   6.1 l/100 km
 *
 * Desenat direct, fără layout: e un singur ecran static, iar fotografia e
 * bitmap-ul deja încărcat pentru meniul principal.
 */
class GoodbyeView(
    context: Context,
    private val photo: Bitmap?,
    greetingName: String,
    trip: TripComputer.Trip
) : View(context) {

    var onDismiss: (() -> Unit)? = null

    private val amber = ContextCompat.getColor(context, R.color.glow_core)

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint()
    private val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = amber
        typeface = context.resources.getFont(R.font.inter_semibold)
        letterSpacing = 0.38f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = context.resources.getFont(R.font.inter_display_light)
        letterSpacing = 0.01f
    }
    private val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB4B4B4.toInt()
        typeface = context.resources.getFont(R.font.inter_regular)
        letterSpacing = 0.04f
    }

    private val kicker = context.getString(R.string.intro_kicker)
    private val title =
        if (greetingName.isBlank()) context.getString(R.string.goodbye_no_name)
        else context.getString(R.string.goodbye) + " " + greetingName.trim() + "."
    private val stats = summary(trip)

    private val src = Rect()
    private val dst = RectF()

    init {
        isClickable = true
        isFocusable = false
        defaultFocusHighlightEnabled = false
        setOnClickListener { dismiss() }
        photo?.let { src.set(0, 0, it.width, it.height) }
        alpha = 0f
    }

    fun show() {
        animate().alpha(1f).setDuration(FADE_MS).start()
    }

    fun dismiss() {
        val cb = onDismiss ?: return
        onDismiss = null
        animate().alpha(0f).setDuration(FADE_MS).withEndAction { cb() }.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Întunecat spre stânga, unde stă textul; mașina din dreapta rămâne vizibilă.
        shade.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x33000000),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.BLACK)
        photo?.let { bmp ->
            val scale = maxOf(w / bmp.width, h / bmp.height)
            val bw = bmp.width * scale
            val bh = bmp.height * scale
            dst.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
            canvas.drawBitmap(bmp, src, dst, photoPaint)
        }
        canvas.drawRect(0f, 0f, w, h, shade)

        val x = w * TEXT_LEFT
        titlePaint.textSize = h * 0.092f
        kickerPaint.textSize = h * 0.034f
        statsPaint.textSize = h * 0.038f
        val base = h * TEXT_BASELINE
        canvas.drawText(kicker, x + h * 0.004f, base - titlePaint.textSize - h * 0.04f, kickerPaint)
        canvas.drawText(title, x, base, titlePaint)
        if (stats.isNotEmpty()) canvas.drawText(stats, x + h * 0.004f, base + h * 0.11f, statsPaint)
    }

    private fun summary(t: TripComputer.Trip): String {
        if (t.distanceKm < MIN_KM) return ""
        val parts = arrayListOf(String.format(Locale.US, "%.1f km", t.distanceKm))
        val min = t.drivingMs / 60_000
        if (min > 0) parts.add(if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min")
        t.avgSpeedKmh?.let { parts.add("$it km/h") }
        t.consumption?.let { parts.add(String.format(Locale.US, "%.1f l/100 km", it)) }
        return parts.joinToString("   ·   ")
    }

    private companion object {
        const val TEXT_LEFT = 0.065f
        const val TEXT_BASELINE = 0.43f
        const val FADE_MS = 400L
        const val MIN_KM = 0.2
    }
}
