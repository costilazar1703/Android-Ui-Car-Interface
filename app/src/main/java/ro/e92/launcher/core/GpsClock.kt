package ro.e92.launcher.core

import android.os.SystemClock
import java.util.Calendar

/**
 * Ora corectă chiar după ce unitatea repornește cu ceasul în 2013.
 *
 * La pornirea motorului unitatea KSW repornește, iar ceasul ei de sistem
 * revine la 2013 până îl sincronizează ea (la noi, abia după ce parcai). O
 * aplicație nu are voie să-l potrivească, dar fiecare fix GPS vine cu ora UTC
 * exactă: ținem minte ultimul și mergem mai departe cu ceasul monoton.
 */
object GpsClock {

    /** Sub anul ăsta ceasul de sistem e cel resetat, nu cel real. */
    private const val MIN_VALID_YEAR = 2024

    @Volatile
    private var fixUtc = 0L
    @Volatile
    private var fixElapsed = 0L

    /** Din [ro.e92.launcher.can.GpsSpeedDataSource], la fiecare fix. */
    fun onFix(utcMillis: Long) {
        if (utcMillis <= 0L) return
        fixUtc = utcMillis
        fixElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * Ora de afișat: cea de sistem dacă e bună, altfel cea din ultimul fix
     * GPS; null cât nu știm ora (ecranul arată atunci „--:--", nu o oră falsă).
     */
    fun now(): Long? {
        val system = System.currentTimeMillis()
        if (yearOf(system) >= MIN_VALID_YEAR) return system
        if (fixUtc == 0L) return null
        return fixUtc + (SystemClock.elapsedRealtime() - fixElapsed)
    }

    fun systemClockValid(): Boolean = yearOf(System.currentTimeMillis()) >= MIN_VALID_YEAR

    private fun yearOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)
}
