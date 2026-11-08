package ro.e92.launcher.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.annotation.RawRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ro.e92.launcher.R
import kotlin.coroutines.coroutineContext

/**
 * Bipurile senzorilor de parcare: varianta B (gong moale), aleasă de șofer pe
 * 2026-11-08 din previzualizări. Fișierele din res/raw sunt exact cele ascultate pe laptop.
 *
 * Mai des pe măsură ce obstacolul se apropie, ton continuu (mai încet, fără armonica
 * înaltă) la [CONTINUOUS_CM] sau mai puțin, tăcere peste [SILENT_CM] sau fără date.
 * Sunetul iese pe calea muzicii: în mașină se aude doar cu radioul pornit.
 */
class ParkBeeper(context: Context) {

    private val beepPcm = readWav(context, R.raw.park_beep)
    private val beepMs = beepPcm.size * 1000L / RATE
    private val beep: AudioTrack = staticTrack(beepPcm, loop = false)
    private val continuous: AudioTrack = staticTrack(readWav(context, R.raw.park_beep_loop), loop = true)
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
                        // Bipul se termină întâi, apoi pauza: altfel bipurile rapide se suprapun
                        // și sună ascuțit.
                        delay(beepMs + gapFor(cm))
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
        private const val MIN_GAP_MS = 110L
        private const val MAX_GAP_MS = 650L
        private const val IDLE_MS = 100L
        const val RATE = 44_100

        /** PCM 16 biți mono dintr-un WAV din res/raw (fișierele generate de noi: 44,1 kHz). */
        fun readWav(context: Context, @RawRes id: Int): ShortArray {
            val bytes = context.resources.openRawResource(id).use { it.readBytes() }
            // Caută bucata "data"; antetul are 44 de octeți la fișierele noastre, dar nu ne bazăm pe asta.
            var i = 12
            while (i + 8 <= bytes.size) {
                val tag = String(bytes, i, 4, Charsets.US_ASCII)
                val len = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 6].toInt() and 0xFF) shl 16) or ((bytes[i + 7].toInt() and 0xFF) shl 24)
                if (tag == "data") {
                    val start = i + 8
                    val count = minOf(len, bytes.size - start) / 2
                    return ShortArray(count) { k ->
                        ((bytes[start + 2 * k].toInt() and 0xFF) or (bytes[start + 2 * k + 1].toInt() shl 8)).toShort()
                    }
                }
                i += 8 + len
            }
            return ShortArray(0)
        }
    }
}
