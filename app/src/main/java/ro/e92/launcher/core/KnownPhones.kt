package ro.e92.launcher.core

import java.security.MessageDigest

/**
 * Nume fixe pentru telefoanele cunoscute, după adresa Bluetooth.
 *
 * Pe profilul doar-muzică (160) stiva nu trimite numele telefonului: `MX1<adresă>` vine gol.
 * Adresa nu stă în cod în clar, doar amprenta ei (SHA-256 cu prefix); orice alt telefon
 * rămâne fără nume, iar ecranele arată „Phone connected”.
 */
object KnownPhones {

    private const val SALT = "e92-known-phone:"

    private val names = mapOf(
        "528117485c2e1696f26d754eca62a45f6e9bd23a1d1321566344f9b237f0e198" to "iPhone - Costi"
    )

    /** Numele fix pentru adresă (12 caractere hex, cu sau fără `:`), sau "" dacă nu-l știm. */
    fun nameFor(address: String): String {
        val clean = address.filter { it.isLetterOrDigit() }.uppercase()
        if (clean.length != 12) return ""
        return names[hash(clean)] ?: ""
    }

    private fun hash(address: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((SALT + address).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
