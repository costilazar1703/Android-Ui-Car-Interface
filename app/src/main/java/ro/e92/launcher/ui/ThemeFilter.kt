package ro.e92.launcher.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import ro.e92.launcher.core.Prefs

/**
 * Tema interfeței, aplicată ca filtru de culoare pe toată fereastra launcher-ului.
 *
 * Totul e desenat în aceeași fereastră (meniuri, fotografii, ceasuri, marșarier, Drift),
 * deci un singur filtru pe decorView le prinde pe toate, fără să atingem fiecare
 * resursă portocalie în parte. Albul, griul și negrul trec neschimbate prin ambele
 * matrice (fiecare rând însumează 1), deci textul și fundalurile rămân curate.
 */
object ThemeFilter {

    fun apply(root: View, theme: Int) {
        val matrix = when (theme) {
            Prefs.THEME_RED -> RED
            Prefs.THEME_MONO -> MONO
            else -> null
        }
        if (matrix == null) {
            root.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix)) }
        root.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    /**
     * Portocaliul devine roșu: verdele și albastrul scad doar cât roșul le depășește.
     * #F04A12 (accentul) iese ~#F00000, tonurile închise ale fotografiilor ies vișinii.
     */
    private val RED = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        -0.5f, 1.5f, 0f, 0f, 0f,
        -0.3f, 0f, 1.3f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    /**
     * Alb-negru. Roșul are pondere mare ca accentele portocalii să iasă gri deschis
     * (focusul se vede clar), nu gri tern ca la luminanța standard.
     */
    private val MONO = floatArrayOf(
        0.6f, 0.35f, 0.05f, 0f, 0f,
        0.6f, 0.35f, 0.05f, 0f, 0f,
        0.6f, 0.35f, 0.05f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
}
