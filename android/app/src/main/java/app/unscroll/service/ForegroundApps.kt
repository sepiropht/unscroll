package app.unscroll.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Premier plan réel : fenêtre d’application focalisée, puis le paquet de l’événement.
 * Le clavier, la barre système et cette application ne remplacent pas l’application en cours.
 * Les autres volets et la fenêtre flottante restent dans [ScreenLayout.exposed].
 */
internal object ForegroundApps {
    fun capture(service: AccessibilityService, eventPackage: String?): ScreenLayout {
        var layout = VisibleWindows.layout(readWindows(service)) { isNoise(service, it) }
        val event = eventPackage?.takeIf { it.isNotBlank() && !isNoise(service, it) } ?: return layout
        if (layout.foreground != null) return layout
        if (event in layout.exposed || event in layout.pictureInPicture) return layout
        layout = layout.copy(foreground = event)
        return layout
    }

    fun resolve(service: AccessibilityService, eventPackage: String?): String? {
        return capture(service, eventPackage).foreground
    }

    fun isNoise(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (packageName == context.packageName) return true
        if (packageName == "android" || packageName == "com.android.systemui") return true
        return packageName == imePackage(context)
    }

    @Suppress("DEPRECATION")
    private fun readWindows(service: AccessibilityService): List<AppWindow> {
        val windows = try {
            service.windows
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val out = ArrayList<AppWindow>(windows.size)
        for (info in windows) {
            if (info.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = try {
                info.root
            } catch (_: Exception) {
                null
            }
            val pkg = try {
                root?.packageName?.toString().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (root != null) runCatching { root.recycle() }
            if (pkg.isBlank()) continue
            val pip = try {
                info.isInPictureInPictureMode
            } catch (_: Exception) {
                false
            }
            out += AppWindow(
                packageName = pkg,
                focused = info.isFocused,
                active = info.isActive,
                pictureInPicture = pip,
            )
        }
        return out
    }

    private fun imePackage(context: Context): String? {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ) ?: return null
        val pkg = flat.substringBefore('/').trim()
        return pkg.ifBlank { null }
    }
}
