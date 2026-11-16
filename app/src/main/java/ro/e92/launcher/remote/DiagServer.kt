package ro.e92.launcher.remote

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import ro.e92.launcher.core.CrashGuard
import ro.e92.launcher.core.Prefs
import ro.e92.launcher.core.Services
import ro.e92.launcher.core.SelfUpdate
import ro.e92.launcher.input.KeyEventLog
import ro.e92.launcher.ui.HomeActivity
import ro.e92.launcher.ui.screens.DiagnosticsReport
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Înlocuitorul de adb pe unitatea asta.
 *
 * De ce există: pe RK3399-ul din mașină portul USB e doar host (laptopul nu
 * vede nimic, oricum ai seta MTP / USB debugging), iar adb peste Wi-Fi refuză
 * conexiunea fără root. Singurul cod pe care îl putem rula pe unitate e al
 * nostru — așa că launcher-ul ascultă el pe Wi-Fi, pe hotspot-ul telefonului,
 * și laptopul vorbește cu el prin `scripts/e92.ps1`.
 *
 * Rulează cu drepturile aplicației, nu ale shell-ului adb: `logcat` vede doar
 * procesul nostru, `dumpsys` e de obicei refuzat. Pentru restul (getprop, pm,
 * ls, cat pe /proc și /sdcard) ajunge.
 *
 * Fiecare cerere poartă cheia din ecranul de Diagnostic (header `X-Key` sau
 * `?k=`): launcher-ul poate intra și pe alte rețele Wi-Fi decât hotspot-ul tău.
 */
object DiagServer {

    const val PORT = 8092

    private const val TAG = "E92Diag"
    private const val PREFS = "e92_diag"
    private const val KEY_KEY = "key"
    private const val SHELL_TIMEOUT_S = 20L
    private const val SNIFF_CAPACITY = 200

    private lateinit var app: Application
    private var started = false
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** HomeActivity în față, pentru captura de ecran. Slabă: nu ținem activitatea în viață. */
    @Volatile
    private var resumed: WeakReference<Activity>? = null

    private val sniffLog = ArrayDeque<String>()
    private val sniffActions = LinkedHashSet<String>()
    private var sniffReceiver: BroadcastReceiver? = null

    fun start(application: Application) {
        if (started) return
        started = true
        app = application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (a is HomeActivity) resumed = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (a is HomeActivity) resumed = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
        Thread({ acceptLoop() }, "e92-diag").apply { isDaemon = true }.start()
    }

    /** Cheia de 4 cifre afișată pe ecran. Generată o dată, apoi aceeași. */
    fun key(): String {
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(KEY_KEY, null)?.let { return it }
        val fresh = Random.nextInt(1000, 10000).toString()
        sp.edit().putString(KEY_KEY, fresh).apply()
        return fresh
    }

    /** `ip:port` pe Wi-Fi, sau null dacă unitatea n-are o adresă IPv4. */
    fun address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }
            ?.hostAddress
            ?.let { "$it:$PORT" }
    }.getOrNull()

    // ------------------------------------------------------------------------
    // HTTP minimal: o cerere pe conexiune, fără keep-alive. Clientul e un
    // script pe laptop, nu un browser care să aibă nevoie de mai mult.
    // ------------------------------------------------------------------------

    private fun acceptLoop() {
        while (true) {
            try {
                ServerSocket(PORT).use { server ->
                    Log.i(TAG, "ascult pe ${address() ?: "?"}")
                    while (true) {
                        val socket = server.accept()
                        pool.execute { serve(socket) }
                    }
                }
            } catch (e: Exception) {
                // Portul poate fi ocupat o clipă după o repornire a procesului.
                Log.w(TAG, "server oprit, reîncerc", e)
                Thread.sleep(3000)
            }
        }
    }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
        val contentLength: Long
    )

    private fun serve(socket: Socket) {
        socket.use {
            it.soTimeout = 60_000
            val input = BufferedInputStream(it.getInputStream())
            val out = it.getOutputStream()
            try {
                val req = parse(input) ?: return
                val given = req.headers["x-key"] ?: req.query["k"]
                if (given != key()) {
                    respond(out, 403, "cheie greșită - o vezi pe ecranul Diagnostic\n")
                    return
                }
                route(req, out)
            } catch (e: Exception) {
                Log.w(TAG, "cerere eșuată", e)
                runCatching { respond(out, 500, "${e.javaClass.simpleName}: ${e.message}\n") }
            }
        }
    }

    private fun parse(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val query = if (q < 0) emptyMap() else target.substring(q + 1).split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) decode(pair) to "" else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
            }
        return Request(parts[0], path, query, headers, input, headers["content-length"]?.toLongOrNull() ?: 0L)
    }

    private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
    }

    private fun readBody(req: Request): ByteArray {
        val out = ByteArrayOutputStream(req.contentLength.toInt().coerceAtLeast(0))
        copy(req.body, out, req.contentLength)
        return out.toByteArray()
    }

    private fun copy(input: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(16 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun respond(out: OutputStream, code: Int, text: String) =
        respond(out, code, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "ERR"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun route(req: Request, out: OutputStream) {
        when (req.path) {
            "/", "/help" -> respond(out, 200, HELP)
            "/info" -> respond(out, 200, info())
            "/events" -> respond(out, 200, ro.e92.launcher.core.EventLog.tail(req.query["n"]?.toIntOrNull() ?: 400))
            "/bt" -> respond(out, 200, Services.kswBt.statusReport())
            "/bt/engine" -> respond(out, 200, btEngine(req.query["set"].orEmpty()))
            "/bt/do" -> respond(out, 200, btDo(req.query["c"].orEmpty()))
            "/keys" -> respond(out, 200, KeyEventLog.snapshot().joinToString("\n").ifEmpty { "(nicio tastă)" } + "\n")
            "/logcat" -> {
                val lines = req.query["n"]?.toIntOrNull() ?: 500
                respond(out, 200, shell("logcat -d -v time -t $lines"))
            }
            "/shell" -> {
                val cmd = if (req.method == "POST") String(readBody(req), Charsets.UTF_8) else req.query["c"].orEmpty()
                if (cmd.isBlank()) respond(out, 400, "comandă goală\n") else respond(out, 200, shell(cmd))
            }
            "/sniff" -> respond(out, 200, sniff(req.query["actions"]))
            "/sim" -> respond(out, 200, simulate(req.query))
            "/mcu" -> respond(out, 200, mcu(req.query))
            "/content" -> respond(out, 200, content(req.query["uri"].orEmpty()))
            "/screenshot" -> {
                val png = screenshot()
                if (png == null) respond(out, 409, "launcher-ul nu e în față\n")
                else respond(out, 200, "image/png", png)
            }
            "/install" -> {
                if (req.method != "POST" || req.contentLength <= 0) respond(out, 400, "trimite APK-ul ca body POST\n")
                else respond(out, 200, install(req))
            }
            else -> respond(out, 404, HELP)
        }
    }

    // ------------------------------------------------------------------------

    private fun info(): String = buildString {
        appendLine("model      = ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE})")
        appendLine("android    = ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("build      = ${Build.DISPLAY}")
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        appendLine("launcher   = ${app.packageName} $version")
        appendLine("last crash = ${CrashGuard.lastSummary(app).ifEmpty { "—" }}")
        appendLine()
        append(DiagnosticsReport.build(app, resumed?.get()?.window?.decorView))
        appendLine()
    }

    private fun btEngine(value: String): String {
        if (value != Prefs.BT_ENGINE_E92 && value != Prefs.BT_ENGINE_KSW) return "set=e92 sau set=ksw\n"
        Services.kswBt.setEngine(value)
        return "motor: $value\n"
    }

    /**
     * Probe în mașină pentru ecranul de marșarier al MCU-ului. Se pot schimba doar cele patru
     * setări de afișare 0x6A pe care le trimit și meniurile KSW (tip cameră, linii, radar,
     * video original), fiecare cu valorile ei permise. Nimic altceva nu pleacă pe aici: nici
     * setări Factory (0x70), nici tipul mașinii, nici cadre libere.
     */
    /** Aceleași valori ca [ro.e92.launcher.can.KswCarDataSource.sendSourceMode]. */
    private val MCU_SOURCE_MODES = setOf(1, 4, 7)

    private fun mcu(query: Map<String, String>): String {
        val p = Services.prefs
        // Mod de afișare al MCU-ului: doar „original" sau „arm", trimis o dată, nu se salvează.
        query["view"]?.let { view ->
            if (view != "original" && view != "arm") return "view=$view: permis original sau arm, nimic trimis\n"
            val sent = Services.kswCar?.sendViewMode(view == "original") == true
            return "view=$view trimis=${if (sent) 1 else 0}\n"
        }
        // Doar citire: MCU-ul își raportează setările (cadrul 0x13), ca la pornire.
        if (query["query"] == "1") {
            val sent = Services.kswCar?.requestMcuInfo() == true
            return "query trimis=${if (sent) 1 else 0} - raspunsul apare in /events ca MCU <-\n"
        }
        // Sursa MCU-ului: doar 1 (muzica), 4 (muzica Bluetooth), 7 (telefon), trimisa o data.
        query["src"]?.let { raw ->
            val mode = raw.toIntOrNull()
            if (mode == null || mode !in MCU_SOURCE_MODES) {
                return "src=$raw: permis 1, 4 sau 7, nimic trimis\n"
            }
            val sent = Services.kswCar?.sendSourceMode(mode) == true
            return "src=$mode trimis=${if (sent) 1 else 0}\n"
        }
        val allowed = linkedMapOf("camera" to 0..3, "track" to 0..1, "radar" to 0..1, "origvideo" to -1..1, "hb" to 0..1, "after" to 0..2)
        // Întâi verificăm tot: o valoare greșită nu schimbă nimic.
        for ((name, range) in allowed) {
            val raw = query[name] ?: continue
            val value = raw.toIntOrNull()
            if (value == null || value !in range) return "$name=$raw: permis $range, nimic schimbat\n"
        }
        query["camera"]?.let { p.mcuCameraType = it.toInt() }
        query["track"]?.let { p.mcuTrack = it.toInt() }
        query["radar"]?.let { p.mcuRadar = it.toInt() }
        query["origvideo"]?.let { p.mcuOrigVideo = it.toInt() }
        query["after"]?.let { p.mcuAfterReverse = it.toInt() }
        query["hb"]?.let {
            p.mcuHeartbeat = it.toInt()
            Services.kswCar?.updateHeartbeat()
        }
        val changed = allowed.keys.any { it in query }
        val sent = changed && Services.kswCar?.sendMcuReverseSetup() == true
        return "camera=${p.mcuCameraType} track=${p.mcuTrack} radar=${p.mcuRadar} origvideo=${p.mcuOrigVideo} hb=${p.mcuHeartbeat} after=${p.mcuAfterReverse}" +
            " scena=${if (p.reverseScene) 1 else 0} trimis=${if (sent) 1 else 0}\n"
    }

    /** Pentru probe fără mașină: /sim?reverse=1&cm=150,40,18,60 /sim?wifi=0..3|off|real */
    private fun simulate(query: Map<String, String>): String {
        query["wifi"]?.let {
            Services.net.simulatedLevel = if (it == "real") null else if (it == "off") -1 else it.toIntOrNull()
            Services.net.refresh()
        }
        query["scene"]?.let {
            Services.prefs.reverseScene = it == "1"
            Services.reverse.applyCameraMode()
        }
        Services.reverse.simulateCan(
            raw = query["raw"]?.split(",")?.mapNotNull { it.toIntOrNull() },
            steer = query["steer"]?.toIntOrNull(),
            gear = query["gear"]?.toIntOrNull(16),
            drift = query["drift"]?.let { it == "1" },
            button = query["dscbutton"] == "1"
        )
        query["reverse"]?.let { on ->
            val cm = query["cm"]?.split(",")?.mapNotNull { it.toIntOrNull() }
            Services.reverse.simulate(on == "1", cm)
        }
        return "ok\n"
    }

    private fun btDo(command: String): String {
        val bt = Services.kswBt
        when (command) {
            "connect" -> bt.reconnectLast()
            "repair" -> bt.repair()
            "pair" -> bt.makeVisible()
            "playpause" -> bt.playPause()
            "next" -> bt.next()
            "previous" -> bt.previous()
            else -> return "c=connect|repair|pair|playpause|next|previous\n"
        }
        return "trimis: $command\n"
    }

    private fun shell(cmd: String): String {
        val process = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        // Citit pe alt fir: o comandă care umple buffer-ul s-ar bloca înainte de waitFor.
        val output = ByteArrayOutputStream()
        val reader = Thread { runCatching { process.inputStream.copyTo(output) } }.apply { start() }
        val finished = process.waitFor(SHELL_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) process.destroy()
        reader.join(2000)
        val text = output.toString("UTF-8")
        return if (finished) "$text\n[exit ${process.exitValue()}]\n" else "$text\n[oprit după ${SHELL_TIMEOUT_S}s]\n"
    }

    /**
     * Ascultă broadcast-uri pe acțiuni date de laptop, fără reinstalare. Așa
     * găsim acțiunile reale ale MCU-ului / CAN-ului: încerci un nume, schimbi o
     * treaptă sau pornești farurile, apoi citești ce a venit.
     */
    @Synchronized
    private fun sniff(actions: String?): String {
        val added = actions?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (added.isNotEmpty() && sniffActions.addAll(added)) {
            sniffReceiver?.let { r -> runCatching { app.unregisterReceiver(r) } }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    intent ?: return
                    recordSniff(intent)
                }
            }
            app.registerReceiver(receiver, IntentFilter().apply { sniffActions.forEach { addAction(it) } })
            sniffReceiver = receiver
        }
        return buildString {
            appendLine("ascult pe: ${sniffActions.ifEmpty { setOf("(nimic - adaugă cu ?actions=a,b)") }.joinToString()}")
            appendLine("primite: ${sniffLog.size}")
            sniffLog.forEach { appendLine(it) }
        }
    }

    @Synchronized
    private fun recordSniff(intent: Intent) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = StringBuilder("$stamp ${intent.action}")
        intent.extras?.let { b -> for (k in b.keySet()) line.append("\n    ").append(k).append(" = ").append(b.get(k)) }
        sniffLog.addLast(line.toString())
        while (sniffLog.size > SNIFF_CAPACITY) sniffLog.removeFirst()
    }

    /**
     * Citește un content provider prin ContentResolver-ul aplicației. Comanda
     * `content` din shell e refuzată pentru noi (cere ACCESS_CONTENT_PROVIDERS_EXTERNALLY),
     * dar un provider exportat fără permisiune - cum pare a fi SysVarProvider-ul
     * KSW, unde stau setările unității - se poate citi direct din aplicație.
     */
    private fun content(uri: String): String {
        if (uri.isBlank()) return "lipsește ?uri=\n"
        return runCatching {
            app.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { c ->
                buildString {
                    appendLine(c.columnNames.joinToString(" | "))
                    while (c.moveToNext()) {
                        appendLine((0 until c.columnCount).joinToString(" | ") { i ->
                            runCatching { c.getString(i) }.getOrNull() ?: "null"
                        })
                    }
                }
            } ?: "query a întors null\n"
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message}\n" }
    }

    /** PNG cu fereastra launcher-ului. Alte aplicații nu se pot captura fără root. */
    private fun screenshot(): ByteArray? {
        val activity = resumed?.get() ?: return null
        var png: ByteArray? = null
        val done = CountDownLatch(1)
        main.post {
            try {
                val decor = activity.window.decorView
                if (decor.width > 0 && decor.height > 0) {
                    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(bitmap))
                    png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    bitmap.recycle()
                }
            } finally {
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        return png
    }

    /**
     * Salvează APK-ul și deschide instalatorul de sistem. Nu se poate instala
     * tăcut fără root: pe ecranul unității trebuie apăsat „Install". Prima dată
     * Android cere și permisiunea „Install unknown apps" pentru launcher.
     */
    private fun install(req: Request): String {
        val apk = File(SelfUpdate.updatesDir(app), "update.apk")
        apk.outputStream().use { copy(req.body, it, req.contentLength) }
        if (apk.length() != req.contentLength) return "primit ${apk.length()} din ${req.contentLength} octeți - reîncearcă\n"

        var result = SelfUpdate.Result.FAILED
        val done = CountDownLatch(1)
        main.post {
            result = SelfUpdate.install(app, apk)
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        return when (result) {
            SelfUpdate.Result.NEEDS_PERMISSION ->
                "APK salvat (${apk.length()} octeți), dar launcher-ul n-are voie să instaleze.\n" +
                    "Pe unitate s-a deschis setarea: activează „Allow from this source”, apoi trimite din nou.\n"
            SelfUpdate.Result.INSTALLING -> "APK primit (${apk.length()} octeți). Apasă „Install” pe ecranul unității.\n"
            else -> "APK primit, dar instalatorul nu s-a putut deschide.\n"
        }
    }

    private val HELP = """
        E92 diag - comenzi (toate cer cheia: header X-Key sau ?k=)
          GET  /info                 unitate + tot ce arată ecranul Diagnostic
          GET  /bt                   starea Bluetooth, cheie=valoare
          GET  /sim?reverse=1&cm=150,40,18,60   simulează marșarierul; /sim?wifi=0..3|off|real
          GET  /mcu?camera=0..3&radar=0|1&track=0|1&origvideo=-1|0|1&hb=0|1   setările de marșarier ale MCU-ului; /mcu?view=original|arm; after=0|1|2 (original la ieșirea din R); query=1 (starea MCU); src=1|4|7
          GET  /bt/engine?set=e92    motorul Bluetooth: e92 sau ksw
          GET  /bt/do?c=playpause    connect, repair, pair, playpause, next, previous
          GET  /keys                 ultimele taste primite
          GET  /logcat?n=500         logcat (doar procesul launcher-ului)
          POST /shell                body = comandă sh, rulată ca aplicația
          GET  /sniff?actions=a,b    ascultă broadcast-uri; fără parametru = ce a venit
          GET  /content?uri=...      citește un content provider (ex. setările KSW)
          GET  /screenshot           PNG cu launcher-ul (doar când e în față)
          POST /install              body = APK; deschide instalatorul pe ecran
    """.trimIndent() + "\n"
}
