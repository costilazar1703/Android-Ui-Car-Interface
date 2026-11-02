package ro.e92.launcher.core

import android.content.Context
import android.content.Intent
import android.widget.Toast
import ro.e92.launcher.R

/**
 * Lansarea aplicațiilor terțe atribuite din Setări.
 *
 * Cele două moduri de eșec sunt tratate DISTINCT și spun utilizatorului exact ce
 * să facă. „Neatribuită" înseamnă că nu a ales încă nimic; „dezinstalată"
 * înseamnă că alesese, dar aplicația nu mai e. Un singur mesaj generic pentru
 * ambele ar face butonul să pară doar stricat.
 */
object AppLaunch {

    /** Numele afișat al aplicației, cu package name-ul ca rezervă dacă lipsește. */
    fun label(context: Context, packageName: String): String {
        if (packageName.isEmpty()) return ""
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        packageName.isNotEmpty() &&
            context.packageManager.getLaunchIntentForPackage(packageName) != null

    /**
     * Eticheta pentru coloana de valoare a unui rând de meniu: numele aplicației,
     * sau de ce nu se poate lansa.
     */
    fun statusLabel(context: Context, packageName: String): String = when {
        packageName.isEmpty() -> context.getString(R.string.unbound)
        !isInstalled(context, packageName) -> context.getString(R.string.not_installed_short)
        else -> label(context, packageName)
    }

    /** @return true dacă aplicația chiar a pornit. */
    fun launch(context: Context, packageName: String, menuLabel: String): Boolean {
        if (packageName.isEmpty()) {
            Toast.makeText(
                context,
                "$menuLabel: ${context.getString(R.string.app_not_set)}",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(
                context,
                context.getString(R.string.app_not_installed, packageName),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /**
     * Pe unitatea KSW telefonul nu trece prin Bluetooth-ul Android (adaptorul e
     * oprit): îl ține modulul Goodocom, condus de aplicația vendorului. Doar ea
     * poate împerechea telefonul, deci acolo trimitem; setările Android rămân
     * rezerva pentru alte unități.
     */
    fun openBluetooth(context: Context) {
        if (isInstalled(context, VENDOR_BT_PACKAGE)) launch(context, VENDOR_BT_PACKAGE, "Bluetooth")
        else openSystem(context, android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
    }

    /**
     * Reset Bluetooth: ce făceai de mână (închis aplicațiile, repornit navigația)
     * într-un singur buton, prin serviciul KSW - vezi [KswBluetooth.reset].
     * Aplicația vendorului rulează ca uid system, deci n-o putem opri și reporni;
     * o comandăm din interior.
     */
    fun resetBluetooth(context: Context) {
        Toast.makeText(context, R.string.bt_resetting, Toast.LENGTH_SHORT).show()
        Services.kswBt.reset()
    }

    private const val VENDOR_BT_PACKAGE = KswBluetooth.PACKAGE

    /** Deschide un ecran de setări de sistem, fără să crape dacă nu există. */
    fun openSystem(context: Context, action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
