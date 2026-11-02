package ro.e92.launcher.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Actualizarea launcher-ului fără laptop la volan și fără Chrome.
 *
 * Laptopul servește ultimul APK pe hotspot (`python -m http.server` din
 * folderul de build); butonul din Setări îl descarcă și îl compară cu APK-ul
 * instalat. versionCode rămâne 1 la fiecare build, deci comparăm conținutul
 * (SHA-256), nu numărul de versiune: același fișier = deja la zi.
 *
 * Instalarea propriu-zisă e a sistemului - fără root nu există instalare
 * tăcută, deci pe ecran apare „Install".
 */
object SelfUpdate {

    enum class Result { INSTALLING, UP_TO_DATE, NEEDS_PERMISSION, NO_SERVER, FAILED }

    suspend fun check(context: Context, url: String): Result = withContext(Dispatchers.IO) {
        val apk = File(updatesDir(context), "update.apk")
        if (!download(url, apk)) return@withContext Result.NO_SERVER
        val installed = File(context.applicationInfo.sourceDir)
        if (sha256(apk) == sha256(installed)) return@withContext Result.UP_TO_DATE
        withContext(Dispatchers.Main) { install(context, apk) }
    }

    /** Deschide instalatorul de sistem pe [apk]; comun cu DiagServer. */
    fun install(context: Context, apk: File): Result {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return Result.NEEDS_PERMISSION
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", apk)
        return runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result.INSTALLING
        }.getOrDefault(Result.FAILED)
    }

    fun updatesDir(context: Context): File =
        File(context.externalCacheDir ?: context.cacheDir, "updates").also { it.mkdirs() }

    private fun download(url: String, out: File): Boolean {
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return false
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (conn.responseCode != 200) return false
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            out.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private const val TIMEOUT_MS = 8_000
}
