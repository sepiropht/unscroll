package app.unscroll.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.unscroll.data.repo.UsageRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Compte les notifications reçues, sans lire leur titre ni leur texte.
 * Le service est optionnel : tant qu'il n'est pas activé dans les réglages Android,
 * aucun chiffre n'est produit et le compteur reste masqué.
 *
 * Les résumés de groupe et les notifications permanentes (lecture en cours, navigation)
 * sont ignorés. Une mise à jour de la même notification ne compte qu'une fois
 * tant que le processus reste en vie.
 */
class NotificationCounterService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seenKeys = ConcurrentHashMap.newKeySet<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg.isBlank()) return
        if (sbn.isOngoing) return
        val flags = sbn.notification?.flags ?: 0
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (!seenKeys.add(sbn.key)) return
        val postedAt = sbn.postTime
        scope.launch {
            runCatching {
                UsageRepository(applicationContext).recordNotification(pkg, postedAt)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        seenKeys.remove(key)
    }
}
