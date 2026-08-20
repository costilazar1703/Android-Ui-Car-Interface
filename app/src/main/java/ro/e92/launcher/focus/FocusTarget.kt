package ro.e92.launcher.focus

import android.view.View

/**
 * Un element focusabil dintr-un ecran. Vecinii pe cele 4 direcții de tilt sunt
 * DEFINIȚI MANUAL — pe layout-uri ultrawide, algoritmul nativ de focus Android
 * alege imprevizibil.
 *
 * [onRotate] e escape hatch-ul pentru liste: cât timp un target îl are și el
 * returnează true, rotația e consumată de listă (scroll intern) în loc să mute
 * focusul la următorul card. Exact ca la iDrive, unde intri într-o listă și
 * rotița derulează în ea până ieși cu BACK sau tilt.
 */
class FocusTarget(
    val id: String,
    val view: View,
    val onActivate: (() -> Unit)? = null,
    val onFocus: ((focused: Boolean) -> Unit)? = null,
    val onRotate: ((steps: Int) -> Boolean)? = null,
    val onOption: (() -> Unit)? = null,
    val up: String? = null,
    val down: String? = null,
    val left: String? = null,
    val right: String? = null,
    /** Derulează target-ul în vizor când primește focus (util în ScrollView). */
    val scrollIntoView: Boolean = false
)

enum class FocusDirection { UP, DOWN, LEFT, RIGHT }
