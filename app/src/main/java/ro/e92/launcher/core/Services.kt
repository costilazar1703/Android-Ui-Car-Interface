package ro.e92.launcher.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ro.e92.launcher.can.BroadcastCanDataSource
import ro.e92.launcher.can.CanDataSource
import ro.e92.launcher.can.GpsSpeedDataSource
import ro.e92.launcher.can.MockCanDataSource
import ro.e92.launcher.can.VehicleRepository
import ro.e92.launcher.input.HardKeyRouter
import ro.e92.launcher.media.MediaHub
import ro.e92.launcher.net.ConnectivityMonitor

/**
 * ServiceLocator manual. Fără Dagger/Hilt pentru v1 — un singleton e suficient,
 * build-ul rămâne rapid și nu adaugă procesare de anotări la fiecare compilare.
 */
object Services {

    lateinit var appContext: Context
        private set

    /** Scope-ul de proces. Trăiește cât aplicația; nu se anulează niciodată. */
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    lateinit var prefs: Prefs
        private set
    lateinit var vehicle: VehicleRepository
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var keys: HardKeyRouter
        private set
    lateinit var media: MediaHub
        private set
    lateinit var net: ConnectivityMonitor
        private set

    /** Telefonul pe unitatea KSW - modulul nu trece prin Bluetooth-ul Android. */
    lateinit var kswBt: KswBluetooth
        private set

    lateinit var trip: TripComputer
        private set

    /** Ținută separat: ecranul de diagnostic afișează extras-urile brute din ea. */
    lateinit var broadcastCan: BroadcastCanDataSource
        private set

    /** Treapta de marșarier, din mesajele MCU; aceeași instanță peste reconstruirea surselor. */
    lateinit var reverse: ro.e92.launcher.can.ReverseSource
        private set

    /** Datele de bord KSW; pornește și oprește cadrele CAN. Null cu alte surse alese. */
    @Volatile
    var kswCar: ro.e92.launcher.can.KswCarDataSource? = null
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            prefs = Prefs(appContext)
            broadcastCan = BroadcastCanDataSource(appContext)
            reverse = ro.e92.launcher.can.ReverseSource(appContext, scope)
            vehicle = VehicleRepository(scope, buildSources())
            apps = AppRepository(appContext)
            keys = HardKeyRouter(prefs)
            media = MediaHub(appContext)
            net = ConnectivityMonitor(appContext)
            kswBt = KswBluetooth(appContext, scope)
            trip = TripComputer(appContext, scope)
            initialized = true
            reverse.watchForScene()
        }
    }

    /**
     * Ordinea = prioritatea. CAN real întâi, GPS ultimul, mock doar dacă e cerut
     * explicit sau dacă suntem pe emulator (unde nimic altceva nu livrează nimic).
     */
    private fun buildSources(): List<CanDataSource> = when (prefs.canMode) {
        Prefs.CAN_MOCK -> listOf(MockCanDataSource(scope))
        Prefs.CAN_BROADCAST -> listOf(broadcastCan)
        Prefs.CAN_GPS -> listOf(GpsSpeedDataSource(appContext))
        else -> listOfNotNull(
            reverse,
            broadcastCan,
            // Datele de bord pe care le arată aplicația Dashboard a unității:
            // autonomie, combustibil, temperatură, frână de mână.
            ro.e92.launcher.can.KswCarDataSource(appContext, scope).also { kswCar = it },
            // Înaintea GPS-ului: viteza din OBD e fără întârziere.
            ro.e92.launcher.can.ObdDataSource(
                scope,
                if (isEmulator()) listOf("10.0.2.2") else listOf("192.168.0.10", "192.168.4.1")
            ),
            GpsSpeedDataSource(appContext),
            // Doar pe emulator. Pe unitate mock-ul era „live" lângă datele reale
            // și umplea golurile cu valori inventate (1047 rpm cu motorul oprit,
            // frână de mână trasă din senin).
            MockCanDataSource(scope).takeIf { isEmulator() }
        )
    }

    private fun isEmulator(): Boolean =
        android.os.Build.HARDWARE.let { it == "ranchu" || it == "goldfish" } ||
            android.os.Build.FINGERPRINT.startsWith("generic")

    /** Reconstruiește lanțul de surse după ce s-a schimbat modul din Setări. */
    fun rebuildCanSources() {
        vehicle.replaceSources(buildSources())
    }
}
