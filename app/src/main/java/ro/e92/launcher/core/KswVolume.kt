package ro.e92.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Volumul de bază al unității KSW.
 *
 * Unitatea iese spre amplificatorul mașinii, iar volumul real îl dai din
 * butonul radioului și de pe volan. Ce ține unitatea trebuie deci să fie sus:
 * după reset volumul principal KSW (KSW_VOL_VAL_00, 0-40) rămăsese la 3, iar
 * muzica de pe telefon abia se auzea.
 *
 * La pornire: volumul Android de media la maxim (stiva Bluetooth redă prin el)
 * și volumul principal KSW ridicat la nivelul din Settings dacă e sub. La
 * pornire doar ridicăm; din Settings îl pui exact.
 */
object KswVolume {

    private const val TAG = "KswVolume"

    /** EventCenter citește volumul de aici la pornire (getSettingInt → SysVarProvider). */
    private const val MAIN_VOL = "COM.SZCHOICEWAY_KEY_KSW_VOL_VAL_00"
    const val MAX = 40

    /** Treptele din Settings → Launcher → Unit volume. */
    val STEPS = listOf(20, 25, 30, 35, 40)

    private const val EVENT_SERVICE = "com.szchoiceway.eventcenter.EventService"
    private const val EVENT_PACKAGE = "com.szchoiceway.eventcenter"
    private const val DESCRIPTOR = "com.szchoiceway.eventcenter.IEventService"

    /** IEventService.SendVol_KSW(bMute, iMode, iVolType, iVolVal); tipul 1 = volumul principal. */
    private const val TX_SEND_VOL = 115

    fun apply(context: Context) {
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) < max) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            }
        }.onFailure { Log.w(TAG, "volumul media", it) }

        val wanted = Services.prefs.unitVolume
        val current = KswSettings.read(context, MAIN_VOL)?.toIntOrNull() ?: return
        if (current >= wanted) return
        EventLog.log(TAG, "volumul principal KSW era $current - îl ridic la $wanted")
        set(context, wanted)
    }

    /** Volumul principal KSW, așa cum îl vede EventCenter; null fără KSW. */
    fun current(context: Context): Int? = KswSettings.read(context, MAIN_VOL)?.toIntOrNull()

    /** Pune volumul principal exact la [value]: salvat și trimis la MCU acum. */
    fun set(context: Context, value: Int) {
        val v = value.coerceIn(0, MAX)
        // Salvat: EventCenter îl trimite la MCU la fiecare pornire.
        KswSettings.write(context, MAIN_VOL, v.toString())
        // Și acum, fără repornire, prin același apel ca butoanele de volan.
        sendNow(context, v)
    }

    /**
     * Pune volumul principal acum, FĂRĂ să-l salveze: la pornirea următoare unitatea revine la
     * volumul ei. Pentru salut, care îl ridică doar cât vorbește.
     */
    fun setLive(context: Context, value: Int) = sendNow(context, value.coerceIn(0, MAX))

    /** Treptele volumului salutului din Car & unit. */
    val GREETING_STEPS = listOf(10, 15, 20, 25, 30, 35, 40)

    /** Butoanele + și - din Button mapping. */
    fun step(context: Context, delta: Int) {
        val now = current(context) ?: return
        val next = (now + delta).coerceIn(0, MAX)
        if (next != now) set(context, next)
    }

    private fun sendNow(context: Context, value: Int) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                runCatching {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(DESCRIPTOR)
                        data.writeInt(0)        // fără mute
                        data.writeInt(1)        // modul
                        data.writeInt(1)        // volumul principal
                        data.writeInt(value)
                        service?.transact(TX_SEND_VOL, data, reply, 0)
                        reply.readException()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }.onFailure { Log.w(TAG, "SendVol_KSW", it) }
                runCatching { context.unbindService(this) }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val ok = runCatching {
            context.bindService(Intent(EVENT_SERVICE).setPackage(EVENT_PACKAGE), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "nu mă pot lega de EventService", it) }.getOrDefault(false)
        if (!ok) EventLog.log(TAG, "EventService indisponibil - volumul se aplică la următoarea pornire")
    }
}
