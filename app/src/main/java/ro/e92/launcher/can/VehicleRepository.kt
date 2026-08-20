package ro.e92.launcher.can

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Fuzionează toate sursele disponibile într-o singură stare și o publică
 * cu rată limitată.
 *
 * Reguli:
 *  - sursele sunt încercate în ordinea din listă (prioritate descrescătoare);
 *  - per câmp, câștigă prima sursă vie care are o valoare non-null
 *    (așa cade viteza automat pe GPS când CAN nu o livrează);
 *  - o sursă care n-a mai emis de [CanDataSource.STALE_AFTER_MS] e ignorată.
 *
 * Throttling-ul e AICI, nu în View: datele CAN pot veni la 50+ Hz, dashboard-ul
 * are nevoie de 10 Hz. Dacă lași View-ul să filtreze, tot plătești invalidate-urile.
 */
@OptIn(FlowPreview::class)
class VehicleRepository(
    private val scope: CoroutineScope,
    val sources: List<CanDataSource>
) {

    private val _state = MutableStateFlow(VehicleState.EMPTY)
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    /** Id-ul sursei care livrează efectiv viteza acum. Afișat în Diagnostics. */
    private val _activeSourceId = MutableStateFlow(NO_SOURCE)
    val activeSourceId: StateFlow<String> = _activeSourceId.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        sources.forEach { it.start() }
        job = scope.launch {
            combine(sources.map { it.vehicleState }) { states -> mergeByPriority(states) }
                .sample(SAMPLE_INTERVAL_MS)
                .collect { _state.value = it }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        sources.forEach { it.stop() }
        _state.value = VehicleState.EMPTY
        _activeSourceId.value = NO_SOURCE
    }

    private fun mergeByPriority(states: Array<VehicleState>): VehicleState {
        val now = SystemClock.elapsedRealtime()

        var speed: Int? = null
        var rpm: Int? = null
        var handbrake: Boolean? = null
        var reverse: Boolean? = null
        var doors: Set<Door> = emptySet()
        var doorsSet = false
        var newest = 0L
        var speedFrom = NO_SOURCE

        for (i in states.indices) {
            val s = states[i]
            if (!s.hasData || now - s.timestamp > CanDataSource.STALE_AFTER_MS) continue

            if (speed == null && s.speedKmh != null) {
                speed = s.speedKmh
                speedFrom = sources[i].id
            }
            if (rpm == null) rpm = s.rpm
            if (handbrake == null) handbrake = s.handbrake
            if (reverse == null) reverse = s.reverseGear
            if (!doorsSet && s.doorsOpen.isNotEmpty()) {
                doors = s.doorsOpen
                doorsSet = true
            }
            if (s.timestamp > newest) newest = s.timestamp
        }

        _activeSourceId.value = speedFrom

        return VehicleState(
            speedKmh = speed,
            rpm = rpm,
            handbrake = handbrake,
            doorsOpen = doors,
            reverseGear = reverse,
            timestamp = newest
        )
    }

    companion object {
        const val NO_SOURCE = "none"
        /** 10 Hz — limita din bugetul de performanță pentru afișajul numeric. */
        const val SAMPLE_INTERVAL_MS = 100L
    }
}
