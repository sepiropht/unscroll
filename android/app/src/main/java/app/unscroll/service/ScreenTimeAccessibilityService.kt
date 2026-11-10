package app.unscroll.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.unscroll.data.guard.Browsers
import app.unscroll.data.guard.Domains

/**
 * Optionnel : lit uniquement la barre d'adresse des navigateurs connus,
 * pour estimer le temps par site. Le blocage des applications se fait
 * ailleurs, par UsageStats et la superposition. Aucun geste, aucune
 * action Accueil, aucune détection du premier plan hors barre d'adresse.
 */
class ScreenTimeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        GuardRuntime.clearDomains()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        val eventPkg = event.packageName?.toString()
        if (eventPkg != null && !Browsers.isBrowser(eventPkg)) return
        val pkg = eventPkg ?: return
        GuardRuntime.setDomain(pkg, readDomain(pkg))
    }

    /**
     * Lit uniquement les identifiants de barre d'adresse. Le texte de la page n'est pas parcouru.
     */
    @Suppress("DEPRECATION")
    private fun readDomain(pkg: String): String? {
        val ids = Browsers.addressBarViewIds(pkg)
        if (ids.isEmpty()) return null
        val primary = rootInActiveWindow
        if (primary != null) {
            val found = findInRoot(primary, ids)
            runCatching { primary.recycle() }
            if (found != null) return found
        }
        val listed = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return null
        for (window in listed) {
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            } ?: continue
            if (root.packageName?.toString() != pkg) {
                runCatching { root.recycle() }
                continue
            }
            val found = findInRoot(root, ids)
            runCatching { root.recycle() }
            if (found != null) return found
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun findInRoot(root: AccessibilityNodeInfo, ids: List<String>): String? {
        for (id in ids) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id)
            } catch (_: Exception) {
                null
            } ?: continue
            var domain: String? = null
            for (node in nodes) {
                if (domain == null) {
                    domain = Domains.normalize(node.text?.toString())
                        ?: Domains.normalize(node.contentDescription?.toString())
                }
                runCatching { node.recycle() }
            }
            if (domain != null) return domain
        }
        return null
    }

    companion object {
        @Volatile
        private var instance: ScreenTimeAccessibilityService? = null

        fun isConnected(): Boolean = instance != null
    }
}
