package ro.e92.launcher.media

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import ro.e92.launcher.R
import ro.e92.launcher.core.EventLog
import ro.e92.launcher.core.Services
import kotlin.random.Random

/**
 * Pops & Bangs și „DSG fart", doar din turometru (fără poziția pedalei).
 *
 * Turația vine de 10 ori pe secundă cât e pornit modul ([ro.e92.launcher.can.KswCarDataSource]).
 * Din ea deosebim două lucruri după cât de repede scade:
 *  - schimbare de treaptă: o cădere bruscă, peste [SHIFT_RATE] rpm/s și peste [SHIFT_DROP] rpm
 *    în cel mult [SHIFT_WINDOW_MS] - mult mai repede decât frâna de motor;
 *  - ridicarea piciorului: peste pragul ales, turația scade constant, între [LIFT_RATE_MIN] și
 *    [SHIFT_RATE] rpm/s, două citiri la rând. Atunci pornește o rafală de pocnituri, oprită
 *    imediat ce turația urcă din nou sau coboară sub [STOP_RPM].
 *
 * Totul e modest: volumul de bază e mic, iar intensitatea aleasă îl urcă doar puțin.
 * Pragurile sunt primele estimări; se reglează după probele din mașină.
 */
object PopsEngine {

    const val STYLES = 3
    const val SHIFT_STYLES = 2
    private const val SAMPLES_PER_STYLE = 6
    private const val HISTORY = 16

    private val thread = HandlerThread("pops").apply { start() }
    private val handler = Handler(thread.looper)

    private var pool: SoundPool? = null
    private val popIds = Array(STYLES) { IntArray(SAMPLES_PER_STYLE) }
    private val shiftIds = IntArray(SHIFT_STYLES)

    private val times = LongArray(HISTORY)
    private val rpms = IntArray(HISTORY)
    private var count = 0

    private var armed = false
    private var burstUntil = 0L
    private var lastBurstEnd = 0L
    private var lastShiftAt = 0L

    /** De pe firul binder-ului, la fiecare cadru de bord. */
    fun onRpm(rpm: Int) {
        if (!Services.prefs.popsEnabled) return
        val now = SystemClock.elapsedRealtime()
        handler.post { process(rpm, now) }
    }

    /** Butonul de test: o rafală la intensitatea și durata alese. */
    fun testPops() = whenLoaded { startBurst(SystemClock.elapsedRealtime(), TEST_RPM, test = true) }

    fun testShift() = whenLoaded { playShift(TEST_RPM, test = true) }

    /** Încarcă sunetele din timp (la pornirea modului), ca prima rafală să nu fie mută. */
    fun preload() = handler.post { ensureLoaded() }

    /** SoundPool încarcă în fundal: la prima folosire așteptăm puțin înainte să cânte. */
    private fun whenLoaded(action: () -> Unit) = handler.post {
        val fresh = pool == null
        if (!ensureLoaded()) return@post
        if (fresh) handler.postDelayed({ action() }, LOAD_WAIT_MS) else action()
    }

    private fun process(rpm: Int, now: Long) {
        if (!ensureLoaded()) return
        push(now, rpm)
        if (count < 3) return
        val prevIdx = (count - 2) % HISTORY
        val prev2Idx = (count - 3) % HISTORY
        val dt = (now - times[prevIdx]).coerceAtLeast(1)
        val rate = (rpm - rpms[prevIdx]) * 1000f / dt
        val prevRate = (rpms[prevIdx] - rpms[prev2Idx]) * 1000f / (times[prevIdx] - times[prev2Idx]).coerceAtLeast(1)

        // Schimbare de treaptă: cădere bruscă de la o turație de mers.
        val dropRecent = maxInWindow(now, SHIFT_WINDOW_MS) - rpm
        if (Services.prefs.shiftSound != 0 && rate < -SHIFT_RATE && dropRecent >= SHIFT_DROP &&
            rpms[prevIdx] >= SHIFT_MIN_RPM && now - lastShiftAt > SHIFT_COOLDOWN_MS
        ) {
            lastShiftAt = now
            armed = false              // nu și pops pe aceeași cădere
            playShift(rpms[prevIdx], test = false)
            return
        }

        val threshold = Services.prefs.popsRpm
        if (rpm >= threshold) armed = true

        // Rafala în curs se oprește când turația urcă din nou (accelerație) sau e prea jos.
        if (now < burstUntil && (rate > RISE_STOP || rpm < STOP_RPM)) {
            burstUntil = now
            handler.removeCallbacksAndMessages(BURST_TOKEN)
        }

        val lifting = rate < -LIFT_RATE_MIN && rate > -SHIFT_RATE &&
            prevRate < -LIFT_RATE_MIN && prevRate > -SHIFT_RATE
        if (armed && lifting && now >= burstUntil && now - lastBurstEnd > BURST_COOLDOWN_MS) {
            armed = false
            startBurst(now, rpms[prevIdx], test = false)
        }
    }

    private fun startBurst(now: Long, fromRpm: Int, test: Boolean) {
        val prefs = Services.prefs
        val style = prefs.popsStyle.coerceIn(0, STYLES - 1)
        val duration = DURATIONS_MS[prefs.popsDuration.coerceIn(0, DURATIONS_MS.size - 1)]
        val base = INTENSITY[prefs.popsIntensity.coerceIn(0, INTENSITY.size - 1)]
        // Mai sus în turație = puțin mai tare, ca la o mașină reală.
        val rpmBoost = ((fromRpm - 3000) / 3000f).coerceIn(0f, 1f) * 0.35f + 0.65f
        burstUntil = now + duration
        lastBurstEnd = burstUntil
        if (!test) EventLog.log(TAG, "pops de la $fromRpm rpm")
        val (gapMin, gapMax) = GAPS_MS[style]
        var at = Random.nextLong(10, 60)
        while (at < duration) {
            val id = popIds[style][Random.nextInt(SAMPLES_PER_STYLE)]
            // Spre final mai rare și mai încet, cum se sting pocniturile adevărate.
            val fade = 1f - (at.toFloat() / duration) * 0.6f
            val vol = (base * rpmBoost * fade * Random.nextFloat().coerceIn(0.45f, 1f)).coerceIn(0f, 1f)
            handler.postAtTime({ pool?.play(id, vol, vol, 1, 0, Random.nextFloat() * 0.12f + 0.94f) },
                BURST_TOKEN, SystemClock.uptimeMillis() + at)
            at += (Random.nextLong(gapMin, gapMax) / fade).toLong()
        }
    }

    private fun playShift(fromRpm: Int, test: Boolean) {
        val style = (Services.prefs.shiftSound - 1).coerceIn(0, SHIFT_STYLES - 1)
        val base = INTENSITY[Services.prefs.popsIntensity.coerceIn(0, INTENSITY.size - 1)] * SHIFT_LEVEL
        if (!test) EventLog.log(TAG, "schimbare de treaptă la $fromRpm rpm")
        pool?.play(shiftIds[style], base, base, 1, 0, 1f)
    }

    private fun push(now: Long, rpm: Int) {
        val i = count % HISTORY
        times[i] = now
        rpms[i] = rpm
        count++
    }

    private fun maxInWindow(now: Long, windowMs: Long): Int {
        var max = 0
        for (k in 0 until minOf(count, HISTORY)) {
            val i = (count - 1 - k) % HISTORY
            if (now - times[i] > windowMs) break
            if (rpms[i] > max) max = rpms[i]
        }
        return max
    }

    private fun ensureLoaded(): Boolean {
        if (pool != null) return true
        val context = Services.appContext
        val p = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        return runCatching {
            for (s in 0 until STYLES) for (k in 0 until SAMPLES_PER_STYLE) {
                popIds[s][k] = p.load(context, POP_RES[s][k], 1)
            }
            for (s in 0 until SHIFT_STYLES) shiftIds[s] = p.load(context, SHIFT_RES[s], 1)
            pool = p
            true
        }.getOrElse {
            EventLog.log(TAG, "sunete indisponibile: ${it.message}")
            p.release()
            false
        }
    }

    private const val TAG = "Pops"
    private val BURST_TOKEN = Any()

    /** Pragurile de turație din setări. */
    val RPM_STEPS = intArrayOf(3000, 3500, 4000, 4500)
    private val DURATIONS_MS = longArrayOf(600, 1200, 2000)
    /** Volum de bază: mic. Mare înseamnă doar puțin mai tare, nu o bubuitură. */
    private val INTENSITY = floatArrayOf(0.25f, 0.4f, 0.55f)
    private const val SHIFT_LEVEL = 0.7f
    private val GAPS_MS = arrayOf(40L to 140L, 70L to 200L, 90L to 250L)

    private const val SHIFT_RATE = 2500f
    private const val SHIFT_DROP = 700
    private const val SHIFT_WINDOW_MS = 350L
    private const val SHIFT_MIN_RPM = 2200
    private const val SHIFT_COOLDOWN_MS = 1200L
    private const val LIFT_RATE_MIN = 250f
    private const val RISE_STOP = 150f
    private const val STOP_RPM = 1800
    private const val BURST_COOLDOWN_MS = 1500L
    private const val TEST_RPM = 4500
    private const val LOAD_WAIT_MS = 600L

    private val POP_RES = arrayOf(
        intArrayOf(R.raw.pop_a0, R.raw.pop_a1, R.raw.pop_a2, R.raw.pop_a3, R.raw.pop_a4, R.raw.pop_a5),
        intArrayOf(R.raw.pop_b0, R.raw.pop_b1, R.raw.pop_b2, R.raw.pop_b3, R.raw.pop_b4, R.raw.pop_b5),
        intArrayOf(R.raw.pop_c0, R.raw.pop_c1, R.raw.pop_c2, R.raw.pop_c3, R.raw.pop_c4, R.raw.pop_c5)
    )
    private val SHIFT_RES = intArrayOf(R.raw.shift_a, R.raw.shift_b)
}
