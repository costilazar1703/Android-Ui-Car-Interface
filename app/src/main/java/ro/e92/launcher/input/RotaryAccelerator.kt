package ro.e92.launcher.input

import android.os.SystemClock

/**
 * Rotița reală BMW nu mută un element per click când o învârți repede — sare mai
 * multe. Fără asta, parcurgerea unei liste de 60 de aplicații e nefolosibilă.
 *
 * Zero alocări: doar câmpuri primitive, apelat din onKeyDown.
 */
class RotaryAccelerator(
    private val fastThresholdMs: Long = 90,
    private val turboThresholdMs: Long = 45,
    private val fastStep: Int = 2,
    private val turboStep: Int = 5
) {

    private var lastEventAt = 0L
    private var lastDirection = 0

    /**
     * @param direction +1 pentru CW, -1 pentru CCW
     * @return câte elemente să sară focusul (întotdeauna >= 1)
     */
    fun step(direction: Int): Int {
        val now = SystemClock.uptimeMillis()
        val delta = now - lastEventAt
        val sameDirection = direction == lastDirection

        lastEventAt = now
        lastDirection = direction

        // Schimbarea de sens resetează accelerarea — altfel un corectiv mic
        // ar sări jumătate de listă înapoi.
        if (!sameDirection) return 1

        return when {
            delta <= turboThresholdMs -> turboStep
            delta <= fastThresholdMs -> fastStep
            else -> 1
        }
    }

    fun reset() {
        lastEventAt = 0L
        lastDirection = 0
    }
}
