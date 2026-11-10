package app.unscroll.service

import android.content.Context
import android.content.Intent
import app.unscroll.data.achieve.AchievementStore
import app.unscroll.data.guard.BlockDecision
import app.unscroll.data.guard.Browsers
import app.unscroll.data.guard.BlockEvaluator
import app.unscroll.data.guard.BlockReason
import app.unscroll.data.guard.Evaluation
import app.unscroll.data.guard.FocusWindows
import app.unscroll.data.guard.ForegroundQuery
import app.unscroll.data.guard.GuardRepository
import app.unscroll.data.guard.RulesSnapshot
import app.unscroll.data.guard.ScheduleWindow
import app.unscroll.data.guard.SubjectKind
import app.unscroll.data.guard.Weekdays
import app.unscroll.data.stats.LimitKeys
import app.unscroll.permissions.PermissionChecks
import app.unscroll.prefs.AppPrefs
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Applique les limites à partir de [UsageStatsManager] et de la superposition.
 * Le domaine courant, s'il est connu, vient uniquement du service d'accessibilité
 * optionnel, limité à la barre d'adresse des navigateurs.
 */
class GuardEngine(context: Context) {
    private val app = context.applicationContext
    private val repo = GuardRepository(app)
    private val presenter = BlockPresenter(app)
    private val reminders = ReminderClock(app)
    private val mutex = Mutex()

    private var realPackage: String? = null
    private var segmentStart = 0L
    private var frozen = false
    private var suppressUntil = 0L
    private val accum = HashMap<String, Long>()
    private val exhausted = HashSet<String>()
    private val cooling = HashSet<String>()
    private val lastLeftApp = HashMap<String, Long>()
    private val lastLeftCategory = HashMap<String, Long>()
    private var categoryOf = HashMap<String, String>()
    private var launchers = emptySet<String>()
    private var alwaysAllowed = emptySet<String>()
    private var countedBlockKey: String? = null
    private var currentDomain: String? = null
    private var webSegmentStart = 0L
    private val lastLeftWeb = HashMap<String, Long>()

    suspend fun tick() {
        mutex.withLock { evaluate() }
    }

    fun requestClose() {
        suppressUntil = System.currentTimeMillis() + SUPPRESS_MS
        presenter.hide()
        goHome(app)
    }

    fun requestExtra(targetKey: String, reasonName: String) {
        // Applied on the next tick so the mutex stays on the engine thread.
        pendingExtra = targetKey to reasonName
    }

    fun destroy() {
        presenter.destroy()
    }

    @Volatile
    private var pendingExtra: Pair<String, String>? = null

    private suspend fun evaluate() {
        val now = System.currentTimeMillis()
        val extra = pendingExtra
        pendingExtra = null
        if (extra != null) onExtra(extra.first, extra.second, now)

        val fg = repo.currentForeground(now, LOOKBACK_MS)
            ?.takeIf { it.isNotBlank() && !isNoise(app, it) }

        if (fg != null && fg != realPackage) {
            switchPackage(fg, now, countOpen = true)
        } else if (fg == null && realPackage != null) {
            clearForeground(now)
        }

        var rules = repo.snapshot(now)
        categoryOf = HashMap(rules.categories)
        launchers = rules.launchers
        alwaysAllowed = rules.alwaysAllowed
        expireCooldowns(rules.cooldowns, now)
        rules = repo.snapshot(System.currentTimeMillis())

        val pkg = realPackage
        if (pkg.isNullOrBlank()) {
            updateDomain(null, rules.now)
            noteAchievements(rules, null)
            withContext(Dispatchers.Main) { presenter.hide() }
            noteReminders()
            return
        }

        val barDomain = if (Browsers.isBrowser(pkg)) GuardRuntime.domainFor(pkg) else null
        if (Browsers.isBrowser(pkg)) {
            updateDomain(barDomain, rules.now)
        } else if (currentDomain != null) {
            updateDomain(null, rules.now)
        }
        flushWeb(rules.now, force = false)

        val gap = if (rules.usageAsOf in 1 until rules.now) {
            (rules.now - rules.usageAsOf).coerceAtMost(8_000L)
        } else {
            0L
        }
        val session = sessionNow(rules.now)
        val evaluation = BlockEvaluator.evaluate(
            rules,
            ForegroundQuery(
                packageName = pkg,
                domain = currentDomain,
                sessionMillis = session,
                exhausted = exhausted.toSet(),
                isLauncher = pkg in rules.launchers,
                liveTailMillis = minOf(gap, liveAppExtra(rules.now)),
            ),
        )
        applyEvaluation(evaluation, rules.now)
        val decision = evaluation.decision
        noteAchievements(rules, decision)
        withContext(Dispatchers.Main) {
            val stamp = System.currentTimeMillis()
            if (!decision.blocked) {
                if (frozen) unfreeze(stamp)
                presenter.hide()
            } else if (stamp < suppressUntil) {
                presenter.hide()
            } else {
                if (!frozen) freeze(stamp)
                presenter.show(decision)
            }
        }
        noteReminders()
    }

    private suspend fun applyEvaluation(evaluation: Evaluation, now: Long) {
        for ((key, until) in evaluation.cooldownsToSet) {
            repo.setCooldown(key, until)
            cooling += key
            freeze(now)
        }
        for (key in evaluation.ceilingsToClear) repo.clearCeiling(key, now)
        exhausted += evaluation.exhaust
        for (warn in evaluation.warnings) {
            if (repo.markWarned(warn.targetKey, warn.minutes, now)) {
                GuardNotifications.warn(app, warn.targetKey, warn.label, warn.minutes)
            }
        }
    }

    private fun noteAchievements(rules: RulesSnapshot, decision: BlockDecision?) {
        val blocked = decision?.takeIf { it.blocked && it.subjectKind != SubjectKind.WEB }
        val key = blocked?.targetKey?.ifBlank { blocked.subject }
        val fresh = blocked != null && !key.isNullOrBlank() && key != countedBlockKey
        if (blocked != null && !key.isNullOrBlank()) {
            if (fresh) countedBlockKey = key
        } else {
            countedBlockKey = null
        }
        val armed = guardIsArmed(rules)
        if (armed || fresh) {
            AchievementStore.get(app)
                .noteGuard(rules.dayStart, armed || fresh, fresh)
        }
    }

    private fun noteReminders() {
        val pkg = realPackage
        val counting = pkg != null && !frozen && pkg !in launchers && pkg !in alwaysAllowed
        runCatching { reminders.onSample(pkg, counting, System.currentTimeMillis()) }
    }

    private suspend fun switchPackage(pkg: String, now: Long, countOpen: Boolean) {
        val elapsed = takeSegment(now)
        val prev = realPackage
        if (prev != null && elapsed > 0L) {
            addAccum(LimitKeys.app(prev), elapsed)
            categoryOf[prev]?.let { addAccum(LimitKeys.category(it), elapsed) }
        }
        if (prev != null) {
            lastLeftApp[prev] = now
            categoryOf[prev]?.let { lastLeftCategory[it] = now }
        }
        flushWeb(now, force = true)
        if (currentDomain != null) {
            lastLeftWeb[currentDomain!!] = now
            currentDomain = null
        }
        resetStaleApp(pkg, now)
        realPackage = pkg
        segmentStart = now
        frozen = false
        suppressUntil = 0L
        if (countOpen && pkg !in alwaysAllowed && pkg !in launchers) {
            repo.incrementOpen(LimitKeys.app(pkg), now)
        }
    }

    private suspend fun clearForeground(now: Long) {
        val elapsed = takeSegment(now)
        val prev = realPackage
        if (prev != null && elapsed > 0L) {
            addAccum(LimitKeys.app(prev), elapsed)
            categoryOf[prev]?.let { addAccum(LimitKeys.category(it), elapsed) }
        }
        if (prev != null) {
            lastLeftApp[prev] = now
            categoryOf[prev]?.let { lastLeftCategory[it] = now }
        }
        flushWeb(now, force = true)
        if (currentDomain != null) {
            lastLeftWeb[currentDomain!!] = now
            currentDomain = null
        }
        realPackage = null
        frozen = false
    }

    private fun resetStaleApp(pkg: String, now: Long) {
        val left = lastLeftApp[pkg] ?: return
        if (now - left <= SESSION_GAP_MS) return
        val key = LimitKeys.app(pkg)
        if (key !in cooling) {
            accum.remove(key)
            exhausted.remove(key)
        }
        val cat = categoryOf[pkg] ?: return
        val catLeft = lastLeftCategory[cat] ?: return
        if (now - catLeft <= SESSION_GAP_MS) return
        val catKey = LimitKeys.category(cat)
        if (catKey !in cooling) {
            accum.remove(catKey)
            exhausted.remove(catKey)
        }
    }

    private fun takeSegment(now: Long): Long {
        if (frozen) {
            frozen = false
            segmentStart = now
            return 0L
        }
        val elapsed = (now - segmentStart).coerceAtLeast(0L)
        segmentStart = now
        return elapsed
    }

    private fun liveAppExtra(now: Long): Long {
        if (frozen || realPackage == null) return 0L
        return (now - segmentStart).coerceAtLeast(0L)
    }

    private fun sessionNow(now: Long): Map<String, Long> {
        val map = HashMap(accum)
        val pkg = realPackage ?: return map
        val extra = liveAppExtra(now)
        if (extra > 0L) {
            val appKey = LimitKeys.app(pkg)
            map[appKey] = (map[appKey] ?: 0L) + extra
            categoryOf[pkg]?.let { cat ->
                val key = LimitKeys.category(cat)
                map[key] = (map[key] ?: 0L) + extra
            }
        }
        val domain = currentDomain
        if (domain != null && !frozen) {
            val webExtra = (now - webSegmentStart).coerceAtLeast(0L)
            if (webExtra > 0L) {
                val key = LimitKeys.web(domain)
                map[key] = (map[key] ?: 0L) + webExtra
            }
        }
        return map
    }

    private suspend fun updateDomain(domain: String?, now: Long) {
        if (domain == currentDomain) return
        flushWeb(now, force = true)
        if (currentDomain != null) lastLeftWeb[currentDomain!!] = now
        currentDomain = domain
        webSegmentStart = now
        if (domain != null) {
            val left = lastLeftWeb[domain]
            val key = LimitKeys.web(domain)
            if (left != null && now - left > SESSION_GAP_MS && key !in cooling) {
                accum.remove(key)
                exhausted.remove(key)
            }
            repo.incrementOpen(key, now)
            repo.addWebTime(domain, 0L, visit = true, now = now)
        }
    }

    private suspend fun flushWeb(now: Long, force: Boolean) {
        val domain = currentDomain ?: return
        if (frozen) return
        val delta = (now - webSegmentStart).coerceAtLeast(0L)
        if (!force && delta < 1_000L) return
        if (delta <= 0L) return
        webSegmentStart = now
        addAccum(LimitKeys.web(domain), delta)
        repo.addWebTime(domain, delta, visit = false, now = now)
    }

    private fun addAccum(key: String, delta: Long) {
        if (delta <= 0L) return
        accum[key] = (accum[key] ?: 0L) + delta
    }

    private fun freeze(now: Long) {
        if (frozen) return
        val elapsed = (now - segmentStart).coerceAtLeast(0L)
        val pkg = realPackage
        if (pkg != null && elapsed > 0L) {
            addAccum(LimitKeys.app(pkg), elapsed)
            categoryOf[pkg]?.let { addAccum(LimitKeys.category(it), elapsed) }
        }
        segmentStart = now
        frozen = true
    }

    private fun unfreeze(now: Long) {
        if (!frozen) return
        segmentStart = now
        webSegmentStart = now
        frozen = false
    }

    private fun expireCooldowns(cooldowns: Map<String, Long>, now: Long) {
        val done = cooling.filter { key -> (cooldowns[key] ?: 0L) <= now }
        for (key in done) {
            cooling.remove(key)
            accum.remove(key)
            exhausted.remove(key)
            if (key.startsWith("app:") && realPackage == LimitKeys.payload(key)) {
                segmentStart = now
            }
        }
    }

    private suspend fun onExtra(targetKey: String, reasonName: String, now: Long) {
        val reason = runCatching { BlockReason.valueOf(reasonName) }.getOrNull() ?: return
        val soFar = accum[targetKey] ?: 0L
        val granted = repo.grantExtra(targetKey, reason, soFar, now)
        if (!granted) return
        exhausted.remove(targetKey)
        cooling.remove(targetKey)
        unfreeze(now)
        suppressUntil = 0L
    }

    companion object {
        private const val LOOKBACK_MS = 120_000L
        private const val SESSION_GAP_MS = 30_000L
        private const val SUPPRESS_MS = 1_500L

        fun isNoise(context: Context, packageName: String): Boolean {
            if (packageName.isBlank()) return true
            if (packageName == context.packageName) return true
            if (packageName == "android" || packageName == "com.android.systemui") return true
            return false
        }

        fun goHome(context: Context) {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(home) }
        }

        fun guardIsArmed(context: Context, now: Long = System.currentTimeMillis()): Boolean {
            return runBlocking {
                guardIsArmed(GuardRepository(context.applicationContext).snapshot(now))
            }
        }

        fun guardIsArmed(rules: RulesSnapshot): Boolean {
            val today = Instant.ofEpochMilli(rules.now).atZone(rules.zone).dayOfWeek
            if (rules.limits.any { limit ->
                    (limit.dailyLimitMillis > 0L && Weekdays.contains(limit.weekdaysMask, today)) ||
                        limit.sessionLimitMillis > 0L ||
                        limit.openLimit > 0
                }
            ) {
                return true
            }
            if (rules.blocks.any { it.untilMillis > rules.now }) return true
            if (rules.schedules.any { schedule ->
                    schedule.enabled && (
                        Weekdays.contains(schedule.weekdaysMask, today) ||
                            ScheduleWindow.isActive(
                                rules.now,
                                rules.zone,
                                schedule.startMinute,
                                schedule.endMinute,
                                schedule.weekdaysMask,
                            )
                        )
                }
            ) {
                return true
            }
            if (rules.routines.any { routine ->
                    routine.enabled && routine.durationMinutes > 0 && (
                        Weekdays.contains(routine.weekdaysMask, today) ||
                            FocusWindows.isActive(
                                rules.now,
                                rules.zone,
                                routine.startMinute,
                                routine.durationMinutes,
                                routine.weekdaysMask,
                            )
                        )
                }
            ) {
                return true
            }
            return rules.focusUntil > rules.now &&
                (rules.focusPackages.isNotEmpty() || rules.focusDomains.isNotEmpty())
        }

        fun shouldRun(context: Context): Boolean {
            val app = context.applicationContext
            if (!PermissionChecks.hasUsageAccess(app)) return false
            val prefs = AppPrefs(app)
            if (FocusSessions.manualRunning(prefs, System.currentTimeMillis())) return true
            return guardIsArmed(app)
        }
    }
}

object GuardRuntime {
    private val lock = Any()
    private var engine: GuardEngine? = null
    @Volatile
    private var domains: Map<String, String> = emptyMap()

    fun setDomain(packageName: String, domain: String?) {
        synchronized(lock) {
            val next = HashMap(domains)
            if (domain.isNullOrBlank()) next.remove(packageName) else next[packageName] = domain
            domains = next
        }
    }

    fun domainFor(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return domains[packageName]
    }

    fun clearDomains() {
        synchronized(lock) { domains = emptyMap() }
    }

    fun engine(context: Context): GuardEngine = synchronized(lock) {
        engine ?: GuardEngine(context.applicationContext).also { engine = it }
    }

    suspend fun tickOnce(context: Context) {
        engine(context).tick()
    }

    fun requestClose(): Boolean {
        val current = synchronized(lock) { engine }
        if (current != null) {
            current.requestClose()
            return true
        }
        return false
    }

    fun requestExtra(targetKey: String, reason: String) {
        synchronized(lock) { engine }?.requestExtra(targetKey, reason)
    }

    fun goHomeFallback(context: Context) = GuardEngine.goHome(context)
}
