package ro.e92.launcher.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import ro.e92.launcher.R
import ro.e92.launcher.can.ModeScene
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost

/**
 * Confirmarea de mod, peste ecranul curent: sisteme oprite sau normale, Drive Sport sau Drive.
 * Stă [ModeBannerView.TOTAL_MS] și dispare singură; orice tastă o închide și ajunge mai departe.
 */
class ModeScreen(host: ScreenHost, private val scene: ModeScene) : Screen(host) {

    override val title: String get() = context.getString(titleRes(scene))
    override val isOverlay: Boolean get() = true

    private var dismissed = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View {
        val subtitle = when (scene) {
            ModeScene.DRIFT -> R.string.mode_traction_off
            ModeScene.NORMAL -> R.string.mode_traction_on
            else -> 0
        }
        // Mașina cu fum pentru modurile „tari", cea liniștită de pe Home pentru revenire.
        val photo = when (scene) {
            ModeScene.DRIFT, ModeScene.SPORT -> R.drawable.bg_drift
            else -> R.drawable.bg_home
        }
        return ModeBannerView(
            context,
            context.getString(titleRes(scene)),
            if (subtitle != 0) context.getString(subtitle) else null,
            photo
        ) { dismiss() }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }
    }

    override fun onShow() {
        (root as? ModeBannerView)?.play()
    }

    override fun onHide() {
        (root as? ModeBannerView)?.stop()
    }

    override fun focusTargets(): List<FocusTarget> = emptyList()

    override fun onAction(action: LauncherAction): Boolean {
        dismiss()
        return false
    }

    private fun dismiss() {
        if (dismissed) return
        dismissed = true
        host.pop()
    }

    private fun titleRes(scene: ModeScene) = when (scene) {
        ModeScene.DRIFT -> R.string.mode_drift
        ModeScene.NORMAL -> R.string.mode_normal
        ModeScene.SPORT -> R.string.mode_sport
        ModeScene.DRIVE -> R.string.mode_drive
    }
}

/**
 * Fotografia, titlul în stânga cu fontul salutului de la pornire, linia portocalie și
 * reflexul alb care trece o dată peste litere.
 *
 * Totul merge pe ceasul propriu, cadru cu cadru: pe unitate scara animațiilor Android poate
 * fi 0, iar atunci Animator-ele sar direct la final.
 */
class ModeBannerView(
    context: Context,
    private val title: String,
    private val subtitle: String?,
    photoRes: Int,
    private val onDone: () -> Unit
) : View(context) {

    private val accent = ContextCompat.getColor(context, R.color.glow_core)

    // Fotografiile au 1920x720; la jumătate ajung pentru ecranul de 1280x480 pe durata scurtă a scenei.
    private val photo: Bitmap? = runCatching {
        BitmapFactory.decodeResource(resources, photoRes, BitmapFactory.Options().apply {
            inSampleSize = 1
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }.getOrNull()

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }

    /**
     * Titlul în relief: litere groase, aplecate, cu față metalică, ramă deschisă și o
     * grosime portocalie trasă în jos-dreapta. Cele trei vopsele au aceeași formă de literă.
     */
    private fun titleStyle() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = context.resources.getFont(R.font.inter_semibold)
        isFakeBoldText = true
        textSkewX = TITLE_SKEW
        letterSpacing = 0.015f
    }
    private val titlePaint = titleStyle()
    private val depthPaint = titleStyle()
    private val rimPaint = titleStyle().apply { color = Color.rgb(190, 195, 203) }
    private var faceFor = 0f

    /** Rândul mic, ca „BMW iDRIVE" din salut: semibold, litere rărite, portocaliu. */
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        typeface = context.resources.getFont(R.font.inter_semibold)
        letterSpacing = 0.38f
    }

    private val unitSweep = LinearGradient(
        -1f, -0.5f, 1f, 0.5f,
        intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
    )
    private val m = Matrix()
    private val photoRect = RectF()
    private val textRect = RectF()

    private var startedAt = 0L
    private var running = false
    private var finished = false
    private val subText = subtitle?.uppercase()

    fun play() {
        startedAt = SystemClock.uptimeMillis()
        running = true
        finished = false
        invalidate()
    }

    fun stop() {
        running = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Întuneric din stânga, sub text; mașina din dreapta rămâne curată.
        scrimPaint.shader = LinearGradient(
            0f, 0f, w * 0.7f, 0f,
            intArrayOf(Color.argb(215, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val now = if (startedAt == 0L) 0f else (SystemClock.uptimeMillis() - startedAt).toFloat()
        val all = ease(now, 0f, FADE_IN_MS) * (1f - ease(now, TOTAL_MS - EXIT_MS, EXIT_MS))

        // ---- fotografia: se așază încet, de la puțin mărită ----
        canvas.drawColor(Color.argb((255 * all).toInt(), 0, 0, 0))
        photo?.let { bmp ->
            val scale = maxOf(w / bmp.width, h / bmp.height) *
                (PHOTO_START_SCALE - (PHOTO_START_SCALE - 1f) * ease(now, 0f, TOTAL_MS))
            val pw = bmp.width * scale
            val ph = bmp.height * scale
            photoRect.set((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f)
            photoPaint.alpha = (255 * all).toInt()
            canvas.drawBitmap(bmp, null, photoRect, photoPaint)
        }
        scrimPaint.alpha = (255 * all).toInt()
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        // ---- așezare: titlu, linie, rând mic; totul aliniat la stânga ----
        val big = h * TITLE_SIZE
        titlePaint.textSize = big
        depthPaint.textSize = big
        rimPaint.textSize = big
        val rim = h * RIM_WIDTH
        subPaint.textSize = h * SUB_SIZE
        val x = w * TEXT_LEFT
        val titleW = titlePaint.measureText(title)
        val blockH = big * 0.74f + (if (subText != null) h * (LINE_GAP + SUB_GAP) + subPaint.textSize * 0.72f else h * LINE_GAP)
        val top = (h - blockH) / 2f + h * 0.02f
        val titleBase = top + big * 0.74f
        val lineY = titleBase + h * LINE_GAP
        val subBase = lineY + h * SUB_GAP + subPaint.textSize * 0.72f

        val titleIn = ease(now, TITLE_AT_MS, TITLE_MS)
        val lineIn = ease(now, LINE_AT_MS, LINE_MS)
        val subIn = ease(now, SUB_AT_MS, SUB_MS)

        val depth = h * DEPTH
        textRect.set(x, top - big * 0.2f, x + titleW + depth * 2f, if (subText != null) subBase + h * 0.02f else lineY)
        // Umbra se desenează sub strat, ca reflexul alb să prindă doar literele, nu și umbra.
        if (titleIn > 0f) {
            val sa = (255 * titleIn * all).toInt()
            depthPaint.color = Color.BLACK
            depthPaint.alpha = sa
            depthPaint.setShadowLayer(h * 0.03f, 0f, h * 0.012f, Color.argb(sa, 0, 0, 0))
            canvas.drawText(title, x + depth, titleBase + (1f - titleIn) * h * 0.03f + depth, depthPaint)
            depthPaint.clearShadowLayer()
        }
        val layer = canvas.saveLayer(
            textRect.left - 8f, textRect.top - 8f, textRect.right + 8f, textRect.bottom + 8f, null
        )
        if (titleIn > 0f) {
            // Urcă puțin cât apare, ca salutul de la pornire.
            val ty = titleBase + (1f - titleIn) * h * 0.03f
            val alpha = (255 * titleIn * all).toInt()
            // Grosimea: copii suprapuse, din ce în ce mai închise spre fund; ultima aruncă umbra.
            for (i in DEPTH_STEPS downTo 1) {
                val f = i / DEPTH_STEPS.toFloat()
                depthPaint.color = Color.rgb(
                    (Color.red(accent) * (0.78f - 0.6f * f)).toInt(),
                    (Color.green(accent) * (0.78f - 0.6f * f)).toInt(),
                    (Color.blue(accent) * (0.78f - 0.6f * f)).toInt()
                )
                depthPaint.alpha = alpha
                canvas.drawText(title, x + depth * f, ty + depth * f, depthPaint)
            }
            // Fața metalică: lumină sus, o dungă de umbră sub mijloc, lumină din nou la bază.
            if (faceFor != ty) {
                faceFor = ty
                titlePaint.shader = LinearGradient(
                    0f, ty - big * 0.74f, 0f, ty,
                    // Hyper gray: gri metalic închis, ca jantele din fotografie.
                    intArrayOf(
                        Color.rgb(158, 162, 169), Color.rgb(112, 117, 125), Color.rgb(56, 60, 67),
                        Color.rgb(86, 91, 99), Color.rgb(124, 129, 137)
                    ),
                    floatArrayOf(0f, 0.42f, 0.6f, 0.78f, 1f), Shader.TileMode.CLAMP
                )
            }
            // Rama: o copie deschisă cu puțin mai sus-stânga, sub față. Un contur desenat cu
            // STROKE arăta liniile dintre bucățile literelor, în interiorul lor.
            rimPaint.alpha = (alpha * 0.75f).toInt()
            canvas.drawText(title, x - rim, ty - rim, rimPaint)
            titlePaint.alpha = alpha
            canvas.drawText(title, x, ty, titlePaint)
        }
        if (subText != null && subIn > 0f) {
            subPaint.alpha = (255 * subIn * all).toInt()
            canvas.drawText(subText, x + h * 0.004f, subBase, subPaint)
        }

        // ---- reflexul alb, mascat pe litere ----
        val sweep = ((now - SWEEP_AT_MS) / SWEEP_MS)
        if (sweep > 0f && sweep < 1f) {
            val band = textRect.width() * 0.22f
            val sx = textRect.left - band + (textRect.width() + 2 * band) * sweep
            m.setScale(band, textRect.height() * 1.6f)
            m.postTranslate(sx, textRect.centerY())
            unitSweep.setLocalMatrix(m)
            sweepPaint.shader = unitSweep
            sweepPaint.alpha = (255 * all).toInt()
            canvas.drawRect(textRect.left - 8f, textRect.top - 8f, textRect.right + 8f, textRect.bottom + 8f, sweepPaint)
        }
        canvas.restoreToCount(layer)

        // ---- linia: se trage din stânga, se stinge spre dreapta ----
        if (lineIn > 0f) {
            val lw = titleW * lineIn
            linePaint.shader = LinearGradient(
                x, 0f, x + lw, 0f,
                intArrayOf(accent, accent, Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            linePaint.alpha = (255 * all).toInt()
            canvas.drawRect(x, lineY - LINE_PX / 2f, x + lw, lineY + LINE_PX / 2f, linePaint)
        }

        if (!running) return
        if (now >= TOTAL_MS) {
            if (!finished) {
                finished = true
                running = false
                post(onDone)
            }
        } else {
            postInvalidateOnAnimation()
        }
    }

    /** 0 până la [from], 1 după [length] ms, cu încetinire spre final. */
    private fun ease(now: Float, from: Float, length: Float): Float {
        val t = ((now - from) / length).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    companion object {
        const val TOTAL_MS = 2_000f
        private const val FADE_IN_MS = 220f
        private const val EXIT_MS = 300f
        private const val PHOTO_START_SCALE = 1.06f
        private const val TITLE_AT_MS = 90f
        private const val TITLE_MS = 420f
        private const val LINE_AT_MS = 260f
        private const val LINE_MS = 480f
        private const val SUB_AT_MS = 380f
        private const val SUB_MS = 360f
        private const val SWEEP_AT_MS = 560f
        private const val SWEEP_MS = 900f

        private const val TEXT_LEFT = 0.066f
        private const val TITLE_SIZE = 0.17f
        private const val SUB_SIZE = 0.042f
        private const val LINE_GAP = 0.062f
        private const val TITLE_SKEW = -0.17f
        private const val DEPTH = 0.014f
        private const val DEPTH_STEPS = 7
        private const val RIM_WIDTH = 0.0026f
        private const val SUB_GAP = 0.045f
        private const val LINE_PX = 3f
    }
}
