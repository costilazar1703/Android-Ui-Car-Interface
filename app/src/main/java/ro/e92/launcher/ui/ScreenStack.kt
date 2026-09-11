package ro.e92.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ro.e92.launcher.focus.FocusEngine

/**
 * Stiva de ecrane.
 *
 * Detaliul care face diferența pentru senzația OEM: la BACK se restaurează
 * ELEMENTUL FOCUSAT, nu doar ecranul. În mașină, un BMW te lasă exact unde erai.
 *
 * View-urile ecranelor acoperite rămân inflate (doar GONE): stiva are adâncime 2-3,
 * iar re-inflatarea la fiecare BACK ar fi cel mai vizibil stutter din aplicație.
 */
class ScreenStack(
    private val container: ViewGroup,
    private val focus: FocusEngine,
    private val inflater: LayoutInflater,
    private val animationsEnabled: () -> Boolean
) {

    private class Entry(val screen: Screen) {
        var savedFocusId: String? = null
    }

    private val stack = ArrayList<Entry>(4)

    var onTitleChanged: ((String) -> Unit)? = null

    /** Anuntat cu drawable-ul cerut de ecranul ajuns deasupra. */
    var onBackgroundChanged: ((Int) -> Unit)? = null

    val current: Screen? get() = stack.lastOrNull()?.screen
    val depth: Int get() = stack.size

    /** Golește stiva și pune un ecran nou ca rădăcină (folosit de MENU / Home). */
    fun setRoot(screen: Screen) {
        while (stack.isNotEmpty()) destroyTop()
        push(screen)
    }

    fun push(screen: Screen) {
        stack.lastOrNull()?.let { top ->
            top.savedFocusId = focus.focusedId
            // Observatorii se opresc în ambele cazuri — nimic nu colectează date
            // pentru un ecran cu care nu se mai poate interacționa. Doar view-ul
            // rămâne pe ecran sub un pop-up, ca să se vadă peste ce s-a deschis.
            top.screen.hide()
            if (!screen.isOverlay) top.screen.root?.visibility = View.GONE
        }

        val entry = Entry(screen)
        val view = screen.createView(inflater, container)
        container.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        stack.add(entry)

        screen.show()
        focus.setTargets(screen.focusTargets())
        onTitleChanged?.invoke(screen.title)
        onBackgroundChanged?.invoke(screen.backgroundRes)
        animateIn(view)
    }

    /** @return false dacă era deja pe rădăcină (nu mai avem unde ieși). */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        destroyTop()

        val top = stack.last()
        top.screen.root?.visibility = View.VISIBLE
        top.screen.show()
        focus.setTargets(top.screen.focusTargets(), top.savedFocusId)
        onTitleChanged?.invoke(top.screen.title)
        onBackgroundChanged?.invoke(top.screen.backgroundRes)
        return true
    }

    /** Reconstruiește graful de focus al ecranului curent după o schimbare de conținut. */
    fun rebuildFocus(keepId: String? = null) {
        val top = stack.lastOrNull() ?: return
        focus.setTargets(top.screen.focusTargets(), keepId ?: focus.focusedId)
    }

    fun onActivityStop() {
        stack.lastOrNull()?.screen?.hide()
    }

    fun onActivityStart() {
        val top = stack.lastOrNull() ?: return
        top.screen.show()
        focus.setTargets(top.screen.focusTargets(), focus.focusedId)
        onTitleChanged?.invoke(top.screen.title)
        onBackgroundChanged?.invoke(top.screen.backgroundRes)
    }

    fun destroyAll() {
        while (stack.isNotEmpty()) destroyTop()
        focus.clear()
    }

    private fun destroyTop() {
        val entry = stack.removeAt(stack.size - 1)
        entry.screen.hide()
        entry.screen.onDestroy()
        entry.screen.root?.let { container.removeView(it) }
    }

    /**
     * Tranziție unică și scurtă: fade de 160 ms. Fără shared elements, fără
     * animații simultane pe mai multe view-uri — pe 2 GB, orice altceva costă frame-uri.
     */
    private fun animateIn(view: View) {
        if (!animationsEnabled()) {
            view.alpha = 1f
            return
        }
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setDuration(ANIM_MS)
            .withEndAction { view.alpha = 1f }
            .start()
    }

    private companion object {
        const val ANIM_MS = 160L
    }
}
