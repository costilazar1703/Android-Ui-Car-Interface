package ro.e92.launcher.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Coperta pentru muzica de pe telefon care trece prin modulul KSW.
 *
 * Modulul trimite doar titlul piesei, fără copertă (AVRCP-ul lui nu are
 * imagini). O căutăm, ca CarPlay, în catalogul public iTunes după titlu -
 * merge doar cu internet (hotspot-ul telefonului) și poate greși la titluri
 * foarte comune; fără rezultat, rămâne iconița implicită, ca până acum.
 */
object ArtworkLookup {

    private const val TAG = "ArtworkLookup"
    private const val CACHE_ITEMS = 40
    private const val ART_PX = 600
    private const val TIMEOUT_MS = 5_000

    private val cache = LruCache<String, Bitmap>(CACHE_ITEMS)
    /** Titluri pentru care n-am găsit nimic - nu le căutăm iar la fiecare refresh. */
    private val misses = HashSet<String>()

    /** Artistul din același rezultat - modulul KSW nu-l trimite deloc. */
    private val artists = HashMap<String, String>()

    fun cached(title: String): Bitmap? = cache.get(key(title))

    fun artist(title: String): String? = artists[key(title)]

    suspend fun find(title: String): Bitmap? {
        val k = key(title)
        if (k.isEmpty()) return null
        cache.get(k)?.let { return it }
        if (k in misses) return null
        val bmp = withContext(Dispatchers.IO) { runCatching { download(title) }.getOrNull() }
        if (bmp != null) cache.put(k, bmp) else misses.add(k)
        return bmp
    }

    private fun download(title: String): Bitmap? {
        val term = URLEncoder.encode(title, "UTF-8")
        val json = get("https://itunes.apple.com/search?media=music&entity=song&limit=1&term=$term")
            ?.toString(Charsets.UTF_8) ?: return null
        val results = JSONObject(json).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val hit = results.getJSONObject(0)
        hit.optString("artistName").takeIf { it.isNotEmpty() }?.let { artists[key(title)] = it }
        val art = hit.optString("artworkUrl100").takeIf { it.isNotEmpty() } ?: return null
        // Aceeași imagine, la rezoluția cât ecranul Media: 100 px ar ieși pixelat.
        val big = art.replace("100x100bb", "${ART_PX}x${ART_PX}bb")
        val bytes = get(big) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun get(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.i(TAG, "copertă indisponibilă: ${e.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun key(title: String) = title.trim().lowercase()

}
