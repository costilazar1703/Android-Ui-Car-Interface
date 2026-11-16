package ro.e92.launcher.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SweepGradient
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
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
        // Drive Sport / Drive: doar un card jos pe mijloc, peste ecranul curent, care rămâne vizibil.
        if (scene == ModeScene.SPORT || scene == ModeScene.DRIVE) {
            return ModeCardView(context, scene == ModeScene.SPORT, context.getString(titleRes(scene))) { dismiss() }
                .apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
        }
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
        (root as? ModeCardView)?.play()
    }

    override fun onHide() {
        (root as? ModeBannerView)?.stop()
        (root as? ModeCardView)?.stop()
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
        const val TOTAL_MS = 2_500f
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

/**
 * Cardul de mod de condus, jos pe mijloc, peste ecranul curent (care rămâne vizibil și primește
 * atingerile). Sticlă închisă cu:
 *  - o lumină care aleargă o dată în jurul marginii, apoi rămâne o margine stinsă în culoarea modului;
 *  - în stânga un ecuson: la DS un turometru simplu care urcă în zona roșie, la D o undă liniștită;
 *  - la DS dâre de viteză care trec prin card la intrare, la D un halou care respiră;
 *  - numele modului, selectorul D / DS cu bucata aprinsă care alunecă la modul nou;
 *  - jos o linie subțire care se scurtează cât timp mai stă cardul pe ecran.
 *
 * Desenat pe ceasul propriu, cadru cu cadru, ca [ModeBannerView]: pe unitate scara animațiilor
 * Android poate fi 0.
 */
class ModeCardView(
    context: Context,
    private val sport: Boolean,
    private val title: String,
    private val onDone: () -> Unit
) : View(context) {

    private val orange = ContextCompat.getColor(context, R.color.glow_core)
    /** DS: portocaliul interfeței. D: gri-oțel rece. */
    private val accent = if (sport) orange else Color.rgb(170, 184, 199)

    private val semibold = context.resources.getFont(R.font.inter_semibold)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = semibold
        letterSpacing = 0.32f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = semibold
        letterSpacing = 0.005f
    }
    private val segTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = semibold
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.06f
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    private val unitSweep = LinearGradient(
        -1f, -0.5f, 1f, 0.5f,
        intArrayOf(Color.TRANSPARENT, Color.argb(210, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
    )
    private val m = Matrix()
    private val card = RectF()
    private val r = RectF()
    private val clip = Path()
    private val needle = Path()
    private val wave = Path()
    private val kicker = context.getString(R.string.mode_kicker).uppercase()

    /** DS stă mai mult: turometrul are nevoie de timp să urce (cerut 2026-11-17). */
    private val total = if (sport) SPORT_TOTAL_MS else DRIVE_TOTAL_MS

    private var startedAt = 0L
    private var running = false
    private var finished = false

    fun play() {
        startedAt = SystemClock.uptimeMillis()
        running = true
        finished = false
        invalidate()
    }

    fun stop() {
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val u = h / 480f
        val now = if (startedAt == 0L) 0f else (SystemClock.uptimeMillis() - startedAt).toFloat()

        val enter = ease(now, 0f, ENTER_MS)
        val pop = back(now, 0f, ENTER_MS + 120f)
        val exit = ease(now, total - EXIT_MS, EXIT_MS)
        val alpha = enter * (1f - exit)
        if (alpha <= 0f) return tick(now)
        val a255 = (255 * alpha).toInt()

        // ---- așezare: jos pe mijloc; urcă cu un mic recul la intrare, coboară la ieșire ----
        val cw = CARD_W * u
        val ch = CARD_H * u
        val cx = w / 2f
        val cy = h - BOTTOM_GAP * u - ch / 2f + (1f - enter) * 30f * u + exit * 16f * u
        val scale = 0.9f + 0.1f * pop
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        card.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
        val rad = ch / 2f

        // Umbra: straturi din ce în ce mai largi (setShadowLayer pe forme nu merge cu accelerare
        // hardware pe Android 8.1).
        for (i in 1..7) {
            shadowPaint.alpha = (15 * alpha).toInt()
            val g = i * 4f * u
            r.set(card.left - g, card.top - g + 8f * u, card.right + g, card.bottom + g + 8f * u)
            canvas.drawRoundRect(r, rad + g, rad + g, shadowPaint)
        }
        // Haloul de dedesubt: la D respiră încet, la DS se aprinde puternic și se liniștește.
        val breathe = if (sport) 0.55f + 0.45f * (1f - ease(now, 300f, 900f))
        else 0.55f + 0.2f * kotlin.math.sin(now / 420f).toFloat()
        val glowR = cw * 0.55f
        fillPaint.shader = RadialGradient(
            cx, card.bottom, glowR,
            intArrayOf(withAlpha(accent, (95 * alpha * breathe).toInt()), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.scale(1f, 0.3f, cx, card.bottom)
        canvas.drawCircle(cx, card.bottom, glowR, fillPaint)
        canvas.restore()
        fillPaint.shader = null

        // ---- sticla ----
        cardPaint.shader = LinearGradient(
            0f, card.top, 0f, card.bottom,
            Color.argb((246 * alpha).toInt(), 34, 36, 41), Color.argb((246 * alpha).toInt(), 11, 12, 15),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(card, rad, rad, cardPaint)

        clip.reset()
        clip.addRoundRect(card, rad, rad, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        // Luciul de sus al sticlei.
        fillPaint.shader = LinearGradient(
            0f, card.top, 0f, card.top + ch * 0.5f,
            Color.argb((22 * alpha).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(card.left, card.top, card.right, card.top + ch * 0.5f, fillPaint)
        fillPaint.shader = null
        if (sport) drawStreaks(canvas, now, u, alpha)
        // Timpul rămas: o linie subțire pe fundul cardului care se scurtează spre centru.
        val left = 1f - ((now - ENTER_MS) / (total - EXIT_MS - ENTER_MS)).coerceIn(0f, 1f)
        if (left > 0f) {
            val half = (cw / 2f - rad) * left
            fillPaint.color = withAlpha(accent, (150 * alpha).toInt())
            canvas.drawRect(cx - half, card.bottom - 2.4f * u, cx + half, card.bottom, fillPaint)
        }
        canvas.restore()

        // ---- marginea: o lumină aleargă o dată în jur, apoi rămâne stinsă în culoarea modului ----
        strokePaint.strokeWidth = 1.4f * u
        strokePaint.shader = null
        strokePaint.color = Color.argb((40 * alpha).toInt(), 255, 255, 255)
        canvas.drawRoundRect(card, rad, rad, strokePaint)
        val run = ((now - RUN_AT_MS) / RUN_MS)
        val settle = ease(now, RUN_AT_MS + RUN_MS * 0.7f, 500f)
        strokePaint.color = withAlpha(accent, (90 * alpha * settle).toInt())
        canvas.drawRoundRect(card, rad, rad, strokePaint)
        if (run > 0f && run < 1f) {
            val comet = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(accent, a255), Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.72f, 0.94f, 0.985f, 1f)
            )
            m.setRotate(-90f + 360f * ease(now, RUN_AT_MS, RUN_MS), cx, cy)
            comet.setLocalMatrix(m)
            strokePaint.shader = comet
            strokePaint.strokeWidth = 2.6f * u
            canvas.drawRoundRect(card, rad, rad, strokePaint)
            strokePaint.shader = null
        }

        // ---- ecusonul din stânga ----
        val bR = ch * if (sport) 0.42f else 0.36f
        val bcx = card.left + rad
        fillPaint.shader = RadialGradient(
            bcx, cy - bR * 0.3f, bR * 1.2f,
            Color.argb((255 * alpha).toInt(), 44, 47, 54), Color.argb((255 * alpha).toInt(), 16, 17, 21),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(bcx, cy, bR, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = 1.6f * u
        strokePaint.color = withAlpha(accent, (130 * alpha).toInt())
        canvas.drawCircle(bcx, cy, bR, strokePaint)
        if (sport) drawGauge(canvas, now, bcx, cy, bR, u, alpha) else drawWave(canvas, now, bcx, cy, bR, u, alpha)

        // ---- textul ----
        val tx = bcx + bR + 22f * u
        kickerPaint.textSize = 13f * u
        kickerPaint.color = Color.rgb(128, 133, 141)
        kickerPaint.alpha = a255
        titlePaint.textSize = 35f * u
        val textIn = ease(now, TEXT_AT_MS, TEXT_MS)
        val kickY = cy - 11f * u
        val titleY = cy + 25f * u + (1f - textIn) * 10f * u
        canvas.drawText(kicker, tx, kickY, kickerPaint)
        val titleW = titlePaint.measureText(title)
        val tTop = titleY - 38f * u
        val tBottom = titleY + 10f * u
        val layer = canvas.saveLayer(tx - 4f, tTop, tx + titleW + 4f, tBottom, null)
        titlePaint.shader = LinearGradient(
            0f, tTop, 0f, titleY,
            Color.rgb(246, 247, 249), Color.rgb(196, 200, 207), Shader.TileMode.CLAMP
        )
        titlePaint.alpha = (255 * alpha * textIn).toInt()
        canvas.drawText(title, tx, titleY, titlePaint)
        val sweep = (now - SWEEP_AT_MS) / SWEEP_MS
        if (sweep > 0f && sweep < 1f) {
            val band = titleW * 0.3f
            val sx = tx - band + (titleW + 2 * band) * sweep
            m.setScale(band, 56f * u)
            m.postTranslate(sx, titleY - 14f * u)
            unitSweep.setLocalMatrix(m)
            sweepPaint.shader = unitSweep
            sweepPaint.alpha = a255
            canvas.drawRect(tx - 4f, tTop, tx + titleW + 4f, tBottom, sweepPaint)
        }
        canvas.restoreToCount(layer)

        // ---- selectorul D / DS ----
        val segW = 60f * u
        val segH = 38f * u
        val gap = 4f * u
        val sx2 = card.right - rad * 0.62f - segW * 2 - gap
        val sy = cy - segH / 2f
        r.set(sx2 - 4f * u, sy - 4f * u, sx2 + segW * 2 + gap + 4f * u, sy + segH + 4f * u)
        fillPaint.color = Color.argb((130 * alpha).toInt(), 0, 0, 0)
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fillPaint)
        val slide = back(now, SLIDE_AT_MS, SLIDE_MS)
        val fromX = if (sport) sx2 else sx2 + segW + gap
        val toX = if (sport) sx2 + segW + gap else sx2
        val px = fromX + (toX - fromX) * slide
        r.set(px, sy, px + segW, sy + segH)
        fillPaint.color = withAlpha(accent, a255)
        canvas.drawRoundRect(r, segH / 2f, segH / 2f, fillPaint)
        segTextPaint.textSize = 16.5f * u
        val baseY = sy + segH / 2f + segTextPaint.textSize * 0.36f
        val lit = ease(now, SLIDE_AT_MS, SLIDE_MS)
        drawSeg(canvas, "D", sx2 + segW / 2f, baseY, !sport, lit, alpha)
        drawSeg(canvas, "DS", sx2 + segW * 1.5f + gap, baseY, sport, lit, alpha)

        canvas.restore()
        tick(now)
    }

    /**
     * DS: turometru după desenul ales de utilizator (2026-11-17): două arcuri subțiri, groase sus
     * și ascuțite la capete, și un ac subțire. Arcurile se trag de la stânga la dreapta, acul urcă
     * în viteză, scade puțin ca la o schimbare de treaptă, apoi intră în zona roșie și tremură
     * acolo; arcul interior se umple cu portocaliu după ac.
     */
    private fun drawGauge(canvas: Canvas, now: Float, cx: Float, cy: Float, bR: Float, u: Float, alpha: Float) {
        val rOut = bR * 0.72f
        val rIn = bR * 0.54f
        val c0 = cy + rOut * 0.42f
        val reveal = ease(now, GAUGE_AT_MS, 420f)
        if (reveal <= 0f) return
        val a255 = (255 * alpha).toInt()

        val outer = crescent(cx, c0, rOut, 3.3f * u)
        val inner = crescent(cx, c0, rIn, 2.7f * u)

        // Arcurile apar de la stânga la dreapta.
        canvas.save()
        canvas.clipPath(wedge(cx, c0, rOut * 1.4f, 180f, 180f * reveal))
        fillPaint.color = Color.argb((235 * alpha).toInt(), 238, 240, 244)
        canvas.drawPath(outer, fillPaint)
        fillPaint.color = Color.argb((150 * alpha).toInt(), 238, 240, 244)
        canvas.drawPath(inner, fillPaint)
        canvas.restore()

        val f = revLevel(now - GAUGE_AT_MS)
        val angle = 180f + 180f * f

        run {
            // Zona roșie: capătul din dreapta al arcului exterior; se aprinde cât acul e în ea.
            val inRed = ((f - RED_FROM) / (1f - RED_FROM)).coerceIn(0f, 1f)
            val flicker = 0.75f + 0.25f * kotlin.math.sin(now / 38f).toFloat()
            canvas.save()
            canvas.clipPath(wedge(cx, c0, rOut * 1.4f, 180f + 180f * RED_FROM, 180f * (1f - RED_FROM) * reveal))
            fillPaint.color = Color.argb((255 * alpha * (0.55f + 0.45f * inRed * flicker)).toInt(), 232, 36, 27)
            canvas.drawPath(outer, fillPaint)
            canvas.restore()
            // Arcul interior se umple după ac.
            canvas.save()
            canvas.clipPath(wedge(cx, c0, rOut * 1.4f, 180f, angle - 180f))
            fillPaint.color = withAlpha(accent, a255)
            canvas.drawPath(inner, fillPaint)
            canvas.restore()
        }

        // Acul: subțire, mai lat la bază, cu o coadă scurtă dincolo de centru.
        val rad = Math.toRadians(angle.toDouble())
        val dx = kotlin.math.cos(rad).toFloat()
        val dy = kotlin.math.sin(rad).toFloat()
        val len = rIn * 0.95f
        val tail = rIn * 0.22f
        val wb = 2.1f * u
        val wt = 0.6f * u
        needle.reset()
        needle.moveTo(cx - dx * tail - dy * wb, c0 - dy * tail + dx * wb)
        needle.lineTo(cx + dx * len - dy * wt, c0 + dy * len + dx * wt)
        needle.lineTo(cx + dx * len + dy * wt, c0 + dy * len - dx * wt)
        needle.lineTo(cx - dx * tail + dy * wb, c0 - dy * tail - dx * wb)
        needle.close()
        fillPaint.color = Color.argb(a255, 245, 246, 248)
        canvas.drawPath(needle, fillPaint)
    }

    /**
     * Turația acului la DS, 0 = stânga, 1 = capătul din dreapta: urcă repede, scade puțin la
     * schimbarea de treaptă, urcă în zona roșie și tremură acolo.
     */
    private fun revLevel(t: Float): Float {
        val s = t - NEEDLE_DELAY_MS
        if (s <= 0f) return 0.02f
        return when {
            // Urcă lin, ca o turație care crește.
            s < 1_200f -> 0.02f + 0.6f * smooth(s / 1_200f)
            // Schimbarea de treaptă: scade puțin.
            s < 1_550f -> 0.62f - 0.1f * smooth((s - 1_200f) / 350f)
            // Urcă din nou, până în zona roșie.
            s < 2_850f -> 0.52f + 0.42f * smooth((s - 1_550f) / 1_300f)
            // Stă în zona roșie, cu un tremur abia vizibil.
            else -> 0.94f + 0.006f * kotlin.math.sin((s - 2_850f) / 55f).toFloat()
        }
    }

    /** Pornește lin și se oprește lin. */
    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun easeT(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x) * (1f - x)
    }

    /**
     * Semicercul de sus, subțiat spre capete: discul de sus minus un cerc puțin mai mic, coborât.
     * Sus are grosimea [t], la capete rămâne o treime, ca zona roșie să se vadă.
     */
    private fun crescent(cx: Float, cy: Float, r: Float, t: Float): Path {
        val half = Path()
        half.moveTo(cx - r, cy)
        half.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f, false)
        half.close()
        val cut = Path()
        cut.addCircle(cx, cy + t * 0.67f, r - t * 0.33f, Path.Direction.CW)
        half.op(cut, Path.Op.DIFFERENCE)
        return half
    }

    /** O felie din cerc, pentru tăiat arcurile pe unghi. */
    private fun wedge(cx: Float, cy: Float, r: Float, start: Float, sweep: Float): Path {
        val p = Path()
        if (sweep <= 0f) return p
        p.moveTo(cx, cy)
        p.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, false)
        p.close()
        return p
    }

    /** D: o undă lină care curge încet spre dreapta. */
    private fun drawWave(canvas: Canvas, now: Float, cx: Float, cy: Float, r: Float, u: Float, alpha: Float) {
        val half = r * 0.62f
        val amp = r * 0.2f * ease(now, GAUGE_AT_MS, 500f)
        val shift = now / 380f
        wave.reset()
        var first = true
        var x = -half
        while (x <= half) {
            val t = x / half
            val fade = 1f - t * t
            val y = cy + amp * fade * kotlin.math.sin(t * 3.4f - shift).toFloat()
            if (first) wave.moveTo(cx + x, y) else wave.lineTo(cx + x, y)
            first = false
            x += 1.5f * u
        }
        iconPaint.strokeWidth = 3.2f * u
        iconPaint.color = withAlpha(accent, (255 * alpha).toInt())
        canvas.drawPath(wave, iconPaint)
    }

    /** DS: câteva dâre de lumină care trec prin card de la stânga la dreapta, la intrare. */
    private fun drawStreaks(canvas: Canvas, now: Float, u: Float, alpha: Float) {
        for ((i, y) in STREAK_Y.withIndex()) {
            val t = (now - STREAK_AT_MS - i * 70f) / STREAK_MS
            if (t <= 0f || t >= 1f) continue
            val len = card.width() * 0.32f
            val x = card.left - len + (card.width() + len) * ease(t * STREAK_MS, 0f, STREAK_MS)
            val yy = card.top + card.height() * y
            fillPaint.shader = LinearGradient(
                x, 0f, x + len, 0f,
                Color.TRANSPARENT, withAlpha(accent, (150 * alpha * (1f - t)).toInt()), Shader.TileMode.CLAMP
            )
            canvas.drawRect(x, yy - 0.8f * u, x + len, yy + 0.8f * u, fillPaint)
        }
        fillPaint.shader = null
    }

    /** Textul bucății: deschis pe culoarea aprinsă, gri pe fundal; trece de la una la alta cu glisarea. */
    private fun drawSeg(canvas: Canvas, label: String, x: Float, y: Float, target: Boolean, slide: Float, alpha: Float) {
        val lit = if (target) slide else 1f - slide
        val onLit = if (sport) Color.WHITE else Color.rgb(16, 18, 22)
        val idle = Color.rgb(140, 145, 153)
        segTextPaint.color = blend(idle, onLit, lit)
        segTextPaint.alpha = (255 * alpha).toInt()
        canvas.drawText(label, x, y, segTextPaint)
    }

    private fun tick(now: Float) {
        if (!running) return
        if (now >= total) {
            if (!finished) {
                finished = true
                running = false
                post(onDone)
            }
        } else {
            postInvalidateOnAnimation()
        }
    }

    private fun withAlpha(color: Int, a: Int) =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private fun ease(now: Float, from: Float, length: Float): Float {
        val t = ((now - from) / length).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    /** Ca [ease], dar trece puțin de capăt și revine: un recul mic, ca la iOS. */
    private fun back(now: Float, from: Float, length: Float): Float {
        val t = ((now - from) / length).coerceIn(0f, 1f) - 1f
        val s = 1.6f
        return 1f + t * t * ((s + 1f) * t + s)
    }

    private companion object {
        const val TOTAL_MS = ModeBannerView.TOTAL_MS
        const val SPORT_TOTAL_MS = 5_000f
        const val DRIVE_TOTAL_MS = 3_500f
        const val ENTER_MS = 380f
        const val EXIT_MS = 320f
        const val RUN_AT_MS = 180f
        const val RUN_MS = 1_000f
        const val GAUGE_AT_MS = 200f
        /** Acul pornește după ce arcurile s-au tras. */
        const val NEEDLE_DELAY_MS = 350f
        /** De unde începe zona roșie, ca parte din arc. */
        const val RED_FROM = 0.84f
        const val TEXT_AT_MS = 140f
        const val TEXT_MS = 380f
        const val SLIDE_AT_MS = 360f
        const val SLIDE_MS = 460f
        const val SWEEP_AT_MS = 700f
        const val SWEEP_MS = 850f
        const val STREAK_AT_MS = 120f
        const val STREAK_MS = 520f
        val STREAK_Y = floatArrayOf(0.26f, 0.5f, 0.71f, 0.38f)

        const val CARD_W = 620f
        const val CARD_H = 100f
        /** Distanța de la marginea de jos a ecranului; pe Dashboard cardul lasă cifrele vizibile. */
        const val BOTTOM_GAP = 12f
    }
}
