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

    @Volatile
    private var carUtc = 0L
    @Volatile
    private var carElapsed = 0L

    /**
     * Chemat când ora din bord sare față de cât numărăm noi (ai potrivit ceasul din bord), ca
     * ecranul să se schimbe pe loc, nu abia la minutul următor.
     */
    @Volatile
    var onJump: (() -> Unit)? = null

    /**
     * Ceasul din bord (cadrul CAN 0x2F8, la ~15 s cât merge motorul), cel pe care șoferul îl
     * potrivește din mașină. Fiecare cadru îl ia de la capăt, deci ora de pe ecran urmează mereu
     * bordul (cerut 2026-11-19).
     */
    fun onCarTime(epochMillis: Long) {
        val now = SystemClock.elapsedRealtime()
        val predicted = if (carUtc == 0L) 0L else carUtc + (now - carElapsed)
        carUtc = epochMillis
        carElapsed = now
        if (predicted == 0L || kotlin.math.abs(epochMillis - predicted) > JUMP_MS) onJump?.invoke()
    }

    /** Peste atât e o potrivire a ceasului, nu doar secundele care nu vin în cadru. */
    private const val JUMP_MS = 20_000L

    /** Din [ro.e92.launcher.can.GpsSpeedDataSource], la fiecare fix. */
    fun onFix(utcMillis: Long) {
        if (utcMillis <= 0L) return
        fixUtc = utcMillis
        fixElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * Ora de afișat: cea din bord dacă a venit, apoi cea de sistem dacă e bună, altfel cea din ultimul fix
     * GPS; null cât nu știm ora (ecranul arată atunci „--:--", nu o oră falsă).
     */
    fun now(): Long? {
        // Ora din bord are întâietate: e cea pe care o vede șoferul pe cadranul mașinii și vine
        // în câteva secunde, nu după fixul GPS de 2-3 minute (2026-11-15).
        if (carUtc != 0L) return carUtc + (SystemClock.elapsedRealtime() - carElapsed)
        val system = System.currentTimeMillis()
        if (yearOf(system) >= MIN_VALID_YEAR) return system
        if (fixUtc == 0L) return null
        return fixUtc + (SystemClock.elapsedRealtime() - fixElapsed)
    }

    fun hasCarTime(): Boolean = carUtc != 0L

    fun systemClockValid(): Boolean = yearOf(System.currentTimeMillis()) >= MIN_VALID_YEAR

    private fun yearOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)
}
