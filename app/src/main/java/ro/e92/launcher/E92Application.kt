package ro.e92.launcher

import android.app.Application
import ro.e92.launcher.core.CrashGuard
import ro.e92.launcher.core.Services
import ro.e92.launcher.core.SettingsBackup

class E92Application : Application() {
    override fun onCreate() {
        super.onCreate()
        // Primul: un crash din orice vine dupa trebuie sa fie prins si scris.
        CrashGuard.install(this)

        // Inainte de Services: componentele citesc setarile o singura data, la
        // constructie, deci restaurarea trebuie sa fie deja pe disc.
        SettingsBackup.restoreIfFreshInstall(this)

        // Doar constructie de obiecte - nimic care sa atinga reteaua.
        // Cold start-ul are buget de 1.5 s pana la home interactiv.
        Services.init(this)
        SettingsBackup.startAutoBackup(this, Services.prefs)
    }
}
