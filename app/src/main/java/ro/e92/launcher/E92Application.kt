package ro.e92.launcher

import android.app.Application
import ro.e92.launcher.core.CrashGuard
import ro.e92.launcher.core.Services
import ro.e92.launcher.core.SettingsBackup
import ro.e92.launcher.remote.DiagServer

class E92Application : Application() {
    override fun onCreate() {
        super.onCreate()
        // Primul: un crash din orice vine dupa trebuie sa fie prins si scris.
        CrashGuard.install(this)
        ro.e92.launcher.core.EventLog.init(this)
        ro.e92.launcher.core.EventLog.log(
            "App",
            "pornire launcher (unitatea pornită de ${android.os.SystemClock.elapsedRealtime() / 1000}s, " +
                "ceas ${if (ro.e92.launcher.core.GpsClock.systemClockValid()) "bun" else "resetat"})"
        )

        // Inainte de Services: componentele citesc setarile o singura data, la
        // constructie, deci restaurarea trebuie sa fie deja pe disc.
        SettingsBackup.restoreIfFreshInstall(this)

        // Doar constructie de obiecte - nimic care sa atinga reteaua.
        // Cold start-ul are buget de 1.5 s pana la home interactiv.
        Services.init(this)
        // Stiva Bluetooth a unității, cât mai devreme - vezi KswBluetooth.ensureBridgeEarly.
        Thread({ runCatching { Services.kswBt.ensureBridgeEarly() } }, "bt-bridge").start()
        SettingsBackup.startAutoBackup(this, Services.prefs)

        // Pe firul lui; doar deschide un socket, nu întârzie home-ul.
        DiagServer.start(this)
    }
}
