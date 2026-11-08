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
 * Pops & Bangs și „DSG fart" pentru o cutie automată, doar din turometru.
 *
 * Turația vine de ~10 ori pe secundă cât e pornit modul ([ro.e92.launcher.can.KswCarDataSource]).
 * Fără poziția pedalei, citim intenția din forma curbei de turație:
 *
 *  1. Schimbare în sus la accelerație: turația urca, apoi cade brusc (peste [SHIFT_RATE] rpm/s,
 *     cel puțin [SHIFT_DROP] rpm) - „DSG fart", 2-3 pocnituri strânse.
 *  2. Inferioară: turația sare brusc în sus. NU dăm pops pe loc: așteptăm până la
 *     [DOWNSHIFT_WAIT_MS]. Dacă urmează frână de motor (scădere constantă), secvența o dată.
 *     Dacă turația urcă mai departe, a fost kickdown - nimic.
 *  3. Ridicarea piciorului peste pragul ales: începe frâna de motor - secvența o dată pe
 *     episod. Se re-armează doar după o accelerație ([REARM_SAMPLES] citiri de urcare).
 *
 * Volumul e fix, ales în setări; nu crește cu turația. Pragurile sunt primele estimări și se
 * reglează după probele din mașină: fiecare decizie apare în /events cu turația.
 */
object PopsEngine {

    /**
     * 0 Video „în adâncime" (înfundat, cu ecou de habitaclu, ca din spatele mașinii),
     * 1 Video (cum sunt în clip), 2 Fin, 3 Burble, 4 Sec.
     */
    const val STYLES = 5
    /** 0 oprit, 1 din pocnituri, 2 Moale, 3 Scurt. */
    const val SHIFT_OPTIONS = 4
    private const val HISTORY = 24

    // Înaintea câmpurilor care le folosesc: proprietățile unui object se inițializează în ordine.
    private val POP_RES = arrayOf(
        intArrayOf(
            R.raw.pop_d00, R.raw.pop_d01, R.raw.pop_d02, R.raw.pop_d03, R.raw.pop_d04, R.raw.pop_d05,
            R.raw.pop_d06, R.raw.pop_d07, R.raw.pop_d08, R.raw.pop_d09, R.raw.pop_d10, R.raw.pop_d11
        ),
        intArrayOf(
            R.raw.pop_v00, R.raw.pop_v01, R.raw.pop_v02, R.raw.pop_v03, R.raw.pop_v04, R.raw.pop_v05,
            R.raw.pop_v06, R.raw.pop_v07, R.raw.pop_v08, R.raw.pop_v09, R.raw.pop_v10, R.raw.pop_v11
        ),
        intArrayOf(R.raw.pop_a0, R.raw.pop_a1, R.raw.pop_a2, R.raw.pop_a3, R.raw.pop_a4, R.raw.pop_a5),
        intArrayOf(R.raw.pop_b0, R.raw.pop_b1, R.raw.pop_b2, R.raw.pop_b3, R.raw.pop_b4, R.raw.pop_b5),
        intArrayOf(R.raw.pop_c0, R.raw.pop_c1, R.raw.pop_c2, R.raw.pop_c3, R.raw.pop_c4, R.raw.pop_c5)
    )
    private val SHIFT_RES = intArrayOf(R.raw.shift_a, R.raw.shift_b)
    private val BURST_TOKEN = Any()

    private val thread = HandlerThread("pops").apply { start() }
    private val handler = Handler(thread.looper)

    private var pool: SoundPool? = null
    private val popIds = Array(STYLES) { s -> IntArray(POP_RES[s].size) }
    private val shiftIds = IntArray(SHIFT_RES.size)

    private val times = LongArray(HISTORY)
    private val rpms = IntArray(HISTORY)
    private var count = 0

    private var rising = 0             // citiri la rând cu turația în urcare
    private var falling = 0            // citiri la rând de frână de motor
    private var fallStartRpm = 0
    private var armed = true
    private var downshiftAt = 0L       // inferioară văzută, așteptăm să vedem ce urmează
    private var burstUntil = 0L
    private var lastShiftAt = 0L

    /** De pe firul binder-ului, la fiecare cadru de bord. */
    fun onRpm(rpm: Int) {
        if (!Services.prefs.popsEnabled) return
        val now = SystemClock.elapsedRealtime()
        handler.post { process(rpm, now) }
    }

    fun testPops() = whenLoaded { startBurst(SystemClock.elapsedRealtime(), "test") }

    fun testShift() = whenLoaded { playShift("test") }

    /** Încarcă sunetele din timp (la pornirea modului), ca prima rafală să nu fie mută. */
    fun preload() = handler.post { ensureLoaded() }

    /** SoundPool încarcă în fundal: la prima folosire așteptăm puțin înainte să cânte. */
    private fun whenLoaded(action: () -> Unit) = handler.post {
        val fresh = pool == null
        if (!ensureLoaded()) return@post
        if (fresh) handler.postDelayed({ action() }, LOAD_WAIT_MS) else action()
    }

    // ------------------------------------------------------------------ logica

    private fun process(rpm: Int, now: Long) {
        if (!ensureLoaded()) return
        val prevIdx = (count - 1 + HISTORY) % HISTORY
        val hasPrev = count > 0
        val prevRpm = if (hasPrev) rpms[prevIdx] else rpm
        val dt = if (hasPrev) (now - times[prevIdx]).coerceAtLeast(1) else 1
        push(now, rpm)
        if (!hasPrev || dt > STALE_MS) {
            rising = 0; falling = 0
            return
        }
        val rate = (rpm - prevRpm) * 1000f / dt

        // Urcare / frână de motor: contoare de citiri la rând.
        if (rate > RISE_RATE) rising++ else rising = 0
        if (rate < -BRAKE_RATE_MIN && rate > -SHIFT_RATE) {
            if (falling == 0) fallStartRpm = prevRpm
            falling++
        } else if (rate >= -BRAKE_RATE_MIN) {
            falling = 0
        }

        // O rafală în curs se oprește la accelerație nouă.
        if (now < burstUntil && rising >= 1 && rate > STOP_ON_RISE) {
            burstUntil = now
            handler.removeCallbacksAndMessages(BURST_TOKEN)
        }

        // Re-armare după o accelerație.
        if (rising >= REARM_SAMPLES) armed = true

        // 1. Schimbare în sus la accelerație.
        val wasAccelerating = averageRate(now, ACCEL_LOOKBACK_MS, skip = 1) > ACCEL_RATE
        val drop = maxInWindow(now, SHIFT_WINDOW_MS) - rpm
        if (rate < -SHIFT_RATE && drop >= SHIFT_DROP && prevRpm >= SHIFT_MIN_RPM && wasAccelerating &&
            now - lastShiftAt > SHIFT_COOLDOWN_MS
        ) {
            lastShiftAt = now
            falling = 0
            downshiftAt = 0
            playShift("schimbare în sus la $prevRpm rpm")
            return
        }

        // 2. Inferioară: salt brusc în sus, dintr-o stare în care NU accelerai (la accelerație
        //    turația urcă oricum repede). Fereastra de dinainte de salt sare peste ultimele
        //    [JUMP_SKIP] citiri, adică peste saltul însuși.
        val jump = rpm - minInWindow(now, JUMP_WINDOW_MS)
        if (rate > JUMP_RATE && jump >= JUMP_MIN && rpm >= DOWNSHIFT_MIN_RPM && downshiftAt == 0L &&
            averageRate(now, ACCEL_LOOKBACK_MS, skip = JUMP_SKIP) <= ACCEL_RATE
        ) {
            downshiftAt = now
            falling = 0
        }
        if (downshiftAt != 0L) {
            // Pops doar dacă urmează frână de motor; orice altceva (kickdown, accelerație) = nimic.
            if (falling >= BRAKE_SAMPLES) {
                downshiftAt = 0
                armed = false
                startBurst(now, "inferioară + frână de motor la $rpm rpm")
            } else if (now - downshiftAt > DOWNSHIFT_WAIT_MS) {
                downshiftAt = 0
                EventLog.log(TAG, "inferioară fără frână de motor (kickdown / accelerație) la $rpm rpm: fără pops")
            }
            return
        }

        // 3. Frână de motor peste prag, o dată pe episod.
        if (armed && falling >= BRAKE_SAMPLES && fallStartRpm >= Services.prefs.popsRpm && now >= burstUntil) {
            armed = false
            startBurst(now, "frână de motor de la $fallStartRpm rpm")
        }
    }

    private fun startBurst(now: Long, why: String) {
        val prefs = Services.prefs
        val style = prefs.popsStyle.coerceIn(0, STYLES - 1)
        val duration = DURATIONS_MS[prefs.popsDuration.coerceIn(0, DURATIONS_MS.size - 1)]
        val volume = volumeFraction()
        burstUntil = now + duration
        EventLog.log(TAG, "pops: $why")
        val (gapMin, gapMax) = GAPS_MS[style]
        val ids = popIds[style]
        var at = Random.nextLong(10, 50)
        var last = -1
        while (at < duration) {
            var k = Random.nextInt(ids.size)
            if (k == last) k = (k + 1) % ids.size           // nu de două ori la rând aceeași
            last = k
            // Spre final mai rare și mai încet, cum se sting pocniturile adevărate.
            val fade = 1f - (at.toFloat() / duration) * 0.55f
            val vol = (volume * fade * (0.6f + Random.nextFloat() * 0.4f)).coerceIn(0f, 1f)
            val id = ids[k]
            handler.postAtTime(
                { pool?.play(id, vol, vol, 1, 0, 0.96f + Random.nextFloat() * 0.08f) },
                BURST_TOKEN, SystemClock.uptimeMillis() + at
            )
            at += (Random.nextLong(gapMin, gapMax) / fade).toLong()
        }
    }

    /** „DSG fart": 2-3 pocnituri strânse din stilul ales, sau unul din sunetele generate. */
    private fun playShift(why: String) {
        val option = Services.prefs.shiftSound.coerceIn(0, SHIFT_OPTIONS - 1)
        if (option == 0) return
        val volume = volumeFraction() * SHIFT_LEVEL
        EventLog.log(TAG, "schimbare: $why")
        if (option >= 2) {
            pool?.play(shiftIds[option - 2], volume, volume, 1, 0, 1f)
            return
        }
        val ids = popIds[Services.prefs.popsStyle.coerceIn(0, STYLES - 1)]
        val n = 2 + Random.nextInt(2)
        var at = 0L
        for (i in 0 until n) {
            val id = ids[Random.nextInt(ids.size)]
            val vol = volume * (1f - i * 0.25f)
            handler.postAtTime({ pool?.play(id, vol, vol, 1, 0, 1f) }, BURST_TOKEN, SystemClock.uptimeMillis() + at)
            at += Random.nextLong(35, 70)
        }
    }

    private fun volumeFraction(): Float = Services.prefs.popsVolume.coerceIn(1, 10) / 10f

    // ------------------------------------------------------------------ istoric

    private fun push(now: Long, rpm: Int) {
        val i = count % HISTORY
        times[i] = now
        rpms[i] = rpm
        count++
    }

    private inline fun forWindow(now: Long, windowMs: Long, skip: Int = 0, block: (Long, Int) -> Unit) {
        for (k in skip until minOf(count, HISTORY)) {
            val i = (count - 1 - k) % HISTORY
            if (now - times[i] > windowMs) break
            block(times[i], rpms[i])
        }
    }

    private fun maxInWindow(now: Long, windowMs: Long): Int {
        var max = 0
        forWindow(now, windowMs) { _, r -> if (r > max) max = r }
        return max
    }

    private fun minInWindow(now: Long, windowMs: Long): Int {
        var min = Int.MAX_VALUE
        forWindow(now, windowMs) { _, r -> if (r < min) min = r }
        return if (min == Int.MAX_VALUE) 0 else min
    }

    /** Panta medie (rpm/s) pe fereastră, fără ultimele [skip] citiri (căderea sau saltul însuși). */
    private fun averageRate(now: Long, windowMs: Long, skip: Int): Float {
        var firstT = 0L; var firstR = 0; var lastT = 0L; var lastR = 0; var n = 0
        forWindow(now, windowMs + SAMPLE_MS * skip, skip) { t, r ->
            if (n == 0) { lastT = t; lastR = r }
            firstT = t; firstR = r
            n++
        }
        if (n < 3 || lastT == firstT) return 0f
        return (lastR - firstR) * 1000f / (lastT - firstT)
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
            for (s in 0 until STYLES) for (k in POP_RES[s].indices) popIds[s][k] = p.load(context, POP_RES[s][k], 1)
            for (k in SHIFT_RES.indices) shiftIds[k] = p.load(context, SHIFT_RES[k], 1)
            pool = p
            true
        }.getOrElse {
            EventLog.log(TAG, "sunete indisponibile: ${it.message}")
            p.release()
            false
        }
    }

    private const val TAG = "Pops"

    /** Pragurile de turație din setări, pentru frâna de motor fără inferioară. */
    val RPM_STEPS = intArrayOf(3000, 3500, 4000, 4500)
    private val DURATIONS_MS = longArrayOf(600, 1200, 2000)
    private const val SHIFT_LEVEL = 0.8f
    /** Pauzele dintre pocnituri, pe stil (Video adânc, Video, Fin, Burble, Sec). */
    private val GAPS_MS = arrayOf(60L to 170L, 50L to 160L, 40L to 140L, 70L to 200L, 90L to 250L)

    private const val STALE_MS = 600L          // o pauză mai lungă în date: o luăm de la capăt
    private const val RISE_RATE = 150f         // rpm/s: turația urcă
    private const val STOP_ON_RISE = 400f      // rpm/s: accelerație nouă, oprește rafala
    private const val BRAKE_RATE_MIN = 150f    // rpm/s: frână de motor (scădere)
    private const val BRAKE_SAMPLES = 3        // ~0,3 s de frână de motor la rând
    private const val REARM_SAMPLES = 3        // ~0,3 s de accelerație re-armează

    private const val SHIFT_RATE = 2500f
    private const val SHIFT_DROP = 400
    private const val SHIFT_WINDOW_MS = 350L
    private const val SHIFT_MIN_RPM = 2000
    private const val SHIFT_COOLDOWN_MS = 800L
    private const val ACCEL_LOOKBACK_MS = 600L
    private const val ACCEL_RATE = 250f

    private const val JUMP_RATE = 1500f
    private const val JUMP_MIN = 400
    private const val JUMP_WINDOW_MS = 400L
    private const val DOWNSHIFT_MIN_RPM = 2500
    private const val DOWNSHIFT_WAIT_MS = 1200L
    private const val JUMP_SKIP = 4
    private const val SAMPLE_MS = 100L

    private const val LOAD_WAIT_MS = 600L
}
