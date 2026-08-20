package ro.e92.launcher.input

import android.os.SystemClock
import android.view.KeyEvent

/**
 * Buffer circular cu ultimele taste primite, pentru ecranul de diagnostic.
 *
 * Motivul existenței: ciclul de debug pe unitate e lent (stick USB sau ADB peste
 * hotspot). Trebuie să poți afla ce keycode trimite un buton fizic stând în mașină,
 * fără laptop și fără `getevent`.
 *
 * Scrierea se face din onKeyDown → zero alocări: intrările sunt pre-alocate și
 * doar mutate pe loc.
 */
object KeyEventLog {

    class Entry {
        var keyCode: Int = 0
        var action: LauncherAction? = null
        var consumed: Boolean = false
        var atUptimeMs: Long = 0L
    }

    private const val CAPACITY = 40

    private val buffer = Array(CAPACITY) { Entry() }
    private var writeIndex = 0
    private var size = 0

    /** Crește la fiecare tastă; ecranul de diagnostic redesenează doar dacă s-a schimbat. */
    @Volatile
    var revision: Int = 0
        private set

    @Synchronized
    fun record(keyCode: Int, action: LauncherAction?, consumed: Boolean) {
        val e = buffer[writeIndex]
        e.keyCode = keyCode
        e.action = action
        e.consumed = consumed
        e.atUptimeMs = SystemClock.uptimeMillis()

        writeIndex = (writeIndex + 1) % CAPACITY
        if (size < CAPACITY) size++
        revision++
    }

    /** Cea mai recentă intrare prima. Alocă — apelat doar de ecranul de diagnostic. */
    @Synchronized
    fun snapshot(): List<String> {
        val out = ArrayList<String>(size)
        val now = SystemClock.uptimeMillis()
        for (i in 0 until size) {
            var idx = writeIndex - 1 - i
            if (idx < 0) idx += CAPACITY
            val e = buffer[idx]
            val name = KeyEvent.keyCodeToString(e.keyCode)
            val mapped = e.action?.name ?: "—"
            val ago = (now - e.atUptimeMs) / 1000
            out.add("${e.keyCode}  $name → $mapped  ${if (e.consumed) "" else "(ignorat) "}${ago}s")
        }
        return out
    }

    @Synchronized
    fun clear() {
        size = 0
        writeIndex = 0
        revision++
    }
}
