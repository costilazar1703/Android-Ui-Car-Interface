package ro.e92.launcher.core

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.Executors

/**
 * Legătura noastră cu stiva Feasycom (blueware): pty-ul /dev/goc_serial, pe
 * care scriem `AT#<CMD>\r\n` și citim linii `<2 litere><date>\r\n`. Vezi
 * docs/feasycom-protocol.md.
 *
 * Trei lucruri care nu se văd din protocol:
 *
 *  - **O_NOCTTY.** Procesele de aplicație sunt lideri de sesiune fără terminal
 *    (zygote face setsid), iar un open() simplu pe un tty l-ar face terminalul
 *    lor de control: când blueware moare, launcher-ul ar primi SIGHUP și ar
 *    muri odată cu el. De aceea [Os.open], nu FileInputStream.
 *
 *  - **Modul raw.** Implicit, un pty face ecou: tot ce scrie blueware s-ar
 *    întoarce la el ca niște comenzi, iar 0xFF din liniile MI n-ar ajunge
 *    neapărat întregi. Java nu are tcsetattr, NDK-ul nu e în proiect, iar
 *    `stty` nu e sigur pe Android 8.1. Folosim clasa de framework
 *    `android.hardware.SerialPort` (ascunsă, deci prin reflecție - pe API 27
 *    nu există restricții): open() face fix tcsetattr raw, fără ecou, pe un
 *    fd primit de la noi. Setarea rămâne pe tty, nu pe fd, deci o închidem
 *    imediat. Fără raw nu folosim legătura deloc: mai bine „Unavailable" decât
 *    comenzi false spre stivă.
 *
 *  - **Lista albă.** [send] refuză tot ce nu e în [ALLOWED]. DC, PA și restul
 *    comenzilor HFP/agendă sunt exact ce rupe telefonul (2026-11-03), așa că
 *    nu pot pleca de aici nici din greșeală.
 */
class FeasyLink(
    private val path: String,
    /** Înainte de fiecare deschidere: pornește stiva dacă nu rulează. */
    private val prepare: () -> Unit,
    private val listener: Listener
) {

    interface Listener {
        fun onLinkOpen()
        fun onLinkClosed(reason: String)
        fun onLine(code: String, data: ByteArray)
    }

    private val lock = Any()
    private val sender = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false
    @Volatile
    private var reopenRequested = false
    private var reader: Thread? = null

    /** Citit și scris sub [lock], ca o scriere să nu prindă un fd deja închis. */
    private var fd: FileDescriptor? = null

    fun start() {
        if (running) return
        running = true
        reader = Thread({ runLoop() }, "FeasyLink").apply {
            isDaemon = true
            start()
        }
    }

    /** Eliberează pty-ul: după întoarcere, bTSuite îl poate deschide din nou. */
    fun stop() {
        running = false
        reader?.join(STOP_WAIT_MS)
        reader = null
        sender.shutdown()
    }

    /** Închide și redeschide pty-ul; stiva rămâne pornită (repornită, n-ar mai termina asocierea). */
    fun reopen() {
        reopenRequested = true
    }

    /**
     * Pune comanda la coadă; firul de trimitere le lasă o pauză între ele, ca
     * bTSuite - modulul nu le primește bine lipite.
     *
     * @param quiet nu o trece în jurnal (doar pentru interogarea periodică de stare)
     */
    fun send(cmd: String, arg: String = "", quiet: Boolean = false) {
        if (cmd !in ALLOWED) {
            EventLog.log(TAG, "comandă refuzată: $cmd")
            return
        }
        if (!running) return
        sender.execute {
            val written = write("AT#$cmd$arg\r\n".toByteArray(Charsets.UTF_8))
            if (!quiet || !written) {
                EventLog.log(TAG, "→ AT#$cmd$arg" + if (written) "" else " (legătura e închisă)")
            }
            if (written) Thread.sleep(SEND_GAP_MS)
        }
    }

    private fun write(bytes: ByteArray): Boolean = synchronized(lock) {
        val target = fd ?: return false
        try {
            var offset = 0
            while (offset < bytes.size) {
                offset += Os.write(target, bytes, offset, bytes.size - offset)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "scriere eșuată", e)
            false
        }
    }

    private fun runLoop() {
        var lastFailure = ""
        while (running) {
            try {
                prepare()
                val opened = open()
                synchronized(lock) { fd = opened }
                lastFailure = ""
                EventLog.log(TAG, "$path deschis, raw")
                listener.onLinkOpen()
                val reason = try {
                    pump(opened)
                } finally {
                    synchronized(lock) {
                        fd = null
                        runCatching { Os.close(opened) }
                    }
                }
                EventLog.log(TAG, "$path închis: $reason")
                listener.onLinkClosed(reason)
                if (running) Thread.sleep(REOPEN_DELAY_MS)
            } catch (e: Exception) {
                // Stiva încă pornește sau nu putem deschide: repetăm rar și
                // jurnalizăm o singură dată, ca un drum lung să nu umple jurnalul.
                val failure = e.message ?: e.javaClass.simpleName
                if (failure != lastFailure) {
                    lastFailure = failure
                    EventLog.log(TAG, "nu pot deschide $path: $failure")
                    listener.onLinkClosed(failure)
                }
                if (running) Thread.sleep(RETRY_OPEN_MS)
            }
        }
    }

    private fun open(): FileDescriptor {
        if (!File(path).exists()) throw IllegalStateException("$path lipsește")
        val opened = Os.open(path, OsConstants.O_RDWR or OsConstants.O_NOCTTY, 0)
        try {
            makeRaw(opened)
        } catch (e: Exception) {
            runCatching { Os.close(opened) }
            throw IllegalStateException("modul raw a eșuat: ${e.javaClass.simpleName}", e)
        }
        return opened
    }

    private fun makeRaw(target: FileDescriptor) {
        val cls = Class.forName("android.hardware.SerialPort")
        val port = cls.getConstructor(String::class.java).newInstance(path)
        // open() își face propriul dup(); pe un pty viteza nu contează.
        ParcelFileDescriptor.dup(target).use { dup ->
            cls.getMethod("open", ParcelFileDescriptor::class.java, Int::class.javaPrimitiveType)
                .invoke(port, dup, PTY_SPEED)
            cls.getMethod("close").invoke(port)
        }
    }

    /** Citește până când pty-ul cade sau ni se cere să ne oprim; întoarce motivul. */
    private fun pump(target: FileDescriptor): String {
        val poll = arrayOf(StructPollfd().apply {
            this.fd = target
            events = OsConstants.POLLIN.toShort()
        })
        val buffer = ByteArray(READ_SIZE)
        val line = ByteArrayOutputStream(LINE_MAX)
        reopenRequested = false
        while (running) {
            if (reopenRequested) return "redeschidere cerută"
            try {
                if (Os.poll(poll, POLL_MS) == 0) continue
                val revents = poll[0].revents.toInt()
                if (revents and OsConstants.POLLIN == 0) {
                    return "stiva a închis pty-ul (revents=$revents)"
                }
                val n = Os.read(target, buffer, 0, buffer.size)
                if (n <= 0) return "stiva a închis pty-ul"
                feed(buffer, n, line)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                return "eroare de citire: ${e.message}"
            }
        }
        return "oprit"
    }

    /** Taie pe CR/LF, pe octeți: titlurile sunt UTF-8, separatorii din MI sunt 0xFF. */
    private fun feed(bytes: ByteArray, count: Int, line: ByteArrayOutputStream) {
        for (i in 0 until count) {
            val b = bytes[i].toInt()
            if (b == '\r'.code || b == '\n'.code) {
                if (line.size() >= CODE_LENGTH) deliver(line.toByteArray())
                else if (line.size() > 0) Log.d(TAG, "linie scurtă ignorată")
                line.reset()
            } else if (line.size() < LINE_MAX) {
                line.write(b)
            }
        }
    }

    private fun deliver(raw: ByteArray) {
        val code = String(raw, 0, CODE_LENGTH, Charsets.ISO_8859_1)
        val data = raw.copyOfRange(CODE_LENGTH, raw.size)
        // O excepție din logica de stare nu trebuie să închidă legătura cu telefonul.
        runCatching { listener.onLine(code, data) }.onFailure { Log.w(TAG, "linia $code", it) }
    }

    companion object {
        private const val TAG = "FeasyLink"

        /**
         * Tot ce are voie să plece spre stivă: muzică (A2DP/AVRCP), împerechere,
         * nume, PIN. Fără DC/DD, DY, PA și nimic altceva din HFP sau agendă.
         */
        val ALLOWED = setOf(
            "MI", "MJ", "MA", "MD", "ME", "MZ", "MV",
            "VA", "VB",
            "MM", "MN", "MX",
            "DA", "DB", "DV",
            // Închide HFP-ul ridicat de telefon: cu el sus, legătura cade la 15 s.
            "DD"
        )

        private const val CODE_LENGTH = 2
        private const val LINE_MAX = 2048
        private const val READ_SIZE = 512
        private const val PTY_SPEED = 115200
        private const val POLL_MS = 500
        private const val SEND_GAP_MS = 150L
        private const val REOPEN_DELAY_MS = 2_000L
        private const val RETRY_OPEN_MS = 10_000L
        private const val STOP_WAIT_MS = 2_000L
    }
}
