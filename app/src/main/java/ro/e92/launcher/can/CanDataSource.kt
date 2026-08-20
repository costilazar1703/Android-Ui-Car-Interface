package ro.e92.launcher.can

import kotlinx.coroutines.flow.StateFlow

/**
 * Transportul datelor de mașină e necunoscut până la finalizarea Fazei 0.
 * Tot restul aplicației vorbește doar cu interfața asta — când se descoperă
 * protocolul real, se schimbă o singură implementare, nu UI-ul.
 */
interface CanDataSource {

    /** Identificator scurt, afișat în ecranul de diagnostic. */
    val id: String

    /** Descriere pentru Setări ("Broadcast vendor: com.foo.CAN_DATA"). */
    val description: String

    val vehicleState: StateFlow<VehicleState>

    /** True dacă sursa e pornită și a produs cel puțin un frame valid. */
    val isAvailable: Boolean

    fun start()

    fun stop()

    companion object {
        /** Dacă un frame e mai vechi de atât, sursa e considerată moartă. */
        const val STALE_AFTER_MS = 2_000L
    }
}
