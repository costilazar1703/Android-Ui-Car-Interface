package ro.e92.launcher.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Fotografiile de fundal, decodate o singură dată.
 *
 * Două probleme rezolvate în același loc:
 *
 * 1. **Decodare dublă.** Fade-ul încrucișat din [HomeActivity] folosește două
 *    ImageView-uri. Cu `setImageResource` pe amândouă, aceeași poză se decoda de
 *    două ori la fiecare schimbare: o copie în plus în memorie și câteva
 *    milisecunde furate exact din primele cadre ale animației. Acum ambele
 *    ImageView-uri primesc ACELAȘI [Bitmap].
 *
 * 2. **De ce NU RGB_565**, deși ar înjumătăți memoria. A fost încercat și
 *    măsurat: fundalurile sunt fotografii de noapte, aproape în întregime sub
 *    gri închis, iar 565 are doar ~4 trepte de roșu și albastru în zona aia.
 *    Norii deveneau pete plate verzi și maronii. Un grăunte adăugat ca dithering
 *    readucea forma, dar umplea cerul de puncte colorate. Câștigul era 1,2 MB per
 *    fundal pe o unitate de 2 GB — nu merită stricată exact poza.
 *
 * Cache-ul ține maximum două: fundalul afișat și cel spre care se face fade-ul.
 * Al treilea ar fi memorie ocupată de o poză care nu mai e pe ecran.
 */
class BackgroundCache(private val resources: Resources) {

    private val cache = LruCache<Int, Bitmap>(MAX_ENTRIES)

    fun get(resId: Int): Bitmap? {
        if (resId == 0) return null
        cache.get(resId)?.let { return it }

        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Fundalurile stau în drawable-nodpi, dar setăm explicit: o scalare
            // după densitate ar decoda o poză mai mare decât ecranul.
            inScaled = false
        }
        val bitmap = runCatching { BitmapFactory.decodeResource(resources, resId, opts) }
            .getOrNull() ?: return null
        cache.put(resId, bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()

    private companion object {
        const val MAX_ENTRIES = 2
    }
}
