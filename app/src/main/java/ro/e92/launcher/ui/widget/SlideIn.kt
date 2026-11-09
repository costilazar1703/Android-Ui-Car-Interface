package ro.e92.launcher.ui.widget

import android.os.SystemClock
import android.view.View
import kotlin.math.pow

/**
 * Glisare a unui ecran în cadru, pe ceasul propriu (postOnAnimation + SystemClock): pe
 * unitate scala animațiilor Android poate fi 0, iar atunci un ViewPropertyAnimator sare
 * direct la final. [direction] +1 = vine din dreapta, -1 = din stânga.
 */
fun View.slideIn(direction: Int, durationMs: Long = 320L) {
    val distance = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * 0.35f
    val start = SystemClock.uptimeMillis()
    translationX = direction * distance
    alpha = 0f
    val step = object : Runnable {
        override fun run() {
            val t = ((SystemClock.uptimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
            val eased = 1f - (1f - t).pow(3)                  // decelerare, ca paginile de dale
            translationX = direction * distance * (1f - eased)
            alpha = eased
            if (t < 1f) postOnAnimation(this) else {
                translationX = 0f
                alpha = 1f
            }
        }
    }
    postOnAnimation(step)
}
