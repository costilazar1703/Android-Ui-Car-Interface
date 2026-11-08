package ro.e92.launcher.media

import android.content.Context
import ro.e92.launcher.core.EventLog
import ro.e92.launcher.core.KswVolume
import ro.e92.launcher.core.Services

/**
 * Muzica mai încet cât e scena de marșarier, ca să se audă bipurile; la ieșire revine
 * exact volumul de dinainte.
 *
 * Volumul unității se salvează (EventCenter îl retrimite la fiecare pornire), deci ținem
 * minte valoarea originală în setări: dacă launcher-ul repornește în marșarier,
 * [restoreIfLeft] o pune înapoi la următoarea pornire.
 */
object ReverseDuck {

    fun duck(context: Context) {
        val prefs = Services.prefs
        if (prefs.duckedFromVolume >= 0) return
        val before = KswVolume.current(context) ?: return
        val quiet = before * DUCK_PERCENT / 100
        if (quiet == before) return
        prefs.duckedFromVolume = before
        KswVolume.set(context, quiet)
        EventLog.log(TAG, "volum $before → $quiet în marșarier")
    }

    fun restore(context: Context) {
        val prefs = Services.prefs
        val before = prefs.duckedFromVolume
        if (before < 0) return
        prefs.duckedFromVolume = -1
        val now = KswVolume.current(context)
        // Dacă șoferul a schimbat volumul în marșarier, îi lăsăm alegerea.
        if (now != null && now != before * DUCK_PERCENT / 100) return
        KswVolume.set(context, before)
        EventLog.log(TAG, "volum înapoi la $before")
    }

    /** La pornire: o scenă întreruptă (launcher repornit în R) nu lasă volumul jos. */
    fun restoreIfLeft(context: Context) = restore(context)

    private const val TAG = "Reverse"
    /** 60 %: muzica rămâne, dar bipurile trec peste ea. */
    private const val DUCK_PERCENT = 60
}
