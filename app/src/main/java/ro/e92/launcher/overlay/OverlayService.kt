package ro.e92.launcher.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.can.ModeEvent
import ro.e92.launcher.can.ModeScene
import ro.e92.launcher.core.EventLog
import ro.e92.launcher.core.KswVolume
import ro.e92.launcher.core.Services
import ro.e92.launcher.ui.screens.ModeCardView
import ro.e92.launcher.ui.widget.VolumePopupView

/**
 * Cardurile de treaptă și bara de volum PESTE alte aplicații (CarPlay, navigație), cerut
 * 2026-11-20. Cât launcher-ul e în față le desenează el (HomeActivity); când e în spate, le
 * desenează serviciul ăsta într-o fereastră de tip overlay, care nu ia focusul și nu prinde
 * atingeri, deci CarPlay de dedesubt rămâne complet utilizabil.
 *
 * Treapta și volumul vin din CAN (0x1D2 și 0x1D6, citite de [ro.e92.launcher.can.ReverseSource]),
 * deci serviciul doar ascultă aceleași fluxuri ca launcher-ul.
 *
 * Rulează ca serviciu în prim-plan (cu o notificare discretă), ca Android să nu omoare procesul
 * launcher-ului cât e altă aplicație în față. Are nevoie de permisiunea „afișare peste alte
 * aplicații" (SYSTEM_ALERT_WINDOW); pe unitate se dă o dată prin adb:
 * `adb shell appops set ro.e92.launcher SYSTEM_ALERT_WINDOW allow`. Fără ea serviciul doar scrie
 * în jurnal și nu desenează nimic.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val windows by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var card: View? = null
    private var volume: VolumePopupView? = null
    private var warnedNoPermission = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        scope.launch {
            Services.reverse.modeEvents.collect { event -> if (!launcherInFront()) showCard(event) }
        }
        scope.launch {
            KswVolume.changes.collect { level -> if (!launcherInFront()) showVolume(level) }
        }
        EventLog.log(TAG, "pornit" + if (canDraw()) "" else " (fără permisiunea de overlay)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        card?.let { runCatching { windows.removeView(it) } }
        volume?.let { runCatching { windows.removeView(it) } }
        super.onDestroy()
    }

    private fun launcherInFront() = Services.reverse.launcherInFront

    private fun canDraw(): Boolean = Settings.canDrawOverlays(this)

    private fun ready(): Boolean {
        if (canDraw()) return true
        if (!warnedNoPermission) {
            warnedNoPermission = true
            EventLog.log(TAG, "lipsește permisiunea de overlay: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
        }
        return false
    }

    /** Fereastra peste tot ecranul, fără focus și fără atingeri: CarPlay primește tot ce apeși. */
    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    /** Cardul de treaptă, jos pe mijloc, ca pe Dashboard. Doar cardurile mici, nu scena Drift. */
    private fun showCard(event: ModeEvent) {
        if (event.scene == ModeScene.DRIFT || event.scene == ModeScene.NORMAL) return
        if (!ready()) return
        card?.let { runCatching { windows.removeView(it) } }
        val view = ModeCardView(this, event, getString(titleRes(event.scene)), null) { removeCard() }
        card = view
        runCatching { windows.addView(view, params()) }
            .onSuccess { view.play() }
            .onFailure { EventLog.log(TAG, "cardul nu s-a putut afișa: ${it.message}"); card = null }
    }

    private fun removeCard() {
        card?.let { runCatching { windows.removeView(it) } }
        card = null
    }

    /** Bara de volum; stă atașată și se ascunde singură după 2 s fără apăsări. */
    private fun showVolume(level: Int) {
        if (!ready()) return
        val view = volume ?: VolumePopupView(this).also { v ->
            runCatching { windows.addView(v, params()) }
                .onSuccess { volume = v }
                .onFailure { EventLog.log(TAG, "bara de volum nu s-a putut afișa: ${it.message}") }
        }
        if (volume != null) view.show(level, KswVolume.MAX)
    }

    private fun titleRes(scene: ModeScene) = when (scene) {
        ModeScene.PARK -> R.string.mode_park
        ModeScene.NEUTRAL -> R.string.mode_neutral
        ModeScene.SPORT -> R.string.mode_sport
        ModeScene.MANUAL -> R.string.mode_manual
        else -> R.string.mode_drive
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "E92 overlay", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("E92")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Overlay"
        private const val CHANNEL = "e92_overlay"
        private const val NOTIFICATION_ID = 92

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { EventLog.log(TAG, "nu pornește: ${it.message}") }
        }
    }
}
