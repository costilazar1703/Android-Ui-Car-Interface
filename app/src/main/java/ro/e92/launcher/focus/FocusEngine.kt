package ro.e92.launcher.focus

import android.graphics.Rect
import android.view.View

/**
 * Graful de focus al ecranului curent.
 *
 * De ce nu focus-ul nativ Android: `nextFocusForward` presupune un layout
 * rezonabil și un `FOCUS_FORWARD` care urmează ordinea de desenare. Pe un ecran
 * de 1280x480 cu carduri de lățimi diferite alegerea lui e greu de prezis și
 * imposibil de reprodus identic după un `BACK`. Aici ordinea e o listă explicită.
 *
 * Feedback-ul vizual se face prin `View.isActivated` + selector în drawable
 * (state_activated), nu prin ripple: niciun cost de animație, aspect BMW.
 */
class FocusEngine {

    private var targets: List<FocusTarget> = emptyList()
    private var index = -1

    /** Rect reutilizat — requestRectangleOnScreen se apelează la fiecare mutare. */
    private val scrollRect = Rect()

    var onFocusChanged: ((FocusTarget?) -> Unit)? = null

    val focused: FocusTarget? get() = targets.getOrNull(index)
    val focusedId: String? get() = focused?.id
    val isEmpty: Boolean get() = targets.isEmpty()

    /**
     * @param restoreId dacă e non-null și există, focusul revine acolo
     *                  (folosit la revenirea din BACK).
     */
    fun setTargets(newTargets: List<FocusTarget>, restoreId: String? = null) {
        clearVisualState()
        targets = newTargets
        index = -1
        if (newTargets.isEmpty()) {
            onFocusChanged?.invoke(null)
            return
        }
        val restored = restoreId?.let { id -> newTargets.indexOfFirst { it.id == id } } ?: -1
        applyFocus(if (restored >= 0) restored else 0)
    }

    fun clear() {
        clearVisualState()
        targets = emptyList()
        index = -1
        onFocusChanged?.invoke(null)
    }

    fun focusById(id: String): Boolean {
        val i = targets.indexOfFirst { it.id == id }
        if (i < 0) return false
        applyFocus(i)
        return true
    }

    /** Rotiță CW. [steps] vine din [ro.e92.launcher.input.RotaryAccelerator]. */
    fun next(steps: Int = 1) = rotate(steps)

    /** Rotiță CCW. */
    fun prev(steps: Int = 1) = rotate(-steps)

    private fun rotate(delta: Int) {
        if (targets.isEmpty() || delta == 0) return

        // Listele consumă rotația cât timp mai au unde derula.
        val current = focused
        if (current?.onRotate != null && current.onRotate.invoke(delta)) return

        // Wrap-around: comportament OEM, lista nu se "lipește" la capete.
        val size = targets.size
        var next = (index + delta) % size
        if (next < 0) next += size
        applyFocus(next)
    }

    /** @return true dacă direcția a mutat focusul (altfel ecranul poate decide altceva). */
    fun move(direction: FocusDirection): Boolean {
        val current = focused ?: return false
        val targetId = when (direction) {
            FocusDirection.UP -> current.up
            FocusDirection.DOWN -> current.down
            FocusDirection.LEFT -> current.left
            FocusDirection.RIGHT -> current.right
        }
        if (targetId != null) return focusById(targetId)

        // Fără vecin explicit, stânga/dreapta se comportă ca rotița — natural pe
        // un layout de carduri pe orizontală. Sus/jos nu inventează nimic.
        return when (direction) {
            FocusDirection.LEFT -> { rotate(-1); true }
            FocusDirection.RIGHT -> { rotate(1); true }
            else -> false
        }
    }

    fun activate() {
        focused?.onActivate?.invoke()
    }

    /** @return true dacă target-ul curent are meniu contextual (butonul OPTION). */
    fun option(): Boolean {
        val handler = focused?.onOption ?: return false
        handler.invoke()
        return true
    }

    private fun applyFocus(newIndex: Int) {
        if (newIndex == index) return
        targets.getOrNull(index)?.let { old ->
            old.view.isActivated = false
            old.onFocus?.invoke(false)
        }
        index = newIndex
        val target = targets.getOrNull(newIndex)
        if (target != null) {
            target.view.isActivated = true
            target.onFocus?.invoke(true)
            if (target.scrollIntoView) scrollIntoView(target.view)
        }
        onFocusChanged?.invoke(target)
    }

    private fun scrollIntoView(view: View) {
        scrollRect.set(0, 0, view.width, view.height)
        // immediate=true: fără animație de scroll, e mai ieftin și mai "mecanic".
        view.requestRectangleOnScreen(scrollRect, true)
    }

    private fun clearVisualState() {
        targets.getOrNull(index)?.let {
            it.view.isActivated = false
            it.onFocus?.invoke(false)
        }
    }
}
