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

    /** Ținută separat: ecranul de diagnostic afișează extras-urile brute din ea. */
    lateinit var broadcastCan: BroadcastCanDataSource
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
            vehicle = VehicleRepository(scope, buildSources())
            apps = AppRepository(appContext)
            keys = HardKeyRouter(prefs)
            media = MediaHub(appContext)
            net = ConnectivityMonitor(appContext)
            initialized = true
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
        else -> listOf(
            broadcastCan,
            GpsSpeedDataSource(appContext),
            MockCanDataSource(scope)
        )
    }

    /** Reconstruiește lanțul de surse după ce s-a schimbat modul din Setări. */
    fun rebuildCanSources() {
        vehicle.stop()
        vehicle = VehicleRepository(scope, buildSources())
        vehicle.start()
    }
}
