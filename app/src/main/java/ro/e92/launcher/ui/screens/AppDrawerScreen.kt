package ro.e92.launcher.ui.screens

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.e92.launcher.core.AppEntry
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ScreenAppDrawerBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost

/**
 * Grid orizontal paginat, navigabil din rotiță.
 *
 * Poate fi folosit și ca selector (pentru "alege aplicația de navigație"): în modul
 * [onPick] nu lansează aplicația, ci o returnează și iese.
 */
class AppDrawerScreen(
    host: ScreenHost,
    private val onPick: ((AppEntry) -> Unit)? = null
) : Screen(host) {

    override val title: String get() = if (onPick != null) "SELECT APP" else "APPS"

    private lateinit var binding: ScreenAppDrawerBinding
    private lateinit var adapter: AppGridAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup) =
        ScreenAppDrawerBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        adapter = AppGridAdapter(Services.apps) { entry -> activate(entry) }
        adapter.onSelectionChanged = { entry -> updateHint(entry) }

        binding.grid.apply {
            layoutManager = GridLayoutManager(context, ROWS, RecyclerView.HORIZONTAL, false)
            setHasFixedSize(true)
            itemAnimator = null // niciun animator: highlight-ul trebuie să fie instant
            adapter = this@AppDrawerScreen.adapter
        }

        screenScope.launch {
            Services.apps.apps.collect { list ->
                if (list.isEmpty()) {
                    // Scanarea e scumpă; o facem doar dacă nimeni n-a făcut-o încă.
                    withContext(Dispatchers.IO) { Services.apps.refreshBlocking() }
                } else {
                    // Iconițele se citesc de pe disc — niciodată în onBindViewHolder.
                    withContext(Dispatchers.IO) { Services.apps.preloadIconsBlocking(list) }
                    adapter.submit(list)
                }
            }
        }
    }

    override fun focusTargets(): List<FocusTarget> = listOf(
        FocusTarget(
            id = "grid",
            view = binding.grid,
            onActivate = { adapter.activateSelected() },
            onRotate = { steps ->
                val moved = adapter.moveSelection(steps)
                if (moved) binding.grid.scrollToPosition(adapter.selectedIndex)
                moved
            }
        )
    )

    private fun activate(entry: AppEntry) {
        val picker = onPick
        if (picker != null) {
            // Întâi pop, apoi callback: ecranul care a cerut selecția trebuie să
            // fie deja cel curent când își reconstruiește conținutul și focusul.
            host.pop()
            picker(entry)
            return
        }
        val intent: Intent = Services.apps.launchIntent(entry)
        runCatching { context.startActivity(intent) }
    }

    private fun updateHint(entry: AppEntry?) {
        binding.txtHint.text = if (entry == null) {
            ""
        } else {
            "${entry.label} · ${adapter.selectedIndex + 1} / ${adapter.itemCount}"
        }
    }

    private companion object {
        /** Două rânduri: pe 480 px pe verticală, trei ar face iconițele ilizibile. */
        const val ROWS = 2
    }
}
