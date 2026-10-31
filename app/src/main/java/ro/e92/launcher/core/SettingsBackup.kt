package ro.e92.launcher.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup-ul setărilor: maparea butoanelor, aplicațiile atribuite, tot ce ai
 * configurat cu rotița și n-ai vrea să refaci.
 *
 * **Automat, în ambele sensuri.** Scrierea se face singură la fiecare schimbare
 * (cu o mică întârziere, ca zece apăsări la rând să dea o singură scriere), iar
 * restaurarea se face singură la prima pornire după o instalare pe curat, dacă
 * găsește un backup. Un backup pe care trebuie să-ți amintești să-l faci e un
 * backup care lipsește exact când îți trebuie.
 *
 * Fișierul e JSON lizibil, cu tipul fiecărei valori păstrat — o restaurare care
 * ar transforma un Int într-un String ar crăpa la prima citire.
 */
object SettingsBackup {

    private const val FORMAT = 1
    private const val DEBOUNCE_MS = 2000L

    private val thread by lazy { HandlerThread("settings-backup").also { it.start() } }
    private val handler by lazy { Handler(thread.looper) }

    // Ținut într-un câmp: SharedPreferences păstrează listener-ele ca referințe
    // SLABE, iar unul creat inline ar fi colectat de GC și ar tăcea fără eroare.
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun startAutoBackup(context: Context, prefs: Prefs) {
        val app = context.applicationContext
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in Prefs.VOLATILE_KEYS) return@OnSharedPreferenceChangeListener
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ runCatching { write(app, prefs.snapshot()) } }, DEBOUNCE_MS)
        }
        listener = l
        prefs.registerListener(l)
    }

    /**
     * Rulează ÎNAINTE de [Services.init]: componentele citesc setările o singură
     * dată, la construcție, deci restaurarea trebuie să fie deja pe disc când
     * pornesc.
     *
     * @return true dacă a restaurat ceva.
     */
    fun restoreIfFreshInstall(context: Context): Boolean {
        val sp = context.getSharedPreferences(Prefs.FILE_NAME, Context.MODE_PRIVATE)
        // „Pe curat" = nicio cheie reala. Cele volatile (pagina curenta) pot
        // aparea dintr-o singura apasare si nu inseamna ca ai configurat ceva.
        if (sp.all.keys.any { it !in Prefs.VOLATILE_KEYS }) return false
        val file = Storage.backupFile(context)
        if (!file.exists()) return false
        return runCatching { apply(sp, read(file)) }.isSuccess
    }

    /** Restaurare cerută din Setări. Cere o repornire ca să aibă efect. */
    fun restoreNow(context: Context): Boolean {
        val file = Storage.backupFile(context)
        if (!file.exists()) return false
        val sp = context.getSharedPreferences(Prefs.FILE_NAME, Context.MODE_PRIVATE)
        return runCatching { apply(sp, read(file)) }.isSuccess
    }

    fun backupNow(context: Context, prefs: Prefs): Boolean =
        runCatching { write(context, prefs.snapshot()) }.isSuccess

    fun lastBackup(context: Context): File? = Storage.backupFile(context).takeIf { it.exists() }

    // ------------------------------------------------------------------ format

    private fun write(context: Context, values: Map<String, *>) {
        val out = JSONObject()
        out.put("format", FORMAT)
        out.put("saved", System.currentTimeMillis())
        val entries = JSONObject()
        for ((key, value) in values) {
            val typed = JSONObject()
            when (value) {
                is String -> typed.put("t", "s").put("v", value)
                is Int -> typed.put("t", "i").put("v", value)
                is Long -> typed.put("t", "l").put("v", value)
                is Float -> typed.put("t", "f").put("v", value.toDouble())
                is Boolean -> typed.put("t", "b").put("v", value)
                is Set<*> -> typed.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            entries.put(key, typed)
        }
        out.put("values", entries)

        // Scriere atomică: într-un fișier temporar, apoi redenumire. O pană de
        // curent la jumătatea scrierii ar lăsa altfel un backup corupt peste unul
        // bun — iar tableta din mașină se stinge brusc la fiecare oprire.
        val target = Storage.backupFile(context)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(out.toString(2))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun read(file: File): JSONObject = JSONObject(file.readText())

    private fun apply(sp: SharedPreferences, json: JSONObject) {
        val values = json.getJSONObject("values")
        val editor = sp.edit()
        for (key in values.keys()) {
            val typed = values.getJSONObject(key)
            when (typed.getString("t")) {
                "s" -> editor.putString(key, typed.getString("v"))
                "i" -> editor.putInt(key, typed.getInt("v"))
                "l" -> editor.putLong(key, typed.getLong("v"))
                "f" -> editor.putFloat(key, typed.getDouble("v").toFloat())
                "b" -> editor.putBoolean(key, typed.getBoolean("v"))
                "ss" -> {
                    val arr = typed.getJSONArray("v")
                    editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }
        editor.commit()
    }
}
