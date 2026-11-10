package app.unscroll.data.repo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Noms de paquets livrés avec le système. Le résultat est gardé pour le processus. */
object InstalledApps {
    private val gate = Any()

    @Volatile
    private var system: Set<String>? = null

    fun systemPackages(context: Context): Set<String> {
        system?.let { return it }
        synchronized(gate) {
            system?.let { return it }
            val pm = context.applicationContext.packageManager
            @Suppress("DEPRECATION")
            val found = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { info -> info.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
                .map { it.packageName }
                .toSet()
            system = found
            return found
        }
    }
}
