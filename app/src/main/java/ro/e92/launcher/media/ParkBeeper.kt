package ro.e92.launcher.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Bipurile senzorilor de parcare, generate în cod (fără fișiere audio).
 *
 * Mai des pe măsură ce obstacolul se apropie, ton continuu sub [CONTINUOUS_CM], tăcere
 * peste [SILENT_CM] sau fără date. Sunetul iese pe calea muzicii (USAGE_MEDIA): în mașină
 * se aude doar cu radioul pornit, ca orice sunet din unitate.
 */
class ParkBeeper {

    private val beep: AudioTrack = staticTrack(tone(BEEP_MS), loop = false)
    private val continuous: AudioTrack = staticTrack(tone(LOOP_MS, smoothEnds = false), loop = true)
    private var continuousOn = false

    /** Bucla de bipuri; rulează cât trăiește corutina (scena de marșarier), apoi eliberează tot. */
    suspend fun run(closestCm: () -> Int?, enabled: () -> Boolean) {
        try {
            while (coroutineContext.isActive) {
                val cm = closestCm()
                when {
                    !enabled() || cm == null || cm >= SILENT_CM -> {
                        setContinuous(false)
                        delay(IDLE_MS)
                    }
                    cm <= CONTINUOUS_CM -> {
                        setContinuous(true)
                        delay(IDLE_MS)
                    }
                    else -> {
                        setContinuous(false)
                        playBeep()
                        delay(gapFor(cm))
                    }
                }
            }
        } finally {
            release()
        }
    }

    /** Un singur bip, pentru butonul de test din Setări. */
    fun testBeep() = playBeep()

    fun release() {
        runCatching { beep.release() }
        runCatching { continuous.release() }
    }

    private fun playBeep() {
        runCatching {
            beep.stop()
            beep.reloadStaticData()
            beep.play()
        }
    }

    private fun setContinuous(on: Boolean) {
        if (on == continuousOn) return
        continuousOn = on
        runCatching {
            if (on) {
                continuous.reloadStaticData()
                continuous.play()
            } else {
                continuous.pause()
                continuous.flush()
            }
        }
    }

    /** Pauza dintre bipuri: [MAX_GAP_MS] la [SILENT_CM], [MIN_GAP_MS] chiar peste [CONTINUOUS_CM]. */
    private fun gapFor(cm: Int): Long {
        val t = ((cm - CONTINUOUS_CM).toFloat() / (SILENT_CM - CONTINUOUS_CM)).coerceIn(0f, 1f)
        return (MIN_GAP_MS + t * (MAX_GAP_MS - MIN_GAP_MS)).toLong()
    }

    private fun tone(ms: Int, smoothEnds: Boolean = true): ShortArray {
        val n = RATE * ms / 1000
        val fade = RATE * FADE_MS / 1000
        return ShortArray(n) { i ->
            // Capete netede: fără ele bipul pocnește în difuzoare. Bucla continuă nu le
            // are, altfel tonul ar pulsa la fiecare reluare.
            val env = if (smoothEnds) min(1f, min(i, n - 1 - i).toFloat() / fade) else 1f
            (sin(2 * PI * FREQ_HZ * i / RATE) * env * AMPLITUDE).toInt().toShort()
        }
    }

    private fun staticTrack(samples: ShortArray, loop: Boolean): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        if (loop) track.setLoopPoints(0, samples.size, -1)
        return track
    }

    companion object {
        const val SILENT_CM = 150
        /** Ton continuu doar foarte aproape (cerut 2026-11-08: 30 cm era prea devreme). */
        const val CONTINUOUS_CM = 10
        private const val MIN_GAP_MS = 70L
        private const val MAX_GAP_MS = 650L
        private const val IDLE_MS = 100L
        private const val BEEP_MS = 90
        /** Bucla tonului continuu: un număr întreg de perioade, ca să nu se audă cusătura. */
        private const val LOOP_MS = 500
        private const val FADE_MS = 6
        private const val RATE = 44_100
        /** 1800 Hz: înalt și clar ca la senzorii de parcare, fără să fie strident. */
        private const val FREQ_HZ = 1800.0
        private const val AMPLITUDE = 0.6f * Short.MAX_VALUE
    }
}
