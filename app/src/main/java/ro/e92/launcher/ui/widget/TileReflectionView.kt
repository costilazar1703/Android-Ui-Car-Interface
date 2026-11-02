package ro.e92.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import ro.e92.launcher.R

/**
 * Reflexia dalei „în apă": pătratul de deasupra, în oglindă, care se stinge în
 * jos ca pe asfaltul ud din fotografia de fundal.
 *
 * Se desenează cu drawable-urile dalei (rama, în varianta subțire
 * `bg_tile_reflection`, și iconița),
 * nu dintr-o captură de ecran. Așa reflexia urmează singură starea dalei: când
 * dala se aprinde, se aprinde și reflexia — `duplicateParentState` îi aduce
 * starea `activated`, iar drawable-urile își schimbă singure aspectul.
 *
 * Peste oglindă trec benzi orizontale foarte fine, ca suprafața să citească a
 * apă, nu a sticlă lustruită. Sub tot rămâne lumina portocalie (`tile_shadow`),
 * care era deja indicatorul principal al selecției.
 *
 * **Strat hardware.** Oglinda cere un saveLayer cu mască. Fără strat propriu,
 * HWUI ar reface masca la fiecare cadru în care se redesenează grila — adică pe
 * toată alunecarea de pagină, pentru douăsprezece dale deodată. Cu strat
 * hardware, reflexia se desenează o dată și se refolosește ca textură până se
 * schimbă starea dalei.
 */
class TileReflectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val frame: Drawable = ContextCompat.getDrawable(context, R.drawable.bg_tile_reflection)!!.mutate()
    private val spill: Drawable = ContextCompat.getDrawable(context, R.drawable.tile_shadow)!!.mutate()
    private var icon: Drawable? = null
    private var iconSize = 0
    private var bodyHeight = 0

    private val fadePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val ripplePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * @param iconRes iconița dalei
     * @param iconSizePx mărimea ei în corpul dalei
     * @param bodyHeightPx înălțimea corpului dalei, deci a ceea ce se oglindește
     */
    fun setSource(@DrawableRes iconRes: Int, iconSizePx: Int, bodyHeightPx: Int) {
        icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
        iconSize = iconSizePx
        bodyHeight = bodyHeightPx
        icon?.state = drawableState
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        frame.state = drawableState
        spill.state = drawableState
        icon?.state = drawableState
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Masca de stingere: opac sus, lângă dală, transparent jos.
        // Coboara lent in prima jumatate si repede in a doua: iconita oglindita
        // cade cam la mijlocul reflexiei, iar cu o stingere liniara ajungea
        // aproape invizibila exact acolo.
        fadePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb((255 * TOP_ALPHA).toInt(), 0, 0, 0),
                Color.argb((255 * TOP_ALPHA * 0.6f).toInt(), 0, 0, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        // Benzile de „apă": o rampă de alfă care se repetă la câțiva pixeli.
        val period = RIPPLE_PERIOD_DP * resources.displayMetrics.density
        ripplePaint.shader = LinearGradient(
            0f, 0f, 0f, period,
            intArrayOf(Color.BLACK, Color.argb(RIPPLE_LOW_ALPHA, 0, 0, 0), Color.BLACK),
            null, Shader.TileMode.REPEAT
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // Lumina de sub dală, neoglindită: e pe suprafață, nu în reflexie.
        spill.setBounds(0, 0, w, h)
        spill.draw(canvas)

        val body = if (bodyHeight > 0) bodyHeight else w
        val layer = canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)

        // Oglinda: tot ce se desenează la y negativ apare răsturnat dedesubt.
        // Muchia de jos a dalei ajunge lipită de marginea de sus a reflexiei.
        //
        // Rama se oglindește 1:1, NU turtită. Rama e dublă - două contururi la
        // câțiva pixeli unul de altul - iar turtirea pe verticală le apropia
        // liniile orizontale până se contopeau într-o bandă vizibil mai groasă
        // decât rama dalei.
        canvas.save()
        canvas.scale(1f, -1f)
        frame.setBounds(0, -body, w, 0)
        frame.draw(canvas)
        canvas.restore()

        // Doar iconița e turtită: într-o oglindă 1:1 centrul ei ar cădea la
        // jumătatea înălțimii dalei, în afara reflexiei, deja stinsă de tot.
        icon?.let { ic ->
            canvas.save()
            canvas.scale(1f, -SQUASH)
            val cx = w / 2
            val cy = -body / 2
            ic.setBounds(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2)
            ic.draw(canvas)
            canvas.restore()
        }

        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fadePaint)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ripplePaint)
        canvas.restoreToCount(layer)
    }

    private companion object {
        /** Cât de vizibilă e reflexia lângă dală. Peste ~0.4 concurează cu dala. */
        const val TOP_ALPHA = 0.45f

        /**
         * Cât e turtită iconița oglindită. O aduce în zona în care reflexia încă
         * se vede - la 0.85 cădea aproape la capătul ei, deja stinsă. Rama nu e
         * turtită (vezi onDraw).
         */
        const val SQUASH = 0.55f

        const val RIPPLE_PERIOD_DP = 4f
        /** Cât de întunecată e partea „joasă" a fiecărei benzi de apă. */
        const val RIPPLE_LOW_ALPHA = 150
    }
}
