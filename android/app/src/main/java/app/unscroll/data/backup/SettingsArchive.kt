package app.unscroll.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.unscroll.data.achieve.AchievementStore
import app.unscroll.data.db.AppCategory
import app.unscroll.data.db.BlockSchedule
import app.unscroll.data.db.Category
import app.unscroll.data.db.DefaultCategories
import app.unscroll.data.db.FocusRoutine
import app.unscroll.data.db.InstantBlock
import app.unscroll.data.db.ScheduleKinds
import app.unscroll.data.db.UnscrollDatabase
import app.unscroll.data.db.UsageLimit
import app.unscroll.data.repo.UsageRepository
import app.unscroll.data.stats.UsageStatsEngine
import app.unscroll.permissions.PermissionChecks
import app.unscroll.prefs.AppPrefs
import app.unscroll.prefs.EditLock
import app.unscroll.sync.SyncSettings
import app.unscroll.service.FocusRoutineScheduler
import app.unscroll.service.FocusService
import app.unscroll.service.ReminderScheduler
import app.unscroll.widget.HomeWidgets
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ArchiveException(val kind: Kind) : Exception() {
    enum class Kind { FORMAT, READ, WRITE, SIZE }
}

data class ArchivePreview(
    val limits: Int,
    val schedules: Int,
    val categories: Int,
    val routines: Int,
)

/** Fichier JSON des limites, plannings, catégories et réglages. Le code n’y entre pas. */
class SettingsArchive(context: Context) {
    private val app = context.applicationContext
    private val db = UnscrollDatabase.get(app)
    private val prefs = AppPrefs(app)

    suspend fun writeBackup(uri: Uri) {
        val snapshot = capture()
        val bytes = ArchiveCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
        writeBytes(uri, bytes)
    }

    internal suspend fun readBackup(uri: Uri): Pair<ArchivePreview, Snapshot> {
        val snapshot = ArchiveCodec.decode(readBytes(uri))
        val preview = ArchivePreview(
            limits = snapshot.limits.size,
            schedules = snapshot.schedules.size,
            categories = snapshot.categories.size,
            routines = snapshot.routines.size,
        )
        return preview to snapshot
    }

    internal suspend fun apply(snapshot: Snapshot) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.categories().deleteAll()
            db.appCategories().deleteAll()
            db.usageLimits().deleteAll()
            db.schedules().deleteAll()
            db.focusRoutines().deleteAll()
            db.instantBlocks().deleteAll()
            if (snapshot.categories.isNotEmpty()) db.categories().insertAll(snapshot.categories)
            db.categories().insertAll(DefaultCategories.entities())
            for (row in snapshot.assignments) {
                db.appCategories().upsert(row.copy(updatedAt = row.updatedAt.takeIf { it > 0L } ?: now))
            }
            for (row in snapshot.limits) db.usageLimits().upsert(row)
            var sleepSeen = 0
            var sleepKeep = 0L
            for (row in snapshot.schedules) {
                val id = db.schedules().upsert(row)
                if (row.kind == ScheduleKinds.SLEEP) {
                    sleepSeen += 1
                    sleepKeep = id
                }
            }
            if (sleepSeen > 1) db.schedules().deleteKindExcept(ScheduleKinds.SLEEP, sleepKeep)
            for (row in snapshot.routines) db.focusRoutines().upsert(row)
            for (row in snapshot.blocks) db.instantBlocks().upsert(row)
        }
        applySettings(snapshot.settings)
        ReminderScheduler.schedule(app)
        FocusRoutineScheduler.onChanged(app)
        HomeWidgets.refreshAsync(app)
    }

    suspend fun writeHistory(uri: Uri) {
        writeBytes(uri, historyCsv().toByteArray(Charsets.UTF_8))
    }

    suspend fun wipe() {
        withContext(Dispatchers.Main.immediate) { FocusService.haltIfRunning() }
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            UsageRepository(app).seedCategories()
            AchievementStore.get(app).clear()
            prefs.wipeFile()
            SyncSettings(app).clear()
        }
        EditLock.lock()
        ReminderScheduler.schedule(app)
        FocusRoutineScheduler.onChanged(app)
        HomeWidgets.refreshAsync(app)
    }

    private suspend fun capture(): Snapshot {
        val settings = SettingsBlob(
            themeMode = prefs.themeMode,
            dynamicColor = prefs.dynamicColor,
            weekStartsMonday = prefs.weekStartsMonday,
            hourFormat = prefs.hourFormat,
            showSystemApps = prefs.showSystemApps,
            excluded = prefs.excludedPackages,
            extraMinutes = prefs.extraMinutesEnabled,
            usageReminder = prefs.usageReminderMinutes,
            eyeBreak = prefs.eyeBreakMinutes,
            summaryMinute = prefs.dailySummaryMinute,
            draftMinutes = prefs.focusDraftMinutes,
            draftPackages = prefs.focusDraftPackages,
        )
        return Snapshot(
            settings = settings,
            categories = db.categories().all(),
            assignments = db.appCategories().all(),
            limits = db.usageLimits().all(),
            schedules = db.schedules().all(),
            routines = db.focusRoutines().all(),
            blocks = db.instantBlocks().all(),
        )
    }

    private fun applySettings(blob: SettingsBlob) {
        prefs.themeMode = blob.themeMode
        prefs.dynamicColor = blob.dynamicColor
        prefs.weekStartsMonday = blob.weekStartsMonday
        prefs.hourFormat = blob.hourFormat
        prefs.showSystemApps = blob.showSystemApps
        prefs.excludedPackages = blob.excluded
        prefs.extraMinutesEnabled = blob.extraMinutes
        prefs.usageReminderMinutes = blob.usageReminder
        prefs.eyeBreakMinutes = blob.eyeBreak
        prefs.dailySummaryMinute = blob.summaryMinute
        prefs.focusDraftMinutes = blob.draftMinutes
        prefs.focusDraftPackages = blob.draftPackages
    }

    private suspend fun historyCsv(): String {
        val zone = ZoneId.systemDefault()
        val rows = LinkedHashMap<Pair<Long, String>, Pair<Long, Int>>()
        for (stored in db.dailyUsage().allRows()) {
            if (stored.durationMillis <= 0L && stored.openCount <= 0) continue
            rows[stored.dayStart to stored.packageName] =
                stored.durationMillis.coerceAtLeast(0L) to stored.openCount.coerceAtLeast(0)
        }
        if (PermissionChecks.hasUsageAccess(app)) {
            val today = LocalDate.now(zone)
            val live = UsageStatsEngine(app).compute(today.minusDays(6), today.plusDays(1))
            for (slice in live.values) {
                for (pkg in slice.packages) {
                    if (pkg.durationMillis <= 0L && pkg.openCount <= 0) continue
                    rows[slice.dayStart to pkg.packageName] =
                        pkg.durationMillis.coerceAtLeast(0L) to pkg.openCount.coerceAtLeast(0)
                }
            }
        }
        val repo = UsageRepository(app)
        val lines = rows.map { (key, value) ->
            HistoryCsvLine(
                dayStart = key.first,
                packageName = key.second,
                label = repo.appLabel(key.second),
                durationMillis = value.first,
                openCount = value.second,
            )
        }
        return ArchiveCodec.renderHistory(zone, lines)
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        val stream = app.contentResolver.openOutputStream(uri, "wt") ?: throw ArchiveException(ArchiveException.Kind.WRITE)
        stream.use { it.write(bytes) }
    }

    private fun readBytes(uri: Uri): ByteArray {
        val stream = app.contentResolver.openInputStream(uri) ?: throw ArchiveException(ArchiveException.Kind.READ)
        return stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(tmp)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) throw ArchiveException(ArchiveException.Kind.SIZE)
                buffer.write(tmp, 0, read)
            }
            buffer.toByteArray()
        }
    }

    private companion object {
        const val MAX_BYTES = 1_500_000
    }
}

internal data class SettingsBlob(
    val themeMode: String,
    val dynamicColor: Boolean,
    val weekStartsMonday: Boolean,
    val hourFormat: String,
    val showSystemApps: Boolean,
    val excluded: Set<String>,
    val extraMinutes: Boolean,
    val usageReminder: Int,
    val eyeBreak: Int,
    val summaryMinute: Int,
    val draftMinutes: Int,
    val draftPackages: Set<String>,
)

internal data class Snapshot(
    val settings: SettingsBlob,
    val categories: List<Category>,
    val assignments: List<AppCategory>,
    val limits: List<UsageLimit>,
    val schedules: List<BlockSchedule>,
    val routines: List<FocusRoutine>,
    val blocks: List<InstantBlock>,
)
