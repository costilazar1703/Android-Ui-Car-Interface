package ro.e92.launcher.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String
) {
    val key: String get() = "$packageName/$activityName"
}

/**
 * Lista de aplicații lansabile + cache de iconițe.
 *
 * Scanarea PackageManager-ului e scumpă (sute de ms pe 2 GB), deci se face o
 * singură dată și se reface doar la PACKAGE_ADDED / PACKAGE_REMOVED.
 * Iconițele se încarcă leneș, pe thread-ul care le cere, și rămân într-un LruCache
 * mic — pe un ecran încap ~12 celule, 48 de intrări acoperă câteva pagini.
 */
class AppRepository(private val context: Context) {

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val iconCache = object : LruCache<String, Drawable>(ICON_CACHE_ENTRIES) {}

    private var receiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            iconCache.evictAll()
            Thread { refreshBlocking() }.start()
        }
    }

    fun startWatching() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(packageReceiver, filter)
        receiverRegistered = true
    }

    fun stopWatching() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(packageReceiver) }
        receiverRegistered = false
    }

    /** Blocant — apelează-l de pe un thread de background. */
    fun refreshBlocking() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val self = context.packageName

        val list = pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != self }
            .map {
                AppEntry(
                    packageName = it.activityInfo.packageName,
                    activityName = it.activityInfo.name,
                    label = it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()

        _apps.value = list
    }

    /**
     * Încarcă iconițele în cache înainte ca lista să ajungă pe ecran.
     * Blocant — de apelat pe un thread de background. Fără asta, primul scroll
     * prin drawer citește de pe disc în onBindViewHolder și se vede.
     */
    fun preloadIconsBlocking(entries: List<AppEntry>, limit: Int = ICON_CACHE_ENTRIES) {
        for (entry in entries.take(limit)) icon(entry)
    }

    /** Sincron. Apelabil din onBindViewHolder: hit-ul de cache e cazul normal. */
    fun icon(entry: AppEntry): Drawable? {
        iconCache.get(entry.key)?.let { return it }
        val pm = context.packageManager
        val drawable = runCatching {
            pm.getActivityIcon(android.content.ComponentName(entry.packageName, entry.activityName))
        }.getOrNull() ?: runCatching {
            pm.getApplicationIcon(entry.packageName)
        }.getOrNull()
        if (drawable != null) iconCache.put(entry.key, drawable)
        return drawable
    }

    fun launchIntent(entry: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(entry.packageName, entry.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    companion object {
        /** Pe ecran încap ~12 celule; 48 acoperă câteva pagini de scroll. */
        const val ICON_CACHE_ENTRIES = 48
    }
}
