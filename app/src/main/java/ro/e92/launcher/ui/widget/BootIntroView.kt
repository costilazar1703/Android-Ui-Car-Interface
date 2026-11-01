package ro.e92.launcher.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Animația de pornire: mașina se aprinde din întuneric, apare salutul, apoi
 * meniul se așază peste exact aceeași fotografie.
 *
 * Cronologia, în milisecunde (lungită: salutul trebuie să apuce să fie văzut):
 *
 *    0 –  700   o linie de lumină chihlimbar se deschide din centru, pe orizont
 *  300 – 2200   fotografia urcă din negru; camera „se așază" de la 1,14x la
 *               poziția finală a fundalului principal
 * 1100 – 2300   sigla, „BMW iDRIVE", apoi „Welcome back, Mr. Lazar"
 * 2400 – 3400   un reflex de lumină trece peste siglă și text
 * 3400 – 3700   rămâne pe ecran
 * 3700 – 4600   textul se stinge, apoi tot view-ul, dezvăluind meniul
 *
 * **Trecerea fără tăietură.** Fotografia se termină EXACT unde stă fundalul
 * ecranului principal — aceeași mărire, același pivot, aceeași deplasare de
 * parallax ([endScale], [endTranslationX]). Când view-ul se stinge, imaginea de
 * dedesubt e identică, deci singurul lucru care pare că apare sunt dalele.
 * Bitmap-ul e același obiect din BackgroundCache: zero memorie în plus.
 *
 * **Un singur View, un singur animator, zero alocări în onDraw.** Gradienții se
 * creează o dată, în coordonate unitare, și se poziționează prin matrice.
 *
 * Sigla e opțională: apare doar dacă există o resursă `boot_logo` (vezi
 * [logoBitmap]). Până atunci animația e fotografie + text.
 *
 * Orice apăsare o sare: animația e un salut, nu o barieră.
 */
class BootIntroView(
    context: Context,
    private val photo: Bitmap?,
    /** Transformarea finală a fundalului principal, ca trecerea să fie invizibilă. */
    private val endScale: Float,
    private val endTranslationX: Float,
    /** Numele din Setări; gol = doar „Welcome back". */
    greetingName: String
) : View(context) {

    var onFinished: (() -> Unit)? = null

    private val logo: Bitmap? = logoBitmap(context)

    private val amber = ContextCompat.getColor(context, R.color.glow_core)

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = Color.BLACK }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    /** Randul mic de sus: „BMW iDRIVE", chihlimbar, litere rarite. */
    private val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = amber
        typeface = context.resources.getFont(R.font.inter_semibold)
        letterSpacing = 0.38f
    }
    /** Tot salutul, intr-un singur font subtire - ca titlurile mari din iOS. */
    private val greetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = context.resources.getFont(R.font.inter_display_light)
        letterSpacing = 0.01f
    }

    private val kicker = context.getString(R.string.intro_kicker)
    // Spatiul se pune din cod, nu in resursa: Android taie spatiile de la
    // capatul resurselor (chiar si scrise  ), iar textul iesea „back,Mr.".
    private val greeting =
        if (greetingName.isBlank()) context.getString(R.string.intro_greeting_no_name)
        else context.getString(R.string.intro_greeting) + " " + greetingName.trim()

    // Gradienți unitari, poziționați la fiecare cadru prin matrice.
    private val unitLine = LinearGradient(
        -1f, 0f, 1f, 0f,
        intArrayOf(Color.TRANSPARENT, amber, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
    )
    private val unitSweep = LinearGradient(
        -1f, -0.5f, 1f, 0.5f,
        intArrayOf(Color.TRANSPARENT, Color.rgb(255, 232, 200), Color.TRANSPARENT),
        null, Shader.TileMode.CLAMP
    )
    private val m = Matrix()

    private val src = Rect()
    private val photoRect = RectF()
    private val textRect = RectF()
    private val logoRect = RectF()

    private var t = 0f
    private var animator: ValueAnimator? = null
    private var finishing = false

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        setOnClickListener { skip() }
        // Pe API 26+ un view care primește click devine AUTOMAT focusabil, iar
        // când primește focus din taste (tableta e mereu în „mod tastatură" —
        // rotița iDrive trimite DPAD) sistemul îi desenează peste un evidențiator
        // implicit: alb semi-transparent pe toată suprafața. Măsurat: negrul ieșea
        // (25,25,25). Restul aplicației evită asta cu focusable="false"; focusul
        // îl gestionează FocusEngine, nu Android.
        isFocusable = false
        defaultFocusHighlightEnabled = false
        photo?.let { src.set(0, 0, it.width, it.height) }
    }

    fun start() {
        if (animator != null || finishing) return
        animator = ValueAnimator.ofFloat(0f, TOTAL_MS).apply {
            duration = TOTAL_MS.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                alpha = 1f - phase(VIEW_FADE_FROM, TOTAL_MS)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish()
            })
            start()
        }
    }

    /** Sare la final, cu o stingere scurtă — nu taie brusc. */
    fun skip() {
        if (finishing) return
        animator?.cancel()
        finishing = true
        animate().alpha(0f).setDuration(SKIP_FADE_MS).withEndAction { finish() }.start()
    }

    private fun finish() {
        val cb = onFinished ?: return
        onFinished = null
        animate().cancel()
        cb()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // ---- 1. fotografia: din negru, cu camera care se așază ----
        // Doua curbe diferite: camera se misca repede si se aseaza (ease-out), dar
        // lumina urca LENT la inceput (smoothstep). Cu ease-out si pe lumina,
        // ~60% din ea venea in primele 130 ms - poza parea aprinsa brusc, nu
        // aprinsa treptat.
        val move = ease(phase(300f, 2200f))
        val light = smooth(phase(300f, 2200f))
        val reveal = move
        val bmp = photo
        if (bmp != null && move > 0f) {
            centerCropRect(bmp, w, h, photoRect)
            // Poziția finală = cea a fundalului principal: mărire pe pivot stânga
            // la mijlocul înălțimii, apoi deplasarea de parallax.
            val endL = endTranslationX + photoRect.left * endScale
            val endT = h / 2f + (photoRect.top - h / 2f) * endScale
            val endW = photoRect.width() * endScale
            val endH = photoRect.height() * endScale
            // Poziția de start: aceeași imagine mărită 1,14x în jurul centrului.
            val startW = photoRect.width() * START_ZOOM
            val startH = photoRect.height() * START_ZOOM
            val startL = w / 2f - startW / 2f
            val startT = h / 2f - startH / 2f
            val l = lerp(startL, endL, reveal)
            val tp = lerp(startT, endT, reveal)
            photoRect.set(l, tp, l + lerp(startW, endW, reveal), tp + lerp(startH, endH, reveal))
            canvas.drawBitmap(bmp, src, photoRect, photoPaint)
            // Negrul se retrage treptat de pe fotografie: aprindere, nu fade plat.
            shadePaint.alpha = (255 * (1f - light)).toInt()
            if (shadePaint.alpha > 0) canvas.drawRect(0f, 0f, w, h, shadePaint)
        }

        val horizon = h * 0.60f

        // ---- 2. linia de lumină pe orizont ----
        val grow = ease(phase(0f, 700f))
        val lineFade = 1f - phase(3700f, 4200f)
        if (grow > 0f && lineFade > 0f) {
            val half = w * 0.32f * grow
            m.setScale(half, 1f)
            m.postTranslate(w / 2f, horizon)
            unitLine.setLocalMatrix(m)
            linePaint.shader = unitLine
            for (i in LINE_WIDTHS.indices) {
                linePaint.strokeWidth = LINE_WIDTHS[i]
                linePaint.alpha = (255 * LINE_ALPHAS[i] * lineFade).toInt()
                canvas.drawLine(w / 2f - half, horizon, w / 2f + half, horizon, linePaint)
            }
        }

        val out = 1f - phase(3700f, 4200f)
        val logoIn = ease(phase(1100f, 1900f)) * out
        val kickerIn = ease(phase(1300f, 2000f)) * out
        val textIn = ease(phase(1500f, 2300f))
        val textAlpha = textIn * out
        if (logoIn <= 0f && textAlpha <= 0f && kickerIn <= 0f) return

        // ---- 3. dimensiuni si asezare ----
        //  [sigla]  BMW iDRIVE
        //           Welcome back, Mr. Lazar
        // Sigla in stanga blocului de text, ca un logo urmat de text - asa apare
        // la pornirea unui BMW cu iDrive. Aliniat la STANGA, pe cerul gol:
        // masina sta in dreapta fotografiei.
        val big = h * 0.092f
        greetPaint.textSize = big
        kickerPaint.textSize = h * 0.034f
        val lg = logo
        val logoSize = if (lg != null) h * LOGO_HEIGHT else 0f
        val logoGap = if (lg != null) h * 0.045f else 0f
        val x0 = w * TEXT_LEFT
        val tx = x0 + logoSize + logoGap

        // Textul urca putin in timp ce apare - miscarea „scumpa" din iOS.
        val baseline = h * TEXT_BASELINE + (1f - textIn) * h * 0.025f
        val kickerBase = h * TEXT_BASELINE - big - h * 0.04f
        val blockTop = kickerBase - kickerPaint.textSize
        val blockBottom = h * TEXT_BASELINE + big * 0.22f
        val total = greetPaint.measureText(greeting)
        textRect.set(tx, blockTop, tx + total, blockBottom)

        if (lg != null) {
            val cy = (blockTop + blockBottom) / 2f
            // Sigla vine si ea „din adanc": de la 0,9 la marimea finala.
            val s = logoSize * (0.9f + 0.1f * ease(phase(1100f, 1900f)))
            logoRect.set(x0 + (logoSize - s) / 2f, cy - s / 2f, x0 + (logoSize + s) / 2f, cy + s / 2f)
        } else {
            logoRect.setEmpty()
        }

        // ---- 4. un singur layer pentru sigla + text, ca reflexul sa le prinda pe amandoua ----
        val lx = minOf(if (lg != null) logoRect.left else tx, tx) - 6f
        val layer = canvas.saveLayer(lx, blockTop - 8f, textRect.right + 6f, maxOf(blockBottom, logoRect.bottom) + 8f, null)

        if (lg != null && logoIn > 0f) {
            logoPaint.alpha = (255 * logoIn).toInt()
            canvas.drawBitmap(lg, null, logoRect, logoPaint)
        }
        if (kickerIn > 0f) {
            kickerPaint.alpha = (255 * kickerIn).toInt()
            canvas.drawText(kicker, tx + h * 0.004f, kickerBase, kickerPaint)
        }
        if (textAlpha > 0f) {
            greetPaint.alpha = (245 * textAlpha).toInt()
            canvas.drawText(greeting, tx, baseline, greetPaint)
        }

        // ---- 5. reflexul, mascat pe sigla si litere ----
        val sweep = phase(2400f, 3400f)
        if (sweep > 0f && sweep < 1f) {
            val left = lx
            val right = textRect.right
            val band = (right - left) * 0.2f
            val x = left - band + (right - left + 2 * band) * sweep
            m.setScale(band, textRect.height() * 1.6f)
            m.postTranslate(x, textRect.centerY())
            unitSweep.setLocalMatrix(m)
            sweepPaint.shader = unitSweep
            sweepPaint.alpha = (200 * out).toInt()
            canvas.drawRect(left, blockTop - 8f, right + 6f, maxOf(blockBottom, logoRect.bottom) + 8f, sweepPaint)
        }
        canvas.restoreToCount(layer)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    /** Dreptunghiul în care centerCrop ar desena bitmap-ul — ca ImageView-ul de fundal. */
    private fun centerCropRect(bmp: Bitmap, w: Float, h: Float, out: RectF) {
        val scale = maxOf(w / bmp.width, h / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        out.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    }

    private fun phase(from: Float, to: Float): Float =
        ((t - from) / (to - from)).coerceIn(0f, 1f)

    /** Ease-out cubic: pornește repede, se așază lin. */
    private fun ease(x: Float): Float {
        val inv = 1f - x
        return 1f - inv * inv * inv
    }

    /** Smoothstep: incepe si se termina lin - pentru lumina care se aprinde. */
    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    companion object {

        private const val TOTAL_MS = 4600f
        private const val VIEW_FADE_FROM = 4100f
        private const val SKIP_FADE_MS = 180L
        private const val START_ZOOM = 1.14f

        /** Salutul: marginea stanga si linia de baza, ca fractiuni din ecran. */
        private const val TEXT_LEFT = 0.065f
        private const val TEXT_BASELINE = 0.43f

        /** Inaltimea siglei, ca fractiune din ecran - cat blocul de text de langa ea. */
        private const val LOGO_HEIGHT = 0.19f

        private val LINE_WIDTHS = floatArrayOf(10f, 4f, 1.6f)
        private val LINE_ALPHAS = floatArrayOf(0.10f, 0.30f, 1f)

        /**
         * Sigla vine dintr-o resursă căutată după NUME, nu din R.drawable: așa
         * codul compilează și fără ea. Când pui `boot_logo.png` în
         * drawable-nodpi, apare singură — fără nicio modificare aici.
         */
        fun logoBitmap(context: Context): Bitmap? {
            val id = context.resources.getIdentifier("boot_logo", "drawable", context.packageName)
            if (id == 0) return null
            return (ContextCompat.getDrawable(context, id) as? BitmapDrawable)?.bitmap
        }
    }
}
