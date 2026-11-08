package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Mașina care se rotește cu degetul: 72 de cadre (câte unul la 5°) dintr-un clip 360°,
 * în assets/car360. Tragi stânga/dreapta și mașina urmează degetul; dai drumul cu viteză
 * și continuă, apoi se oprește lin (inerție ca pe iOS).
 *
 * Inerția merge pe ceasul propriu (postOnAnimation + SystemClock), nu pe animatoarele
 * Android: pe unitate scala animațiilor poate fi 0, iar atunci un ValueAnimator sare direct
 * la final.
 */
class CarSpinView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Unghiul curent, în grade (0 = cadrul 0, fața). */
    private var angle = START_ANGLE
    private var velocityDegPerSec = 0f
    private var lastFrameTime = 0L

    private var downX = 0f
    private var angleAtDown = 0f
    private var tracker: VelocityTracker? = null
    private var dragging = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    /** Cadrele decodate, RGB_565: 72 × 640×320 ≈ 29 MB în cel mai rău caz, ținute cât trăiește view-ul. */
    private val cache = object : LruCache<Int, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount / 1024
    }

    private val decodeOptions = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    private fun frame(index: Int): Bitmap? {
        cache.get(index)?.let { return it }
        val bmp = runCatching {
            context.assets.open("car360/c%02d.jpg".format(index)).use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        }.getOrNull() ?: return null
        cache.put(index, bmp)
        return bmp
    }

    private fun currentIndex(): Int {
        val a = ((angle % 360f) + 360f) % 360f
        return (a / STEP_DEG).roundToInt() % FRAMES
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = frame(currentIndex()) ?: return
        // fitCenter, fără să depășească view-ul
        val scale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        src.set(0, 0, bmp.width, bmp.height)
        dst.set(left, top, left + w, top + h)
        canvas.drawBitmap(bmp, src, dst, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityDegPerSec = 0f
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                angleAtDown = angle
                dragging = false
                // Mașina își ține singură gestul: paginile nu trebuie să se miște sub ea.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dx = event.x - downX
                if (!dragging && abs(dx) > SLOP_PX * resources.displayMetrics.density) dragging = true
                if (dragging) {
                    angle = angleAtDown - dx * degPerPx()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                if (dragging) {
                    velocityDegPerSec = -vx * degPerPx()
                    if (abs(velocityDegPerSec) > MIN_SPIN_DEG_S) {
                        lastFrameTime = SystemClock.uptimeMillis()
                        postOnAnimation(spin)
                    }
                } else {
                    performClick()
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    /** O lățime de view trasă = o jumătate de tură. */
    private fun degPerPx(): Float = 180f / width.coerceAtLeast(1)

    private val spin = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastFrameTime) / 1000f).coerceIn(0f, 0.05f)
            lastFrameTime = now
            angle += velocityDegPerSec * dt
            velocityDegPerSec *= exp(-FRICTION * dt)
            invalidate()
            if (abs(velocityDegPerSec) > STOP_DEG_S) postOnAnimation(this)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(spin)
        cache.evictAll()
    }

    private companion object {
        const val FRAMES = 72
        const val STEP_DEG = 360f / FRAMES
        /** Cadrul de pornire: 3/4 față, ca în pozele de prezentare. */
        const val START_ANGLE = 50f
        const val CACHE_KB = 32 * 1024
        const val SLOP_PX = 6f
        const val MIN_SPIN_DEG_S = 60f
        const val STOP_DEG_S = 8f
        /** Cât de repede se oprește după ce dai drumul (1/s). */
        const val FRICTION = 2.6f
    }
}
