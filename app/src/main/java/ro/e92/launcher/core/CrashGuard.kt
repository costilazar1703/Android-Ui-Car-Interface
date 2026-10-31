package ro.e92.launcher.core

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import ro.e92.launcher.ui.HomeActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Prinde crash-urile, le scrie pe disc și — doar când e sigur — repornește
 * launcher-ul.
 *
 * **Regula care contează cel mai mult:** repornirea se face DOAR dacă launcher-ul
 * era în față în momentul crash-ului. Dacă erai în marșarier, camera și senzorii
 * vendorului sunt deasupra; o repornire „ajutătoare" ar aduce meniul peste ele
 * exact când ai nevoie să vezi ce e în spate. În cazul ăla jurnalul se scrie, dar
 * aplicația rămâne oprită — Android o readuce oricum la următorul HOME.
 *
 * **Protecție la buclă:** la al treilea crash în două minute, crash-ul e predat
 * sistemului. Android repornește singur aplicația HOME când moare — deci o
 * oprire tăcută la fiecare crash ar ține tableta într-o buclă fără ieșire. Predat
 * sistemului, crash-ul activează plasa Android: dialog și, după câteva repetări,
 * renunțarea la launcher în favoarea altuia.
 *
 * Nu folosește [Services]: dacă crash-ul vine chiar din inițializarea lor,
 * gardianul trebuie să meargă oricum.
 */
object CrashGuard {

    private const val PREFS = "e92_crash"
    private const val KEY_TIMES = "times"
    private const val KEY_LAST = "last_summary"

    private const val RESTART_DELAY_MS = 1500L
    private const val LOOP_WINDOW_MS = 2 * 60_000L
    private const val LOOP_LIMIT = 3
    private const val KEEP_LOGS = 10

    @Volatile
    private var foreground = false

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (a is HomeActivity) foreground = true }
            override fun onActivityPaused(a: Activity) { if (a is HomeActivity) foreground = false }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })

        val system = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val loop = runCatching { handle(app, thread, error) }.getOrDefault(false)
            if (loop && system != null) {
                // Bucla: predam crash-ul sistemului. Doar asa afla Android ca
                // aplicatia crapa in mod repetat - si doar atunci isi activeaza
                // propria plasa de siguranta, care renunta la launcher si te lasa
                // sa alegi altul (inclusiv interfata veche). Fara asta, fiind
                // aplicatia HOME, sistemul ne-ar reporni la nesfarsit: am vazut-o
                // pe emulator, unde repornirea venea de la Android, nu de la noi.
                system.uncaughtException(thread, error)
                return@setDefaultUncaughtExceptionHandler
            }
            // In afara buclei: oprire TACUTA. Handler-ul sistemului afiseaza
            // dialogul „E92 has stopped", care poate aparea peste camera de
            // marsarier.
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /** Rezumatul ultimului crash, pentru ecranul de Diagnostic. Gol = niciunul. */
    fun lastSummary(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, "") ?: ""

    /** @return true daca e o bucla de crash-uri. */
    private fun handle(app: Application, thread: Thread, error: Throwable): Boolean {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        writeLog(app, stamp, thread, error)

        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val recent = (sp.getString(KEY_TIMES, "") ?: "")
            .split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it in 0..LOOP_WINDOW_MS } + now

        // commit(), nu apply(): procesul moare în câteva milisecunde.
        sp.edit()
            .putString(KEY_TIMES, recent.joinToString(","))
            .putString(KEY_LAST, "$stamp  ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            .commit()

        val loop = recent.size >= LOOP_LIMIT
        if (loop) return true
        // Repornirea proprie e o rezerva: Android reporneste oricum aplicatia
        // HOME cand moare in fata. Ramane pentru ROM-uri de vendor care n-o fac.
        if (foreground) scheduleRestart(app)
        return false
    }

    /** Reporneste aplicatia curat: programeaza HomeActivity, apoi opreste procesul. */
    fun restart(context: Context) {
        scheduleRestart(context)
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    private fun scheduleRestart(context: Context) {
        val intent = Intent(context, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pending)
    }

    private fun writeLog(context: Context, stamp: String, thread: Thread, error: Throwable) {
        val dir = Storage.logs(context)
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

        File(dir, "crash_$stamp.txt").writeText(
            buildString {
                appendLine("E92 Launcher crash  $stamp")
                appendLine("versiune: $version  pachet: ${context.packageName}")
                appendLine("dispozitiv: ${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE}")
                appendLine("thread: ${thread.name}   launcher in fata: $foreground")
                appendLine()
                append(trace)
            }
        )

        // Doar ultimele zece: pe o unitate care merge ani de zile, jurnalele nu
        // trebuie să crească fără limită.
        dir.listFiles { f -> f.name.startsWith("crash_") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP_LOGS)
            ?.forEach { it.delete() }
    }
}
