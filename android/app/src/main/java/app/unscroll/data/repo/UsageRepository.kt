package app.unscroll.data.repo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import app.unscroll.data.db.AppCategory
import app.unscroll.data.db.Category
import app.unscroll.data.db.DailyTotal
import app.unscroll.data.db.DailyUsage
import app.unscroll.data.db.DefaultCategories
import app.unscroll.data.db.InstantBlock
import app.unscroll.data.db.NotificationCount
import app.unscroll.data.db.Session
import app.unscroll.data.db.UnscrollDatabase
import app.unscroll.data.db.UsageLimit
import app.unscroll.data.db.hasAnyCap
import app.unscroll.data.guard.Weekdays
import app.unscroll.data.stats.DaySlice
import app.unscroll.data.stats.HourlyCodec
import app.unscroll.data.stats.LimitKeys
import app.unscroll.data.stats.PkgSlice
import app.unscroll.data.stats.UsageStatsEngine
import app.unscroll.permissions.PermissionChecks
import app.unscroll.prefs.AppPrefs
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class UsageRepository(context: Context) {
    private val app = context.applicationContext
    private val db = UnscrollDatabase.get(app)
    private val engine = UsageStatsEngine(app)
    private val pm: PackageManager = app.packageManager

    suspend fun seedCategories() {
        if (db.categories().count() > 0) return
        db.categories().insertAll(DefaultCategories.entities())
    }

    /**
     * Journées de [start] inclus à [endExclusive] exclus.
     * Les 7 derniers jours viennent des événements live ; au-delà, de Room
     * si une photo a déjà été prise.
     */
    suspend fun loadDays(start: LocalDate, endExclusive: LocalDate): List<DaySlice> {
        if (!endExclusive.isAfter(start)) return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val recentFrom = today.minusDays(RECENT_DAYS - 1)
        val hasAccess = PermissionChecks.hasUsageAccess(app)
        val live = if (hasAccess) engine.compute(start, endExclusive) else emptyMap()
        val fromMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMillis = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val storedTotals = db.dailyTotals().between(fromMillis, toMillis).associateBy { it.dayStart }
        val storedUsage = db.dailyUsage().between(fromMillis, toMillis).groupBy { it.dayStart }

        val out = ArrayList<DaySlice>()
        var date = start
        while (date.isBefore(endExclusive)) {
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val liveDay = live[dayStart]
            val stored = storedTotals[dayStart]
            val slice = when {
                preferStored() && stored != null -> stored.toSlice(date, storedUsage[dayStart].orEmpty())
                hasAccess && !date.isBefore(recentFrom) && liveDay != null -> liveDay
                stored != null -> stored.toSlice(date, storedUsage[dayStart].orEmpty())
                liveDay != null -> liveDay
                else -> DaySlice.empty(date, dayStart)
            }
            out += slice
            date = date.plusDays(1)
        }
        return out
    }

    /** Comme [loadDays], puis classe les applications vues qui n'ont pas encore de famille. */
    suspend fun loadDaysEnsured(start: LocalDate, endExclusive: LocalDate): List<DaySlice> {
        val days = loadDays(start, endExclusive)
        val names = days.flatMap { day -> day.packages.map { it.packageName } }
        ensureCategories(names)
        return days
    }

    /** Photographie les jours encore couverts par le système, sans rétrécir l'historique. */
    suspend fun persistRecent(daysBack: Long = 9) {
        if (preferStored()) return
        if (!PermissionChecks.hasUsageAccess(app)) return
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val slices = engine.compute(today.minusDays(daysBack), today.plusDays(1))
        for (slice in slices.values) {
            if (slice.date != today && slice.eventCount == 0) continue
            val existing = db.dailyTotals().find(slice.dayStart)
            if (slice.date != today && existing != null && slice.totalMillis < existing.durationMillis) {
                continue
            }
            writeSlice(slice)
        }
    }

    private suspend fun writeSlice(slice: DaySlice) {
        val now = System.currentTimeMillis()
        val usageRows = slice.packages.map { pkg ->
            DailyUsage(
                dayStart = slice.dayStart,
                packageName = pkg.packageName,
                durationMillis = pkg.durationMillis,
                openCount = pkg.openCount,
                hourlyMillis = HourlyCodec.encode(pkg.hourly),
            )
        }
        val sessionRows = slice.sessions.map { session ->
            Session(
                packageName = session.packageName,
                startMillis = session.startMillis,
                endMillis = session.endMillis,
                dayStart = slice.dayStart,
            )
        }
        db.withTransaction {
            db.sessions().deleteDay(slice.dayStart)
            db.dailyUsage().deleteDay(slice.dayStart)
            if (sessionRows.isNotEmpty()) db.sessions().insertAll(sessionRows)
            if (usageRows.isNotEmpty()) db.dailyUsage().insertAll(usageRows)
            db.dailyTotals().upsert(
                DailyTotal(
                    dayStart = slice.dayStart,
                    durationMillis = slice.totalMillis,
                    unlockCount = slice.unlockCount,
                    hourlyMillis = HourlyCodec.encode(slice.hourly),
                    updatedAt = now,
                ),
            )
        }
        for (pkg in slice.packages) {
            val info = runCatching { pm.getApplicationInfo(pkg.packageName, 0) }.getOrNull()
            val key = if (info == null) "autre" else DefaultCategories.keyFor(info.category)
            db.appCategories().insertIgnore(
                AppCategory(
                    packageName = pkg.packageName,
                    categoryKey = key,
                    updatedAt = now,
                ),
            )
        }
    }

    fun appLabel(packageName: String): String {
        return runCatching {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrElse { packageName.substringAfterLast('.') }
    }

    fun excludedPackages(): Set<String> = AppPrefs(app).excludedPackages

    /** Exclusions choisies, et applications système si le réglage les masque. */
    fun statsExclusions(): Set<String> = AppPrefs(app).hiddenFromStats(app)

    fun setExcluded(packageName: String, excluded: Boolean) {
        AppPrefs(app).setPackageExcluded(packageName, excluded)
    }

    /** Pose la catégorie système si l'application n'a pas encore été classée. */
    suspend fun ensureCategories(packageNames: Collection<String>) {
        if (packageNames.isEmpty()) return
        seedCategories()
        val now = System.currentTimeMillis()
        for (name in packageNames.distinct()) {
            if (name.isBlank()) continue
            val info = runCatching { pm.getApplicationInfo(name, 0) }.getOrNull()
            val key = if (info == null) "autre" else DefaultCategories.keyFor(info.category)
            db.appCategories().insertIgnore(
                AppCategory(packageName = name, categoryKey = key, updatedAt = now),
            )
        }
    }

    suspend fun categories(): List<Category> {
        seedCategories()
        return db.categories().all().map { row ->
            row.copy(label = DefaultCategories.displayLabel(app, row.key, row.label, row.custom))
        }
    }

    suspend fun assignments(): Map<String, String> {
        return db.appCategories().all().associate { it.packageName to it.categoryKey }
    }

    suspend fun categoryOf(packageName: String): String {
        ensureCategories(listOf(packageName))
        return db.appCategories().find(packageName)?.categoryKey ?: "autre"
    }

    suspend fun assignCategory(packageName: String, categoryKey: String) {
        seedCategories()
        db.appCategories().upsert(
            AppCategory(
                packageName = packageName,
                categoryKey = categoryKey,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun addCustomCategory(label: String, colorArgb: Int): Category? {
        seedCategories()
        val clean = label.trim().take(40)
        if (clean.isEmpty()) return null
        val row = Category(
            key = "perso_" + System.currentTimeMillis().toString(36),
            label = clean,
            colorArgb = colorArgb,
            custom = true,
            position = db.categories().maxPosition() + 1,
        )
        db.categories().insert(row)
        return row
    }

    suspend fun deleteCustomCategory(key: String) {
        val row = db.categories().find(key) ?: return
        if (!row.custom || key == "autre") return
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.appCategories().moveAll(key, "autre", now)
            db.usageLimits().delete(LimitKeys.category(key))
            db.categories().deleteCustom(key)
        }
    }

    suspend fun notificationsBetween(
        start: LocalDate,
        endExclusive: LocalDate,
        excluded: Set<String> = emptySet(),
    ): Int {
        val (from, to) = millisRange(start, endExclusive)
        return db.notificationCounts().between(from, to)
            .filter { it.packageName !in excluded }
            .sumOf { it.count }
    }

    suspend fun notificationsFor(
        packageName: String,
        start: LocalDate,
        endExclusive: LocalDate,
    ): Int {
        val (from, to) = millisRange(start, endExclusive)
        return db.notificationCounts().between(from, to)
            .filter { it.packageName == packageName }
            .sumOf { it.count }
    }

    /** +1 pour une notification nouvelle. Les mises à jour du même jour s'additionnent ici, le service dédoublonne. */
    suspend fun recordNotification(packageName: String, postedAtMillis: Long) {
        if (packageName.isBlank() || packageName == app.packageName) return
        val zone = ZoneId.systemDefault()
        val dayStart = Instant.ofEpochMilli(postedAtMillis).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val dao = db.notificationCounts()
        if (dao.bump(dayStart, packageName) > 0) return
        try {
            dao.insert(NotificationCount(dayStart, packageName, 1))
        } catch (_: SQLiteException) {
            dao.bump(dayStart, packageName)
        }
    }

    suspend fun limitFor(targetKey: String): Long? {
        val row = db.usageLimits().find(targetKey) ?: return null
        return row.dailyLimitMillis.takeIf { it > 0L }
    }

    suspend fun allLimits(): List<UsageLimit> = db.usageLimits().all()

    suspend fun limitRow(targetKey: String): UsageLimit? = db.usageLimits().find(targetKey)

    suspend fun saveLimit(targetKey: String, dailyLimitMillis: Long?) {
        val mask = db.usageLimits().find(targetKey)?.weekdaysMask ?: Weekdays.ALL
        saveDailyLimit(targetKey, dailyLimitMillis, mask)
    }

    suspend fun saveDailyLimit(targetKey: String, dailyLimitMillis: Long?, weekdaysMask: Int) {
        val existing = db.usageLimits().find(targetKey)
        if (dailyLimitMillis == null || dailyLimitMillis <= 0L) {
            if (existing == null) return
            val kept = existing.copy(dailyLimitMillis = 0)
            if (!kept.hasAnyCap()) db.usageLimits().delete(targetKey) else db.usageLimits().upsert(kept)
        } else {
            val base = existing ?: UsageLimit(targetKey = targetKey)
            db.usageLimits().upsert(
                base.copy(
                    dailyLimitMillis = dailyLimitMillis,
                    weekdaysMask = weekdaysMask and Weekdays.ALL,
                ),
            )
        }
    }

    suspend fun activeBlock(packageName: String): InstantBlock? {
        val row = db.instantBlocks().find(packageName) ?: return null
        if (row.untilMillis != LimitKeys.PERMANENT_UNTIL && row.untilMillis <= System.currentTimeMillis()) {
            db.instantBlocks().delete(packageName)
            return null
        }
        return row
    }

    suspend fun saveBlock(packageName: String, untilMillis: Long) {
        db.instantBlocks().upsert(
            InstantBlock(
                targetKey = packageName,
                untilMillis = untilMillis,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clearBlock(packageName: String) {
        db.instantBlocks().delete(packageName)
    }

    private fun millisRange(start: LocalDate, endExclusive: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to
            endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun DailyTotal.toSlice(date: LocalDate, rows: List<DailyUsage>): DaySlice {
        val packages = rows.map { row ->
            PkgSlice(
                packageName = row.packageName,
                durationMillis = row.durationMillis,
                openCount = row.openCount,
                hourly = HourlyCodec.decode(row.hourlyMillis),
            )
        }.sortedByDescending { it.durationMillis }
        return DaySlice(
            dayStart = dayStart,
            date = date,
            totalMillis = durationMillis,
            unlockCount = unlockCount,
            hourly = HourlyCodec.decode(hourlyMillis),
            packages = packages,
            sessions = emptyList(),
            eventCount = if (durationMillis > 0L || unlockCount > 0) 1 else 0,
        )
    }

    /**
     * Debug-only: if files/unscroll-demo exists in the app sandbox, prefer Room data
     * over live usage stats. Used to seed example screenshots. Never on in release.
     */
    private fun preferStored(): Boolean {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        val candidates = listOfNotNull(
            File(app.filesDir, "unscroll-demo"),
            app.getExternalFilesDir(null)?.let { File(it, "unscroll-demo") },
        )
        return candidates.any { it.exists() }
    }

    private companion object {
        const val RECENT_DAYS = 7L
    }
}
