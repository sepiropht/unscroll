package app.unscroll.sync

import app.unscroll.data.db.Session as DbSessionFq

import android.content.Context
import androidx.room.withTransaction
import app.unscroll.data.db.BlockSchedule
import app.unscroll.data.db.DailyOpen
import app.unscroll.data.db.DailyTotal
import app.unscroll.data.db.DailyUsage
import app.unscroll.data.db.NotificationCount
import app.unscroll.data.db.ScheduleKinds
import app.unscroll.data.db.UnscrollDatabase
import app.unscroll.data.db.WebDaily
import app.unscroll.prefs.AppPrefs
import app.unscroll.widget.HomeWidgets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L'envoi lit les tables locales. La réception complète les lignes reçues
 * et ne retire pas une ligne locale absente du fichier.
 */
class AndroidSnapshotStore(context: Context) {
    private val app = context.applicationContext
    private val db = UnscrollDatabase.get(app)
    private val prefs = AppPrefs(app)
    private val settings = SyncSettings(app)

    suspend fun capture(now: Instant = Instant.now()): AndroidSnapshot = withContext(Dispatchers.IO) {
        AndroidSnapshot(
            exportedAt = ISO.format(now),
            deviceId = settings.deviceId(),
            dailyUsage = db.dailyUsage().allRows().sortedWith(compareBy({ it.dayStart }, { it.packageName })),
            sessions = db.sessions().all().sortedWith(compareBy({ it.dayStart }, { it.packageName }, { it.startMillis })),
            appCategories = db.appCategories().all().sortedBy { it.packageName },
            categories = db.categories().all(),
            dailyTotals = db.dailyTotals().all().sortedBy { it.dayStart },
            notificationCounts = db.notificationCounts().all().sortedWith(compareBy({ it.dayStart }, { it.packageName })),
            usageLimits = db.usageLimits().all().sortedBy { it.targetKey },
            instantBlocks = db.instantBlocks().all().sortedBy { it.targetKey },
            schedules = db.schedules().all().sortedWith(compareBy({ it.kind }, { it.name }, { it.startMinute })),
            webDaily = db.webDaily().all().sortedWith(compareBy({ it.dayStart }, { it.domain })),
            dailyOpens = db.dailyOpens().all().sortedWith(compareBy({ it.dayStart }, { it.targetKey })),
            cooldowns = db.cooldowns().all().sortedBy { it.targetKey },
            extraGrants = db.extraGrants().all().sortedWith(compareBy({ it.dayStart }, { it.targetKey })),
            limitWarnings = db.limitWarnings().all().sortedWith(compareBy({ it.dayStart }, { it.targetKey }, { it.minutes })),
            preferences = SyncPreferences(
                themeMode = prefs.themeMode,
                dynamicColor = prefs.dynamicColor,
                excludedPackages = prefs.excludedPackages.filter { it.isNotBlank() }.sorted(),
                extraMinutesEnabled = prefs.extraMinutesEnabled,
            ),
        )
    }

    suspend fun apply(snapshot: AndroidSnapshot) {
        applyUsage(snapshot)
        applySettings(snapshot)
    }

    /** Tables d'usage de cet appareil. Les réglages partagés restent dans le blob `settings`. */
    suspend fun applyUsage(snapshot: AndroidSnapshot) = withContext(Dispatchers.IO) {
        db.withTransaction {
            mergeUsage(snapshot.dailyUsage)
            mergeSessions(snapshot.sessions)
            mergeTotals(snapshot.dailyTotals)
            mergeCounts(snapshot.notificationCounts)
            mergeWeb(snapshot.webDaily)
            mergeOpens(snapshot.dailyOpens)
            for (row in snapshot.cooldowns) db.cooldowns().upsert(row)
            for (row in snapshot.extraGrants) db.extraGrants().upsert(row)
            for (row in snapshot.limitWarnings) db.limitWarnings().insert(row)
        }
        HomeWidgets.refreshAsync(app)
    }

    suspend fun applySettings(snapshot: AndroidSnapshot) = withContext(Dispatchers.IO) {
        db.withTransaction {
            for (row in snapshot.categories) db.categories().upsert(row)
            val assigned = db.appCategories().all().associateBy { it.packageName }
            for (row in snapshot.appCategories) {
                val local = assigned[row.packageName]
                if (local == null || row.updatedAt >= local.updatedAt) db.appCategories().upsert(row)
            }
            for (row in snapshot.usageLimits) db.usageLimits().upsert(row)
            for (row in snapshot.instantBlocks) db.instantBlocks().upsert(row)
            mergeSchedules(snapshot.schedules)
        }
        val chosen = snapshot.preferences
        if (chosen.themeMode != null) prefs.themeMode = chosen.themeMode
        if (chosen.dynamicColor != null) prefs.dynamicColor = chosen.dynamicColor
        if (chosen.excludedPackages != null) prefs.excludedPackages = chosen.excludedPackages.toSet()
        if (chosen.extraMinutesEnabled != null) prefs.extraMinutesEnabled = chosen.extraMinutesEnabled
        HomeWidgets.refreshAsync(app)
    }

    private suspend fun mergeUsage(rows: List<DailyUsage>) {
        val local = db.dailyUsage().allRows().associateBy { it.dayStart to it.packageName }
        val writes = ArrayList<DailyUsage>()
        for (row in rows) {
            val have = local[row.dayStart to row.packageName]
            val merged = when {
                have == null -> row
                row.durationMillis > have.durationMillis -> row.copy(openCount = maxOf(row.openCount, have.openCount))
                else -> have.copy(openCount = maxOf(row.openCount, have.openCount))
            }
            if (merged != have) writes.add(merged)
        }
        if (writes.isNotEmpty()) db.dailyUsage().insertAll(writes)
    }

    private suspend fun mergeSessions(rows: List<DbSessionFq>) {
        val local = db.sessions().all().associateBy { Triple(it.packageName, it.startMillis, it.dayStart) }
        val fresh = ArrayList<DbSessionFq>()
        for (row in rows) {
            val have = local[Triple(row.packageName, row.startMillis, row.dayStart)]
            if (have == null) {
                fresh.add(row.copy(id = 0))
            } else if (row.endMillis - row.startMillis > have.endMillis - have.startMillis) {
                db.sessions().updateEnd(have.id, row.endMillis)
            }
        }
        if (fresh.isNotEmpty()) db.sessions().insertAll(fresh)
    }

    private suspend fun mergeTotals(rows: List<DailyTotal>) {
        val local = db.dailyTotals().all().associateBy { it.dayStart }
        for (row in rows) {
            val have = local[row.dayStart]
            val merged = when {
                have == null -> row
                row.durationMillis > have.durationMillis -> row.copy(unlockCount = maxOf(row.unlockCount, have.unlockCount))
                else -> have.copy(unlockCount = maxOf(row.unlockCount, have.unlockCount))
            }
            if (merged != have) db.dailyTotals().upsert(merged)
        }
    }

    private suspend fun mergeCounts(rows: List<NotificationCount>) {
        val local = db.notificationCounts().all().associateBy { it.dayStart to it.packageName }
        for (row in rows) {
            val have = local[row.dayStart to row.packageName]
            val count = maxOf(row.count, have?.count ?: 0)
            if (have == null || count != have.count) db.notificationCounts().upsert(row.copy(count = count))
        }
    }

    private suspend fun mergeWeb(rows: List<WebDaily>) {
        val local = db.webDaily().all().associateBy { it.dayStart to it.domain }
        for (row in rows) {
            val have = local[row.dayStart to row.domain]
            val merged = when {
                have == null -> row
                row.durationMillis > have.durationMillis -> row.copy(visitCount = maxOf(row.visitCount, have.visitCount))
                else -> have.copy(visitCount = maxOf(row.visitCount, have.visitCount))
            }
            if (merged != have) db.webDaily().upsert(merged)
        }
    }

    private suspend fun mergeOpens(rows: List<DailyOpen>) {
        val local = db.dailyOpens().all().associateBy { it.dayStart to it.targetKey }
        for (row in rows) {
            val have = local[row.dayStart to row.targetKey]
            val count = maxOf(row.count, have?.count ?: 0)
            if (have == null || count != have.count) db.dailyOpens().upsert(row.copy(count = count))
        }
    }

    private suspend fun mergeSchedules(rows: List<BlockSchedule>) {
        val sleep = rows.lastOrNull { it.kind == ScheduleKinds.SLEEP }
        if (sleep != null) {
            val local = db.schedules().findKind(ScheduleKinds.SLEEP)
            val keep = if (local == null) {
                db.schedules().upsert(sleep.copy(id = 0))
            } else {
                db.schedules().upsert(sleep.copy(id = local.id))
                local.id
            }
            db.schedules().deleteKindExcept(ScheduleKinds.SLEEP, keep)
        }
        val windows = db.schedules().all().filter { it.kind == ScheduleKinds.WINDOW }.toMutableList()
        for (row in rows) {
            if (row.kind != ScheduleKinds.WINDOW) continue
            val match = windows.find {
                it.name == row.name &&
                    it.startMinute == row.startMinute &&
                    it.endMinute == row.endMinute &&
                    it.weekdaysMask == row.weekdaysMask
            }
            if (match != null) {
                db.schedules().upsert(row.copy(id = match.id))
            } else {
                val id = db.schedules().upsert(row.copy(id = 0))
                windows.add(row.copy(id = id))
            }
        }
    }

    companion object {
        private val ISO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
