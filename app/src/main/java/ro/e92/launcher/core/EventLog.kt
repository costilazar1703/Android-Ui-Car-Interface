package ro.e92.launcher.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Jurnalul de evenimente care supraviețuiește repornirilor unității.
 *
 * logcat-ul se pierde la fiecare pornire a motorului (unitatea repornește din
 * cauza căderii de tensiune), iar ce se întâmplă pe drum nu se mai poate citi
 * după aceea. Aici scriem puține linii, doar ce contează - pornirea, contactul,
 * stările Bluetooth, puntea, volumul, ceasul - într-un fișier citit de pe laptop
 * cu `scripts/e92.ps1 events`.
 *
 * Fiecare linie are ora de perete (poate fi 2013 până la sincronizarea GPS) și
 * timpul de la pornire, după care se poate ordona sigur.
 */
object EventLog {

    private const val TAG = "EventLog"
    private const val FILE = "events.log"
    private const val OLD = "events.old.log"
    private const val MAX_BYTES = 256 * 1024L

    private val io = Executors.newSingleThreadExecutor()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        dir = context.filesDir
    }

    fun log(tag: String, message: String) {
        Log.i(tag, message)
        val target = dir ?: return
        val wall = format.format(Date())
        val up = SystemClock.elapsedRealtime() / 1000
        val line = "$wall  +${up}s  $tag  $message\n"
        io.execute {
            runCatching {
                val file = File(target, FILE)
                if (file.length() > MAX_BYTES) file.renameTo(File(target, OLD))
                file.appendText(line)
            }.onFailure { Log.w(TAG, "nu pot scrie jurnalul", it) }
        }
    }

    /** Ultimele [lines] linii, din fișierul vechi și cel curent. */
    fun tail(lines: Int): String {
        val target = dir ?: return ""
        val all = listOf(File(target, OLD), File(target, FILE))
            .filter { it.exists() }
            .flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }
        return all.takeLast(lines).joinToString("\n", postfix = "\n")
    }
}
