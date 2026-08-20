package ro.e92.launcher.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ro.e92.launcher.core.Services

/**
 * NU lansează Activity-ul — sistemul o face singur prin HOME intent, iar un
 * startActivity de aici ar concura cu el și ar produce un flash de ecran.
 *
 * Rolul lui e strict pre-warm: pornește sursele CAN și scanează lista de aplicații
 * pe un thread de background, ca primul acces la drawer să nu aștepte
 * PackageManager-ul.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        Services.init(context)

        val pending = goAsync()
        Thread {
            try {
                Services.vehicle.start()
                Services.apps.refreshBlocking()
            } finally {
                pending.finish()
            }
        }.apply { name = "e92-prewarm" }.start()
    }
}
