package ro.e92.launcher.can

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Simulator de trafic CAN. Se scrie primul și rămâne în build permanent:
 * întreg UI-ul se dezvoltă pe emulator fără mașină, iar în mașină e util
 * ca referință când suspectezi că sursa reală e cea care nu livrează.
 *
 * Generează un ciclu de condus plauzibil: accelerare, croazieră, frânare, oprire.
 * Emite la 20 Hz — intenționat mai des decât afișează UI-ul, ca throttling-ul
 * din [VehicleRepository] să fie exercitat real.
 */
class MockCanDataSource(private val scope: CoroutineScope) : CanDataSource {

    override val id = "mock"
    override val description = "Simulator (fără mașină)"

    private val _state = MutableStateFlow(VehicleState.EMPTY)
    override val vehicleState: StateFlow<VehicleState> = _state.asStateFlow()

    override val isAvailable: Boolean get() = job?.isActive == true

    private var job: Job? = null

    // Stare internă a simulării.
    private var speed = 0f          // km/h
    private var targetSpeed = 0f
    private var gear = 1

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var tick = 0L
            while (isActive) {
                tick++
                stepSimulation(tick)
                _state.value = VehicleState(
                    speedKmh = speed.roundToInt(),
                    rpm = computeRpm(),
                    handbrake = speed < 1f && tick % 400 < 60,
                    doorsOpen = if (speed < 1f && tick % 900 < 40) setOf(Door.FRONT_LEFT) else emptySet(),
                    reverseGear = false,
                    timestamp = SystemClock.elapsedRealtime()
                )
                delay(TICK_MS)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _state.value = VehicleState.EMPTY
    }

    private fun stepSimulation(tick: Long) {
        // La fiecare ~6 s alege o nouă viteză țintă.
        if (tick % (6_000L / TICK_MS) == 0L) {
            targetSpeed = when (Random.nextInt(10)) {
                0, 1 -> 0f
                2, 3, 4 -> Random.nextInt(30, 60).toFloat()
                5, 6, 7 -> Random.nextInt(60, 100).toFloat()
                else -> Random.nextInt(100, 140).toFloat()
            }
        }
        val delta = targetSpeed - speed
        // Accelerare mai lentă decât frânarea, ca la o mașină reală.
        val rate = if (delta > 0) 0.35f else 0.9f
        speed += delta.coerceIn(-rate * 3f, rate * 3f) * 0.35f
        if (abs(delta) < 0.3f) speed = targetSpeed
        speed = speed.coerceIn(0f, 160f)

        gear = when {
            speed < 12f -> 1
            speed < 28f -> 2
            speed < 48f -> 3
            speed < 75f -> 4
            speed < 105f -> 5
            else -> 6
        }
    }

    private fun computeRpm(): Int {
        if (speed < 1f) return 780 + Random.nextInt(-30, 30) // ralanti
        val ratio = when (gear) {
            1 -> 130f
            2 -> 75f
            3 -> 52f
            4 -> 38f
            5 -> 30f
            else -> 25f
        }
        return (speed * ratio).roundToInt().coerceIn(700, 7000)
    }

    private companion object {
        const val TICK_MS = 50L // 20 Hz
    }
}
