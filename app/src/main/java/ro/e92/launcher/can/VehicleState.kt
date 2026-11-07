package ro.e92.launcher.can

/**
 * Starea mașinii la un moment dat. Toate câmpurile sunt nullable pentru că o sursă
 * dată poate livra doar un subset (GPS livrează doar viteza, de exemplu).
 * `null` înseamnă "nu știu", nu "zero" — UI-ul trebuie să afișeze diferit cele două.
 */
data class VehicleState(
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val handbrake: Boolean? = null,
    val doorsOpen: Set<Door> = emptySet(),
    val reverseGear: Boolean? = null,
    /**
     * Modul de condus sport. `null` = unitatea nu raporteaza asa ceva, ceea ce
     * e cazul cel mai probabil: butonul Sport de pe E92 circula pe PT-CAN ca
     * mesaj propriu BMW, nu ca PID OBD2 standard, iar daca MCU-ul unitatii nu-l
     * retransmite in Android nu avem de unde sa-l stim.
     */
    val sportMode: Boolean? = null,
    /** Presiunea turbo, relativă (peste cea atmosferică), în bar. */
    val boostBar: Float? = null,
    /** Temperatura uleiului de motor, °C. */
    val oilTempC: Int? = null,
    /** Temperatura lichidului de răcire, °C (OBD PID 05). */
    val coolantTempC: Int? = null,
    /** Tensiunea bateriei la priza OBD, V (ELM327 ATRV). */
    val batteryV: Float? = null,
    /** Combustibil rămas, cum îl raportează unitatea (pe KSW: litri). */
    val fuel: Int? = null,
    /** Autonomia estimată de mașină, km. */
    val rangeKm: Int? = null,
    /** Temperatura exterioară, °C. */
    val outsideTempC: Float? = null,
    /** Consumul mediu arătat de bordul mașinii, l/100 km. */
    val avgConsumption: Float? = null,
    /** Viteza medie arătată de bordul mașinii, km/h. */
    val avgSpeedKmh: Float? = null,
    /** SystemClock.elapsedRealtime() al ultimei actualizări reale. 0 = niciodată. */
    val timestamp: Long = 0L
) {
    val hasData: Boolean get() = timestamp != 0L

    companion object {
        val EMPTY = VehicleState()
    }
}

enum class Door(val label: String) {
    FRONT_LEFT("Front left"),
    FRONT_RIGHT("Front right"),
    REAR_LEFT("Rear left"),
    REAR_RIGHT("Rear right"),
    TRUNK("Trunk"),
    HOOD("Hood")
}
