package ro.e92.launcher.can

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.e92.launcher.core.EventLog
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Datele motorului din adaptorul OBD2 Wi-Fi (ELM327) din priza mașinii.
 *
 * Unitatea KSW nu trimite turațiile, turbo-ul sau temperatura motorului, iar
 * viteza din GPS vine cu întârziere. Adaptorul le citește din calculatorul
 * motorului cu PID-urile standard OBD2 (mod 01):
 *   0D viteză · 0C turații · 0B presiune admisie (turbo = admisie - atmosferă)
 *   33 presiune atmosferică · 05 lichid de răcire · 2F nivel combustibil.
 * Autonomia nu există ca PID standard; o dă doar unitatea, dacă o trimite.
 *
 * Merge doar cât unitatea e pe Wi-Fi-ul adaptorului. Un PID la care mașina
 * răspunde „NO DATA" nu mai e cerut. Adaptoarele ELM acceptă de obicei un
 * singur client: dacă e deschis Torque, îl ține el.
 */
class ObdDataSource(
    private val scope: CoroutineScope,
    private val hosts: List<String> = DEFAULT_HOSTS
) : CanDataSource {

    override val id = "obd"
    override val description = "OBD2 Wi-Fi adapter (ELM327)"

    private val _state = MutableStateFlow(VehicleState.EMPTY)
    override val vehicleState: StateFlow<VehicleState> = _state.asStateFlow()

    override val isAvailable: Boolean
        get() = connected && SystemClock.elapsedRealtime() - _state.value.timestamp < CanDataSource.STALE_AFTER_MS

    @Volatile
    private var connected = false
    private var job: Job? = null

    private val unsupported = HashSet<String>()
    private var baroKpa = 101

    override fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = connect()
                if (socket == null) {
                    delay(RETRY_MS)
                    continue
                }
                runCatching { session(socket) }
                    .onFailure { EventLog.log(TAG, "OBD deconectat: ${it.message}") }
                connected = false
                runCatching { socket.close() }
                delay(RETRY_MS)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        connected = false
    }

    private fun connect(): Socket? {
        for (host in hosts) {
            val s = Socket()
            val ok = runCatching { s.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS) }.isSuccess
            if (ok) {
                s.soTimeout = READ_TIMEOUT_MS
                EventLog.log(TAG, "OBD conectat la $host:$PORT")
                return s
            }
            runCatching { s.close() }
        }
        return null
    }

    private suspend fun session(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        // Reset, fără ecou / spații / antete, protocol automat.
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
            command(output, input, cmd)
            if (cmd == "ATZ") delay(800)
        }
        unsupported.clear()
        connected = true
        var cycle = 0
        while (scope.isActive && connected) {
            val speed = pid(output, input, "0D")?.let { it[0] }
            val rpm = pid(output, input, "0C")?.let { (it[0] * 256 + it[1]) / 4 }
            if (cycle % 2 == 0) mapKpa = pid(output, input, "0B")?.let { it[0] } ?: mapKpa
            if (cycle % 20 == 0) {
                pid(output, input, "33")?.let { baroKpa = it[0] }
                coolant = pid(output, input, "05")?.let { it[0] - 40 } ?: coolant
                fuelPct = pid(output, input, "2F")?.let { it[0] * 100 / 255 } ?: fuelPct
            }
            val boost = mapKpa?.let { ((it - baroKpa) / 100f).coerceAtLeast(0f) }
            _state.value = VehicleState(
                speedKmh = speed,
                rpm = rpm,
                boostBar = boost,
                coolantTempC = coolant,
                fuel = fuelPct?.let { it * TANK_LITRES / 100 },
                timestamp = SystemClock.elapsedRealtime()
            )
            cycle++
        }
    }

    private var mapKpa: Int? = null
    private var coolant: Int? = null
    private var fuelPct: Int? = null

    /** Cere un PID din modul 01; octeții de date sau null (nesuportat / fără răspuns). */
    private fun pid(output: OutputStream, input: InputStream, pid: String): IntArray? {
        if (pid in unsupported) return null
        val reply = command(output, input, "01$pid").replace(" ", "").uppercase()
        val marker = "41$pid"
        val at = reply.indexOf(marker)
        if (at < 0) {
            if (reply.contains("NODATA") || reply.contains("?")) unsupported.add(pid)
            return null
        }
        val hex = reply.substring(at + marker.length).takeWhile { it.isLetterOrDigit() }
        if (hex.length < 2) return null
        return IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /** Trimite o comandă și citește până la promptul „>" al adaptorului. */
    private fun command(output: OutputStream, input: InputStream, cmd: String): String {
        output.write("$cmd\r".toByteArray())
        output.flush()
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw java.io.IOException("adaptorul a închis conexiunea")
            if (c == '>'.code) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "Obd"
        const val PORT = 35000
        /** Adresele obișnuite ale adaptoarelor Wi-Fi; 10.0.2.2 = laptopul, din emulator. */
        val DEFAULT_HOSTS = listOf("192.168.0.10", "192.168.4.1")
        const val CONNECT_TIMEOUT_MS = 2_500
        const val READ_TIMEOUT_MS = 3_000
        const val RETRY_MS = 10_000L
        /** Rezervorul E92 diesel. */
        const val TANK_LITRES = 61
    }
}
