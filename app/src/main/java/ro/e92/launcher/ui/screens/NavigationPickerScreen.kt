package ro.e92.launcher.ui.screens

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ItemSplitNavBinding
import ro.e92.launcher.databinding.ScreenNavPickerBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.nav.NavLauncher
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost

/**
 * Pop-up-ul de navigație, desenat peste meniu.
 *
 * De ce nu AlertDialog: dialogul își deschide fereastra lui, iar tastele fizice
 * ale controller-ului iDrive ajung acolo — nu la `HomeActivity.onKeyDown`, adică
 * nu la [ro.e92.launcher.focus.FocusEngine]. Rotița ar înceta să funcționeze
 * exact în momentul în care trebuie să alegi ceva. Aici e un [Screen] normal, cu
 * `isOverlay = true`: meniul rămâne vizibil dedesubt, dar tot lanțul de input
 * rămâne al nostru.
 *
 * Butonul hard NAV NU trece pe aici: în mers vrei o singură apăsare, deci
 * [ro.e92.launcher.ui.HomeActivity] lansează direct aplicația configurată.
 * Pop-up-ul e pentru intrarea din meniu, unde ai timp să alegi.
 */
class NavigationPickerScreen(host: ScreenHost) : Screen(host) {

    override val title: String get() = "NAVIGATION"

    override val isOverlay: Boolean get() = true

    private lateinit var binding: ScreenNavPickerBinding

    private class Option(
        val id: String,
        val view: View,
        val launch: () -> Unit
    )

    private val options = ArrayList<Option>(2)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        ScreenNavPickerBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        binding.panelTitle.text = context.getString(R.string.menu_navigation)
        binding.panelHint.text = context.getString(R.string.nav_pick_hint)

        // Fără rebuildFocus aici: ScreenStack cere focusTargets() imediat după
        // show(), iar opțiunile există deja la momentul acela.
        buildOptions()
    }

    override fun focusTargets(): List<FocusTarget> = options.mapIndexed { index, option ->
        FocusTarget(
            id = option.id,
            view = option.view,
            onActivate = {
                // Închidem întâi pop-up-ul: la revenirea din Waze utilizatorul
                // trebuie să găsească meniul, nu selectorul încă deschis.
                host.pop()
                option.launch()
            },
            up = options.getOrNull(index - 1)?.id,
            down = options.getOrNull(index + 1)?.id
        )
    }

    private fun buildOptions() {
        binding.panelOptions.removeAllViews()
        options.clear()

        val inflater = LayoutInflater.from(context)

        addOption(inflater, OPT_WAZE, wazeLabel()) {
            NavLauncher.launch(context, Services.prefs)
        }

        addOption(inflater, OPT_BROWSER, context.getString(R.string.nav_pick_chrome)) {
            openBrowserMaps()
        }
    }

    private fun addOption(
        inflater: LayoutInflater,
        id: String,
        label: String,
        launch: () -> Unit
    ) {
        val row = ItemSplitNavBinding.inflate(inflater, binding.panelOptions, false)
        row.navTitle.text = label
        row.navTitle.setOnClickListener {
            host.pop()
            launch()
        }
        binding.panelOptions.addView(
            row.root,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.resources.getDimensionPixelSize(R.dimen.detail_row_height)
            )
        )
        options.add(Option(id, row.root, launch))
    }

    /**
     * Eticheta primei opțiuni e numele REAL al aplicației configurate, nu textul
     * fix „Waze": dacă utilizatorul a pus altceva din Setări, aici trebuie să
     * scrie ce se va deschide efectiv.
     */
    private fun wazeLabel(): String =
        NavLauncher.installedLabel(context, Services.prefs)
            ?: context.getString(R.string.nav_pick_waze)

    private fun openBrowserMaps() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MAPS_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val browser = Services.prefs.browserPackage
        if (context.packageManager.getLaunchIntentForPackage(browser) != null) {
            intent.setPackage(browser)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            intent.setPackage(null)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.app_not_installed, browser),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private companion object {
        const val OPT_WAZE = "nav_opt_app"
        const val OPT_BROWSER = "nav_opt_browser"
        const val MAPS_URL = "https://www.google.com/maps"
    }
}
