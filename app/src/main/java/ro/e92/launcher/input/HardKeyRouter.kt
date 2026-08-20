package ro.e92.launcher.input

import android.util.SparseArray
import android.view.KeyEvent
import androidx.core.util.forEach
import ro.e92.launcher.core.Prefs

/**
 * Traduce keycode-uri în [LauncherAction].
 *
 * Default-urile de mai jos sunt PLACEHOLDER. Controller-ul CIC poate trimite
 * rotația ca DPAD_LEFT/RIGHT, ca SYSTEM_NAVIGATION_*, ca evenimente de scroll sau
 * ca coduri vendor peste 255 — se decide empiric în Faza 0.2:
 *
 *     adb shell getevent -l
 *
 * În aplicație există și modul "învață tasta" (Setări → Keymap), care nu are nevoie
 * de laptop: apeși acțiunea din listă, apoi tasta fizică, și maparea se salvează.
 *
 * SparseArray, nu HashMap<Int, _>: fără boxing pe cheie, iar lookup-ul se face
 * în onKeyDown, unde bugetul e zero alocări.
 */
class HardKeyRouter(private val prefs: Prefs) {

    private val map = SparseArray<LauncherAction>(32)

    /** Non-null cât timp Setările așteaptă o tastă pentru acțiunea respectivă. */
    var pendingLearn: LauncherAction? = null

    /** Notificat după ce o tastă a fost legată în modul "învață". */
    var onLearned: ((keyCode: Int, action: LauncherAction) -> Unit)? = null

    init {
        loadDefaults()
        loadOverrides()
    }

    /**
     * @return acțiunea rezolvată, sau null dacă tasta e necunoscută / a fost
     *         consumată de modul "învață".
     */
    fun resolve(keyCode: Int): LauncherAction? {
        val learning = pendingLearn
        if (learning != null) {
            bind(keyCode, learning)
            pendingLearn = null
            onLearned?.invoke(keyCode, learning)
            return null
        }
        return map.get(keyCode)
    }

    fun bind(keyCode: Int, action: LauncherAction) {
        // O acțiune poate avea mai multe taste, dar o tastă are o singură acțiune.
        map.put(keyCode, action)
        persist()
    }

    fun unbind(keyCode: Int) {
        map.remove(keyCode)
        persist()
    }

    /** Fără alocări — apelat din onKeyUp pentru fiecare tastă. */
    fun isMapped(keyCode: Int): Boolean = map.get(keyCode) != null

    fun keysFor(action: LauncherAction): List<Int> {
        val result = ArrayList<Int>(2)
        map.forEach { key, value -> if (value == action) result.add(key) }
        return result
    }

    fun resetToDefaults() {
        prefs.clearKeymap()
        map.clear()
        loadDefaults()
    }

    /** Snapshot pentru ecranul de diagnostic. */
    fun dump(): List<Pair<Int, LauncherAction>> {
        val out = ArrayList<Pair<Int, LauncherAction>>(map.size())
        map.forEach { key, value -> out.add(key to value) }
        return out.sortedBy { it.second.ordinal }
    }

    private fun persist() {
        val sb = StringBuilder()
        map.forEach { key, value ->
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(key).append(':').append(value.name)
        }
        prefs.keymapRaw = sb.toString()
    }

    private fun loadOverrides() {
        val raw = prefs.keymapRaw
        if (raw.isEmpty()) return
        // Overrides-urile înlocuiesc complet default-urile: dacă utilizatorul a
        // învățat tastele reale, presupunerile noastre doar ar încurca.
        map.clear()
        for (part in raw.split(';')) {
            val sep = part.indexOf(':')
            if (sep <= 0) continue
            val code = part.substring(0, sep).toIntOrNull() ?: continue
            val action = LauncherAction.byName(part.substring(sep + 1)) ?: continue
            map.put(code, action)
        }
    }

    private fun loadDefaults() {
        // --- Presupuneri pentru controller-ul iDrive (de confirmat în mașină) ---
        map.put(KeyEvent.KEYCODE_DPAD_LEFT, LauncherAction.ROTARY_CCW)
        map.put(KeyEvent.KEYCODE_DPAD_RIGHT, LauncherAction.ROTARY_CW)
        map.put(KeyEvent.KEYCODE_DPAD_CENTER, LauncherAction.SELECT)
        map.put(KeyEvent.KEYCODE_ENTER, LauncherAction.SELECT)
        map.put(KeyEvent.KEYCODE_NUMPAD_ENTER, LauncherAction.SELECT)
        map.put(KeyEvent.KEYCODE_DPAD_UP, LauncherAction.TILT_UP)
        map.put(KeyEvent.KEYCODE_DPAD_DOWN, LauncherAction.TILT_DOWN)

        map.put(KeyEvent.KEYCODE_BACK, LauncherAction.BACK)
        map.put(KeyEvent.KEYCODE_ESCAPE, LauncherAction.BACK)
        map.put(KeyEvent.KEYCODE_MENU, LauncherAction.HOME)
        map.put(KeyEvent.KEYCODE_INFO, LauncherAction.OPTION)

        // Butoane media de pe volan — astea sunt standard pe majoritatea unităților.
        map.put(KeyEvent.KEYCODE_MEDIA_NEXT, LauncherAction.MEDIA_NEXT)
        map.put(KeyEvent.KEYCODE_MEDIA_PREVIOUS, LauncherAction.MEDIA_PREV)
        map.put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, LauncherAction.MEDIA_PLAY_PAUSE)
        map.put(KeyEvent.KEYCODE_HEADSETHOOK, LauncherAction.MEDIA_PLAY_PAUSE)
        map.put(KeyEvent.KEYCODE_VOICE_ASSIST, LauncherAction.VOICE)
        map.put(KeyEvent.KEYCODE_SEARCH, LauncherAction.VOICE)

        map.put(KeyEvent.KEYCODE_MUSIC, LauncherAction.MEDIA)
        map.put(KeyEvent.KEYCODE_CALL, LauncherAction.PHONE)
        map.put(KeyEvent.KEYCODE_TV_RADIO_SERVICE, LauncherAction.RADIO)

        // --- Doar pentru testat pe AVD cu tastatura PC-ului ---
        // Nu strică nimic pe unitate: tastele astea nu există fizic acolo.
        map.put(KeyEvent.KEYCODE_N, LauncherAction.NAV)
        map.put(KeyEvent.KEYCODE_M, LauncherAction.MEDIA)
        map.put(KeyEvent.KEYCODE_P, LauncherAction.PHONE)
        map.put(KeyEvent.KEYCODE_R, LauncherAction.RADIO)
        map.put(KeyEvent.KEYCODE_A, LauncherAction.APPS)
        map.put(KeyEvent.KEYCODE_D, LauncherAction.DIAGNOSTICS)
        map.put(KeyEvent.KEYCODE_H, LauncherAction.HOME)
        map.put(KeyEvent.KEYCODE_O, LauncherAction.OPTION)
    }
}
