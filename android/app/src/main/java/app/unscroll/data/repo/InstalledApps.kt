package app.unscroll.data.repo

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.Context

/**
 * Applications visibles sans QUERY_ALL_PACKAGES : celles qui ont un lanceur,
 * plus celles pour lesquelles [PackageManager.getApplicationInfo] réussit.
 * Les paquets non résolus (services système vus dans UsageStats) sont traités
 * à part : pas de libellé, éventuellement regroupés.
 */
object InstalledApps {
    private val gate = Any()

    @Volatile
    private var system: Set<String>? = null

    fun systemPackages(context: Context): Set<String> {
        system?.let { return it }
        synchronized(gate) {
            system?.let { return it }
            val pm = context.applicationContext.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val found = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .asSequence()
                .map { it.activityInfo.applicationInfo }
                .filter { info -> info.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
                .map { it.packageName }
                .toSet()
            system = found
            return found
        }
    }

    /** True when the package is not a visible launchable app (no label from the package manager). */
    fun isUnresolved(context: Context, packageName: String): Boolean {
        val pm = context.applicationContext.packageManager
        return runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() == null
    }
}
