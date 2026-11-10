package app.unscroll.data.guard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import app.unscroll.data.db.BlockSchedule
import app.unscroll.data.db.DefaultCategories
import app.unscroll.data.db.Cooldown
import app.unscroll.data.db.DailyOpen
import app.unscroll.data.db.ExtraGrant
import app.unscroll.data.db.FocusRoutine
import app.unscroll.data.db.InstantBlock
import app.unscroll.data.db.LimitWarning
import app.unscroll.data.db.ScheduleKinds
import app.unscroll.data.db.UnscrollDatabase
import app.unscroll.data.db.UsageLimit
import app.unscroll.data.db.WebDaily
import app.unscroll.data.db.hasAnyCap
import app.unscroll.data.stats.LimitKeys
import app.unscroll.data.stats.UsageStatsEngine
import app.unscroll.permissions.PermissionChecks
import app.unscroll.prefs.AppPrefs
import java.time.LocalDate
import java.time.ZoneId

data class AppChoice(
    val packageName: String,
    val label: String,
)

class GuardRepository(context: Context) {
    private val app = context.applicationContext
    private val db = UnscrollDatabase.get(app)
    private val engine = UsageStatsEngine(app)
    private val prefs = AppPrefs(app)
    private val pm: PackageManager = app.packageManager

    @Volatile private var usageStamp: Long = 0L
    @Volatile private var usageApps: Map<String, Long> = emptyMap()
    @Volatile private var usageOpens: Map<String, Int> = emptyMap()

    suspend fun snapshot(now: Long = System.currentTimeMillis()): RulesSnapshot {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        refreshUsage(now, today)
        db.instantBlocks().deleteExpired(now, LimitKeys.PERMANENT_UNTIL)
        db.cooldowns().deleteExpired(now)
        val limits = db.usageLimits().all()
        val blocks = db.instantBlocks().all()
        val schedules = db.schedules().all()
        val routines = db.focusRoutines().all()
        val cooldowns = db.cooldowns().all().associate { it.targetKey to it.untilMillis }
        val opens = db.dailyOpens().forDay(dayStart).associate { it.targetKey to it.count }
        val extras = db.extraGrants().forDay(dayStart).associateBy { it.targetKey }
        val web = db.webDaily().between(dayStart, dayEnd).associate { it.domain to it.durationMillis }
        val assignments = db.appCategories().all().associate { it.packageName to it.categoryKey }
        val categories = db.categories().all().associate { row ->
            row.key to DefaultCategories.displayLabel(app, row.key, row.label, row.custom)
        }
        val focusUntil = prefs.focusUntilMillis
        val packages = buildSet {
            addAll(limits.mapNotNull { row -> LimitKeys.payload(row.targetKey).takeIf { row.targetKey.startsWith("app:") } })
            addAll(blocks.map { it.targetKey.removePrefix("app:") })
            schedules.forEach { addAll(parseCsv(it.packagesCsv)) }
            routines.forEach { addAll(parseCsv(it.packagesCsv)) }
            addAll(prefs.focusPackages)
        }
        val labels = packages.associateWith { labelOf(it) }
        return RulesSnapshot(
            now = now,
            zone = zone,
            dayStart = dayStart,
            dayEnd = dayEnd,
            pauseUntil = prefs.pauseUntilMillis,
            extraEnabled = prefs.extraMinutesEnabled,
            limits = limits,
            blocks = blocks,
            schedules = schedules,
            cooldowns = cooldowns,
            opens = opens,
            engineOpens = usageOpens,
            extras = extras,
            appUsage = usageApps,
            usageAsOf = usageStamp,
            webUsage = web,
            categories = assignments,
            categoryLabels = categories,
            focusUntil = focusUntil,
            focusPackages = if (focusUntil > now) prefs.focusPackages else emptySet(),
            focusDomains = if (focusUntil > now) prefs.focusDomains else emptySet(),
            routines = routines,
            routineSkips = prefs.routineSkips(now),
            selfPackage = app.packageName,
            alwaysAllowed = alwaysAllowed(),
            launchers = launcherPackages(),
            appLabels = labels,
        )
    }

    suspend fun saveLimit(row: UsageLimit) {
        if (!row.hasAnyCap()) db.usageLimits().delete(row.targetKey)
        else db.usageLimits().upsert(row)
    }

    suspend fun saveDailyLimit(targetKey: String, dailyLimitMillis: Long?, weekdaysMask: Int) {
        val existing = db.usageLimits().find(targetKey)
        if (dailyLimitMillis == null || dailyLimitMillis <= 0L) {
            if (existing == null) return
            val kept = existing.copy(dailyLimitMillis = 0L)
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

    suspend fun limits(): List<UsageLimit> = db.usageLimits().all()

    suspend fun schedules(): List<BlockSchedule> = db.schedules().all()

    suspend fun schedule(id: Long): BlockSchedule? = db.schedules().find(id)

    suspend fun saveSchedule(row: BlockSchedule): Long = db.schedules().upsert(row)

    suspend fun deleteWindow(id: Long) {
        db.schedules().deleteWindow(id)
    }

    suspend fun findSleep(): BlockSchedule? = db.schedules().findKind(ScheduleKinds.SLEEP)

    /** [packages] est la liste blanche : ces applications restent ouvertes pendant la plage. */
    suspend fun saveSleep(
        enabled: Boolean,
        startMinute: Int,
        endMinute: Int,
        weekdaysMask: Int,
        packages: Set<String>,
    ) {
        val existing = db.schedules().findKind(ScheduleKinds.SLEEP)
        val id = db.schedules().upsert(
            BlockSchedule(
                id = existing?.id ?: 0,
                name = "",
                enabled = enabled,
                startMinute = startMinute,
                endMinute = endMinute,
                weekdaysMask = weekdaysMask and Weekdays.ALL,
                packagesCsv = toCsv(packages),
                domainsCsv = "",
                kind = ScheduleKinds.SLEEP,
            ),
        )
        db.schedules().deleteKindExcept(ScheduleKinds.SLEEP, id)
    }

    suspend fun routines(): List<FocusRoutine> = db.focusRoutines().all()

    suspend fun routine(id: Long): FocusRoutine? = db.focusRoutines().find(id)

    suspend fun saveRoutine(row: FocusRoutine): Long = db.focusRoutines().upsert(row)

    suspend fun deleteRoutine(id: Long) {
        db.focusRoutines().delete(id)
    }

    suspend fun saveBlock(targetKey: String, untilMillis: Long) {
        db.instantBlocks().upsert(
            InstantBlock(targetKey = targetKey, untilMillis = untilMillis, createdAt = System.currentTimeMillis()),
        )
    }

    suspend fun clearBlock(targetKey: String) {
        db.instantBlocks().delete(targetKey)
    }

    suspend fun blocks(): List<InstantBlock> = db.instantBlocks().all()

    suspend fun activeBlock(targetKey: String): InstantBlock? {
        val row = db.instantBlocks().find(targetKey) ?: return null
        if (row.untilMillis != LimitKeys.PERMANENT_UNTIL && row.untilMillis <= System.currentTimeMillis()) {
            db.instantBlocks().delete(targetKey)
            return null
        }
        return row
    }

    suspend fun incrementOpen(targetKey: String, now: Long = System.currentTimeMillis()) {
        if (targetKey.isBlank()) return
        val day = dayStart(now)
        val dao = db.dailyOpens()
        if (dao.bump(day, targetKey) > 0) return
        try {
            dao.insert(DailyOpen(day, targetKey, 1))
        } catch (_: SQLiteException) {
            dao.bump(day, targetKey)
        }
    }

    /** Pose une ligne vide pour qu’un domaine ajouté à la main apparaisse avant la première visite. */
    suspend fun ensureWeb(domain: String, now: Long = System.currentTimeMillis()) {
        if (domain.isBlank()) return
        val day = dayStart(now)
        val dao = db.webDaily()
        if (dao.bump(day, domain, 0L, 0) > 0) return
        try {
            dao.insert(WebDaily(day, domain, 0L, 0))
        } catch (_: SQLiteException) {
            dao.bump(day, domain, 0L, 0)
        }
    }

    suspend fun addWebTime(domain: String, deltaMillis: Long, visit: Boolean, now: Long = System.currentTimeMillis()) {
        if (domain.isBlank() || (deltaMillis <= 0L && !visit)) return
        val day = dayStart(now)
        val visits = if (visit) 1 else 0
        val dao = db.webDaily()
        if (dao.bump(day, domain, deltaMillis.coerceAtLeast(0L), visits) > 0) return
        try {
            dao.insert(WebDaily(day, domain, deltaMillis.coerceAtLeast(0L), visits))
        } catch (_: SQLiteException) {
            dao.bump(day, domain, deltaMillis.coerceAtLeast(0L), visits)
        }
    }

    suspend fun webBetween(fromDay: Long, toExclusive: Long): List<WebDaily> =
        db.webDaily().between(fromDay, toExclusive)

    suspend fun setCooldown(targetKey: String, untilMillis: Long) {
        db.cooldowns().upsert(Cooldown(targetKey, untilMillis))
    }

    suspend fun clearCooldown(targetKey: String) {
        db.cooldowns().delete(targetKey)
    }

    suspend fun clearCeiling(targetKey: String, now: Long = System.currentTimeMillis()) {
        db.extraGrants().clearCeiling(dayStart(now), targetKey)
    }

    /** @return false si le coup de pouce du jour est déjà pris. */
    suspend fun grantExtra(
        targetKey: String,
        reason: BlockReason,
        sessionSoFar: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val day = dayStart(now)
        val existing = db.extraGrants().forDay(day).find { it.targetKey == targetKey }
        if (existing != null) return false
        val bonus = if (reason == BlockReason.DAILY) LimitKeys.EXTRA_MILLIS else 0L
        val ceiling = if (reason == BlockReason.SESSION || reason == BlockReason.COOLDOWN) {
            sessionSoFar + LimitKeys.EXTRA_MILLIS
        } else {
            0L
        }
        val bypass = if (reason == BlockReason.DAILY || reason == BlockReason.SESSION || reason == BlockReason.COOLDOWN) {
            0L
        } else {
            now + LimitKeys.EXTRA_MILLIS
        }
        if (reason == BlockReason.SESSION || reason == BlockReason.COOLDOWN) {
            db.cooldowns().delete(targetKey)
        }
        db.limitWarnings().deleteFor(day, targetKey)
        db.limitWarnings().insert(LimitWarning(day, targetKey, 5))
        db.extraGrants().upsert(
            ExtraGrant(
                dayStart = day,
                targetKey = targetKey,
                reason = reason.name,
                bonusMillis = bonus,
                sessionCeilingMillis = ceiling,
                bypassUntil = bypass,
            ),
        )
        return true
    }

    /** @return true la première fois pour ce seuil, ce jour et cette cible. */
    suspend fun markWarned(targetKey: String, minutes: Int, now: Long = System.currentTimeMillis()): Boolean {
        val id = db.limitWarnings().insert(LimitWarning(dayStart(now), targetKey, minutes))
        return id != -1L
    }

    fun sleepChoices(): List<AppChoice> {
        val base = launchableApps()
        val known = base.map { it.packageName }.toSet()
        val extra = defaultSleepPackages()
            .filter { it !in known }
            .map { AppChoice(it, labelOf(it)) }
        return (base + extra).sortedBy { it.label.lowercase() }
    }

    fun launchableApps(): List<AppChoice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != app.packageName && it !in alwaysAllowed() }
            .map { AppChoice(it, labelOf(it)) }
            .sortedBy { it.label.lowercase() }
    }

    fun labelOf(packageName: String): String {
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrElse { packageName.substringAfterLast('.') }
    }

    fun prefs(): AppPrefs = prefs

    /** Filet lorsque l’accessibilité ne signale plus le premier plan. */
    fun currentForeground(now: Long, lookbackMs: Long): String? = engine.currentForeground(now, lookbackMs)

    fun defaultSleepPackages(): Set<String> {
        val dialer = runCatching {
            app.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        val sms = runCatching {
            android.provider.Telephony.Sms.getDefaultSmsPackage(app)
        }.getOrNull()
        return listOfNotNull(
            dialer,
            sms,
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.sec.android.app.clockpackage",
        ).filter { installed(it) }.toSet()
    }

    private fun installed(packageName: String): Boolean {
        return runCatching { pm.getApplicationInfo(packageName, 0) }.isSuccess
    }

    private fun alwaysAllowed(): Set<String> = setOf(
        app.packageName,
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    private fun launcherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    private fun refreshUsage(now: Long, today: LocalDate) {
        if (!PermissionChecks.hasUsageAccess(app)) {
            usageApps = emptyMap()
            usageOpens = emptyMap()
            usageStamp = 0L
            return
        }
        if (now - usageStamp < USAGE_CACHE_MS && usageStamp != 0L) return
        val slice = engine.compute(today, today.plusDays(1)).values.firstOrNull()
        usageApps = slice?.packages?.associate { it.packageName to it.durationMillis } ?: emptyMap()
        usageOpens = slice?.packages?.associate { it.packageName to it.openCount } ?: emptyMap()
        usageStamp = System.currentTimeMillis()
    }

    private fun dayStart(now: Long): Long {
        val zone = ZoneId.systemDefault()
        return InstantDay.start(now, zone)
    }

    private companion object {
        const val USAGE_CACHE_MS = 8_000L
    }
}

private object InstantDay {
    fun start(now: Long, zone: ZoneId): Long {
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
