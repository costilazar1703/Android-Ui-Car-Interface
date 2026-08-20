package ro.e92.launcher.ui

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.input.LauncherAction

/**
 * Ce poate cere un ecran de la shell. Ține ecranele independente de [HomeActivity]
 * și le face testabile fără să pornească un Activity real.
 */
interface ScreenHost {
    val activity: Activity
    fun push(screen: Screen)
    fun pop(): Boolean
    fun goHome()
    /** Re-citește lista de focus targets a ecranului curent (după ce s-a schimbat conținutul). */
    fun rebuildFocus(keepId: String? = null)
    /** Rutează o acțiune ca și cum ar fi venit de la o tastă fizică. */
    fun dispatch(action: LauncherAction)

    /**
     * Deschide una dintre cele 10 intrări ale meniului principal.
     * Trăiește în shell, nu în [ro.e92.launcher.ui.screens.HomeScreen]: aceleași
     * destinații trebuie atinse și de butoanele hard, fără ca home-ul să fie afișat.
     */
    fun openMenu(action: MainMenuAction)
}

/**
 * Ecran = un View plus graful lui de focus.
 *
 * Nu Fragments: nu avem nevoie de back stack propriu, de saved state sau de
 * ciclul lor de viață; navigarea e dictată de hardkeys. Un Fragment ar adăuga
 * ~un strat de indirecție și tranzacții asincrone pentru zero beneficiu.
 */
abstract class Screen(protected val host: ScreenHost) {

    val context: Context get() = host.activity

    var root: View? = null
        private set

    /** Afișat în bara de sus. */
    abstract val title: String

    protected abstract fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View

    fun createView(inflater: LayoutInflater, container: ViewGroup): View {
        val v = onCreateView(inflater, container)
        root = v
        return v
    }

    /**
     * Scope legat de vizibilitatea ecranului: creat la show, anulat la hide.
     * Nimic nu colectează date pentru un ecran acoperit — regula care ține
     * consumul la zero când ești în Waze.
     */
    private var _scope: CoroutineScope? = null
    private var shown = false

    protected val screenScope: CoroutineScope
        get() = _scope ?: MainScope().also { _scope = it }

    /** Idempotent: push urmat de onStart nu trebuie să dubleze observatorii. */
    fun show() {
        if (shown) return
        shown = true
        _scope = MainScope()
        onShow()
    }

    fun hide() {
        if (!shown) return
        shown = false
        _scope?.cancel()
        _scope = null
        onHide()
    }

    /** Ecranul devine vizibil. Aici se pornesc observatorii de date. */
    protected open fun onShow() = Unit

    /** Ecranul e acoperit sau scos. Aici se opresc observatorii. */
    protected open fun onHide() = Unit

    /** Ecranul e scos definitiv din stivă. */
    open fun onDestroy() = Unit

    open fun focusTargets(): List<FocusTarget> = emptyList()

    /** @return true dacă ecranul a consumat acțiunea (shell-ul nu o mai tratează). */
    open fun onAction(action: LauncherAction): Boolean = false
}
