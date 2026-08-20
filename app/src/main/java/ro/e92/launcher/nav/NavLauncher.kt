package ro.e92.launcher.nav

import android.content.Context
import android.content.Intent
import android.widget.Toast
import ro.e92.launcher.R
import ro.e92.launcher.core.Prefs

/**
 * Aplicația de navigație e configurabilă (Waze e doar default-ul: se descurcă bine
 * cu ecranele late). Launcher-ul nu implementează navigație proprie — non-goal explicit.
 */
object NavLauncher {

    fun installedLabel(context: Context, prefs: Prefs): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(prefs.navPackage, 0)).toString()
    }.getOrNull()

    fun launch(context: Context, prefs: Prefs): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(prefs.navPackage)
        if (intent == null) {
            Toast.makeText(context, R.string.nav_not_installed, Toast.LENGTH_SHORT).show()
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        return true
    }
}
