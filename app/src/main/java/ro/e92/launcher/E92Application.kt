package ro.e92.launcher

import android.app.Application
import ro.e92.launcher.core.Services

class E92Application : Application() {
    override fun onCreate() {
        super.onCreate()
        // Doar construcție de obiecte — nimic care să atingă discul sau rețeaua.
        // Cold start-ul are buget de 1.5 s până la home interactiv.
        Services.init(this)
    }
}
