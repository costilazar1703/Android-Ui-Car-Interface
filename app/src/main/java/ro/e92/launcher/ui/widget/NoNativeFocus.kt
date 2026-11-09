package ro.e92.launcher.ui.widget

import android.view.View

/**
 * Selecția o desenează FocusEngine (starea activated, portocaliu). Focusul nativ al
 * Android-ului ar desena în plus un dreptunghi gri peste rândul atins sau peste primul rând
 * focusabil: pe unitate, unde rotița e o tastatură reală, ieșea gri în loc de portocaliu
 * (raportat 2026-11-09). Rândurile rămân clickable la atingere, doar fără focus nativ.
 */
fun noNativeFocus(view: View) {
    view.isFocusable = false
    view.isFocusableInTouchMode = false
    view.defaultFocusHighlightEnabled = false
}
