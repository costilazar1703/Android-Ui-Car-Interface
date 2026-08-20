package ro.e92.launcher.media

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ro.e92.launcher.core.Services
import ro.e92.launcher.nav.NavNotificationRelay

/**
 * Serviciul are două roluri, ambele pe aceeași permisiune acordată o dată:
 *  1. deblochează [android.media.session.MediaSessionManager.getActiveSessions];
 *  2. citește notificarea aplicației de navigație pentru cardul de next-turn.
 *
 * Nu afișează nimic și nu modifică notificările.
 */
class E92NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Services.init(applicationContext)
        Services.media.refreshSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        Services.init(applicationContext)
        if (sbn.packageName != Services.prefs.navPackage) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        NavNotificationRelay.publish(title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        Services.init(applicationContext)
        if (sbn.packageName == Services.prefs.navPackage) {
            NavNotificationRelay.clear()
        }
    }
}
