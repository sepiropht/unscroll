package app.unscroll.sync

import app.unscroll.data.db.AppCategory
import app.unscroll.data.db.BlockSchedule
import app.unscroll.data.db.Category
import app.unscroll.data.db.Cooldown
import app.unscroll.data.db.DailyOpen
import app.unscroll.data.db.DailyTotal
import app.unscroll.data.db.DailyUsage
import app.unscroll.data.db.ExtraGrant
import app.unscroll.data.db.InstantBlock
import app.unscroll.data.db.LimitWarning
import app.unscroll.data.db.NotificationCount
import app.unscroll.data.db.Session
import app.unscroll.data.db.UsageLimit
import app.unscroll.data.db.WebDaily

/** Réglages qui voyagent. Un champ nul, à la lecture, ne remplace pas la valeur locale. */
data class SyncPreferences(
    val themeMode: String?,
    val dynamicColor: Boolean?,
    val excludedPackages: List<String>?,
    val extraMinutesEnabled: Boolean?,
)

/** Section `clients.android.snapshot`. Le code, le jeton et la phrase n'y entrent pas. */
data class AndroidSnapshot(
    val exportedAt: String,
    val deviceId: String,
    val dailyUsage: List<DailyUsage>,
    val sessions: List<Session>,
    val appCategories: List<AppCategory>,
    val categories: List<Category>,
    val dailyTotals: List<DailyTotal>,
    val notificationCounts: List<NotificationCount>,
    val usageLimits: List<UsageLimit>,
    val instantBlocks: List<InstantBlock>,
    val schedules: List<BlockSchedule>,
    val webDaily: List<WebDaily>,
    val dailyOpens: List<DailyOpen>,
    val cooldowns: List<Cooldown>,
    val extraGrants: List<ExtraGrant>,
    val limitWarnings: List<LimitWarning>,
    val preferences: SyncPreferences,
)

data class SyncTransferResult(
    val ok: Boolean,
    val at: String? = null,
    val reason: String? = null,
)

data class SyncPullResult(
    val ok: Boolean,
    val snapshot: AndroidSnapshot? = null,
    /** Réglages partagés à appliquer. Absent si rien à reprendre. */
    val settings: AndroidSnapshot? = null,
    val settingsRevision: Int? = null,
    val settingsEtag: String? = null,
    val settingsBody: String? = null,
    val reason: String? = null,
)

data class SyncStatus(
    val enabled: Boolean,
    val configured: Boolean,
)

interface SyncProvider {
    suspend fun push(snapshot: AndroidSnapshot): SyncTransferResult
    suspend fun pull(): SyncPullResult
    fun status(): SyncStatus
}

/** Réponse de `POST /v1/accounts`. `token` n'est pas une chaîne à journaliser. */
class ProvisionResult(
    val ok: Boolean,
    val token: String? = null,
    val reason: String? = null,
) {
    override fun toString(): String = "ProvisionResult(ok=$ok, reason=$reason)"
}

/** Aucune requête. La synchro réelle passe par SelfHostedSyncProvider, éteint tant que la personne n'a rien activé. */
class NoopSyncProvider : SyncProvider {
    override suspend fun push(snapshot: AndroidSnapshot): SyncTransferResult =
        SyncTransferResult(ok = false, reason = "disabled")

    override suspend fun pull(): SyncPullResult =
        SyncPullResult(ok = false, reason = "disabled")

    override fun status(): SyncStatus = SyncStatus(
        enabled = false,
        configured = false,
    )
}
