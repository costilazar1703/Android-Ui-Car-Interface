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
