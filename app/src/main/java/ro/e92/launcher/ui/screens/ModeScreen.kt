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
 * Cardul de mod de condus, ca la navigațiile noi: sticlă închisă jos pe mijloc, un cadran mic
 * cu acul care urcă (DS) sau coboară (D), numele modului și selectorul D / DS cu bucata aprinsă
 * care alunecă la modul nou. Ecranul de dedesubt rămâne vizibil și primește atingerile.
 *
 * Desenat pe ceasul propriu, cadru cu cadru, ca [ModeBannerView].
 */
class ModeCardView(
    context: Context,
    private val sport: Boolean,
    private val title: String,
    private val onDone: () -> Unit
) : View(context) {

    private val orange = ContextCompat.getColor(context, R.color.glow_core)
    /** DS: portocaliul interfeței. D: gri-oțel rece, liniștit. */
    private val accent = if (sport) orange else Color.rgb(170, 184, 199)

    private val semibold = context.resources.getFont(R.font.inter_semibold)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = semibold
        letterSpacing = 0.3f
        color = Color.rgb(128, 133, 141)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = semibold
        letterSpacing = 0.01f
        color = Color.rgb(232, 234, 238)
    }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        intArrayOf(Color.TRANSPARENT, Color.argb(200, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
    )
    private val m = Matrix()
    private val card = RectF()
    private val r = RectF()
    private val kicker = context.getString(R.string.mode_kicker).uppercase()

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
        val exit = ease(now, TOTAL_MS - EXIT_MS, EXIT_MS)
        val alpha = enter * (1f - exit)
        if (alpha <= 0f) return tick(now)

        // ---- cardul: urcă din jos și se mărește puțin cât apare, coboară cât pleacă ----
        val cw = CARD_W * u
        val ch = CARD_H * u
        val cx = w / 2f
        val cy = h * CARD_Y + (1f - enter) * 26f * u + exit * 14f * u
        val scale = 0.94f + 0.06f * enter
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        card.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f)
        val rad = ch / 2f

        // Umbra moale: câteva straturi din ce în ce mai largi (setShadowLayer pe forme nu merge
        // cu accelerare hardware pe Android 8.1).
        for (i in 1..6) {
            shadowPaint.alpha = (16 * alpha).toInt()
            val g = i * 4f * u
            r.set(card.left - g, card.top - g + 6f * u, card.right + g, card.bottom + g + 6f * u)
            canvas.drawRoundRect(r, rad + g, rad + g, shadowPaint)
        }
        // Halou slab în culoarea modului, sub card.
        // Elipsă turtită: cercul se desenează întreg, iar scara pe verticală îl aplatizează.
        val glowR = cw * 0.5f
        glowPaint.shader = RadialGradient(
            cx, card.bottom, glowR,
            intArrayOf(withAlpha(accent, (60 * alpha).toInt()), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.scale(1f, 0.28f, cx, card.bottom)
        canvas.drawCircle(cx, card.bottom, glowR, glowPaint)
        canvas.restore()

        cardPaint.shader = LinearGradient(
            0f, card.top, 0f, card.bottom,
            Color.argb((242 * alpha).toInt(), 30, 32, 37), Color.argb((242 * alpha).toInt(), 12, 13, 16),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(card, rad, rad, cardPaint)
        borderPaint.strokeWidth = 1.2f * u
        borderPaint.shader = LinearGradient(
            0f, card.top, 0f, card.bottom,
            Color.argb((70 * alpha).toInt(), 255, 255, 255), Color.argb((14 * alpha).toInt(), 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(card, rad, rad, borderPaint)

        // Linia de lumină de pe muchia de sus, în culoarea modului, se întinde din centru.
        val lineIn = ease(now, LINE_AT_MS, LINE_MS)
        if (lineIn > 0f) {
            val half = cw * 0.36f * lineIn
            borderPaint.shader = LinearGradient(
                cx - half, 0f, cx + half, 0f,
                intArrayOf(Color.TRANSPARENT, withAlpha(accent, (255 * alpha).toInt()), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            borderPaint.strokeWidth = 2f * u
            canvas.drawLine(cx - half, card.top + 0.6f * u, cx + half, card.top + 0.6f * u, borderPaint)
        }

        // ---- cadranul din stânga: arc gri, arc colorat până la ac, acul ----
        val dialR = ch * 0.3f
        val dcx = card.left + rad
        val dcy = cy + dialR * 0.12f
        r.set(dcx - dialR, dcy - dialR, dcx + dialR, dcy + dialR)
        arcPaint.strokeWidth = 4f * u
        arcPaint.color = Color.argb((60 * alpha).toInt(), 255, 255, 255)
        canvas.drawArc(r, ARC_START, ARC_SWEEP, false, arcPaint)
        val needleIn = ease(now, NEEDLE_AT_MS, NEEDLE_MS)
        // DS: acul urcă de jos aproape de capăt; D: coboară de sus la o treime.
        val from = if (sport) 0.18f else 0.9f
        val to = if (sport) 0.9f else 0.32f
        val level = from + (to - from) * needleIn
        arcPaint.color = withAlpha(accent, (255 * alpha).toInt())
        canvas.drawArc(r, ARC_START, ARC_SWEEP * level, false, arcPaint)
        val ang = Math.toRadians((ARC_START + ARC_SWEEP * level).toDouble())
        needlePaint.color = Color.argb((255 * alpha).toInt(), 240, 242, 245)
        needlePaint.strokeWidth = 2.6f * u
        canvas.drawLine(
            dcx, dcy,
            dcx + (dialR * 0.82f * Math.cos(ang)).toFloat(), dcy + (dialR * 0.82f * Math.sin(ang)).toFloat(),
            needlePaint
        )
        needlePaint.color = withAlpha(accent, (255 * alpha).toInt())
        canvas.drawCircle(dcx, dcy, 3.6f * u, needlePaint)

        // ---- textul: rândul mic și numele modului ----
        val tx = dcx + dialR + 22f * u
        kickerPaint.textSize = 12.5f * u
        kickerPaint.alpha = (255 * alpha).toInt()
        titlePaint.textSize = 27f * u
        val textIn = ease(now, TEXT_AT_MS, TEXT_MS)
        val kickY = cy - 8f * u
        val titleY = cy + 21f * u + (1f - textIn) * 8f * u
        canvas.drawText(kicker, tx, kickY, kickerPaint)
        val titleW = titlePaint.measureText(title)
        val layer = canvas.saveLayer(tx - 4f, titleY - 34f * u, tx + titleW + 4f, titleY + 10f * u, null)
        titlePaint.alpha = (255 * alpha * textIn).toInt()
        canvas.drawText(title, tx, titleY, titlePaint)
        val sweep = (now - SWEEP_AT_MS) / SWEEP_MS
        if (sweep > 0f && sweep < 1f) {
            val band = titleW * 0.3f
            val sx = tx - band + (titleW + 2 * band) * sweep
            m.setScale(band, 50f * u)
            m.postTranslate(sx, titleY - 12f * u)
            unitSweep.setLocalMatrix(m)
            sweepPaint.shader = unitSweep
            sweepPaint.alpha = (255 * alpha).toInt()
            canvas.drawRect(tx - 4f, titleY - 34f * u, tx + titleW + 4f, titleY + 10f * u, sweepPaint)
        }
        canvas.restoreToCount(layer)

        // ---- selectorul D / DS din dreapta, cu bucata aprinsă care alunecă la modul nou ----
        val segW = 50f * u
        val segH = 30f * u
        val gap = 4f * u
        val sx2 = card.right - rad * 0.7f - segW * 2 - gap
        val sy = cy - segH / 2f
        r.set(sx2 - 4f * u, sy - 4f * u, sx2 + segW * 2 + gap + 4f * u, sy + segH + 4f * u)
        segPaint.color = Color.argb((120 * alpha).toInt(), 0, 0, 0)
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, segPaint)
        val slide = ease(now, SLIDE_AT_MS, SLIDE_MS)
        val fromX = if (sport) sx2 else sx2 + segW + gap
        val toX = if (sport) sx2 + segW + gap else sx2
        val px = fromX + (toX - fromX) * slide
        r.set(px, sy, px + segW, sy + segH)
        segPaint.color = withAlpha(accent, (255 * alpha).toInt())
        canvas.drawRoundRect(r, segH / 2f, segH / 2f, segPaint)
        segTextPaint.textSize = 15f * u
        val baseY = sy + segH / 2f + segTextPaint.textSize * 0.36f
        drawSeg(canvas, "D", sx2 + segW / 2f, baseY, !sport, slide, alpha)
        drawSeg(canvas, "DS", sx2 + segW * 1.5f + gap, baseY, sport, slide, alpha)

        canvas.restore()
        tick(now)
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

    private companion object {
        const val TOTAL_MS = ModeBannerView.TOTAL_MS
        const val ENTER_MS = 380f
        const val EXIT_MS = 320f
        const val LINE_AT_MS = 220f
        const val LINE_MS = 520f
        const val NEEDLE_AT_MS = 260f
        const val NEEDLE_MS = 700f
        const val TEXT_AT_MS = 140f
        const val TEXT_MS = 380f
        const val SLIDE_AT_MS = 380f
        const val SLIDE_MS = 420f
        const val SWEEP_AT_MS = 650f
        const val SWEEP_MS = 850f

        const val CARD_W = 470f
        const val CARD_H = 78f
        /** Centrul cardului, ca parte din înălțimea ecranului: mijloc spre jos. */
        const val CARD_Y = 0.875f
        const val ARC_START = 135f
        const val ARC_SWEEP = 270f
    }
}
