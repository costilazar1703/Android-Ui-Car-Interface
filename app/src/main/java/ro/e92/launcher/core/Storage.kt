package ro.e92.launcher.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Unde scrie aplicația lucrurile care trebuie să supraviețuiască ei: backup-ul
 * setărilor și jurnalele de erori.
 *
 * Preferința e `/sdcard/E92Launcher/`, nu folderul privat al aplicației. Motivul
 * e concret: `Android/data/<pachet>` se ȘTERGE la dezinstalare — exact momentul
 * în care ai nevoie de backup. Un folder în rădăcina stocării rămâne, se vede din
 * orice manager de fișiere și se copiază pe un stick fără laptop.
 *
 * Dacă permisiunea de scriere n-a fost acordată (sau stocarea lipsește), se cade
 * pe folderul privat. Pierzi supraviețuirea la dezinstalare, dar nu pierzi
 * jurnalele — iar aplicația nu crapă încercând să scrie unde nu are voie.
 */
object Storage {

    private const val FOLDER = "E92Launcher"

    fun root(context: Context): File {
        val shared = sharedRoot(context)
        if (shared != null) return shared
        val private = context.getExternalFilesDir(null) ?: context.filesDir
        return private.also { it.mkdirs() }
    }

    fun logs(context: Context): File = File(root(context), "logs").also { it.mkdirs() }

    fun backupFile(context: Context): File = File(root(context), "settings-backup.json")

    /** True dacă scriem în locul care supraviețuiește dezinstalării. */
    fun isShared(context: Context): Boolean = sharedRoot(context) != null

    private fun sharedRoot(context: Context): File? {
        val granted = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return fallback("permisiune de scriere neacordata")
        val state = Environment.getExternalStorageState()
        if (state != Environment.MEDIA_MOUNTED) return fallback("stocare in starea $state")
        val dir = File(Environment.getExternalStorageDirectory(), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return fallback("mkdirs a esuat pe $dir")
        if (!dir.canWrite()) return fallback("$dir nu e scriibil")
        return dir
    }

    /** De ce s-a ales folderul privat - se vede in logcat, si in masina. */
    private fun fallback(reason: String): File? {
        Log.w(TAG, "Folosesc folderul privat: $reason")
        return null
    }

    private const val TAG = "E92Storage"
}
