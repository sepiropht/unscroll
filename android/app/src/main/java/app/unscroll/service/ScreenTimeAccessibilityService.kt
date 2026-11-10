package app.unscroll.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.unscroll.data.achieve.AchievementStore
import app.unscroll.data.guard.BlockDecision
import app.unscroll.data.guard.BlockEvaluator
import app.unscroll.data.guard.Evaluation
import app.unscroll.data.guard.BlockReason
import app.unscroll.data.guard.Browsers
import app.unscroll.data.guard.Domains
import app.unscroll.data.guard.FocusWindows
import app.unscroll.data.guard.ForegroundQuery
import app.unscroll.data.guard.GuardRepository
import app.unscroll.data.guard.RulesSnapshot
import app.unscroll.data.guard.ScheduleWindow
import app.unscroll.data.guard.SubjectKind
import app.unscroll.data.guard.Weekdays
import app.unscroll.data.stats.LimitKeys
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Détecte l’application au premier plan et ré-applique le blocage à chaque retour.
 * La lecture UsageStats sert de secours lorsque aucun événement n’arrive.
 */
class ScreenTimeAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<GuardEvent>()
    private val repo by lazy { GuardRepository(this) }
    private val presenter by lazy { BlockPresenter(this) }
    private val reminders by lazy { ReminderClock(this) }

    private var realPackage: String? = null
    private var currentDomain: String? = null
    private var segmentStart = 0L
    private var webSegmentStart = 0L
    private var frozen = false
    private var lastA11yAt = 0L
    private var suppressUntil = 0L
    private val accum = HashMap<String, Long>()
    private val exhausted = HashSet<String>()
    private val cooling = HashSet<String>()
    private val lastLeftApp = HashMap<String, Long>()
    private val lastLeftCategory = HashMap<String, Long>()
    private val lastLeftWeb = HashMap<String, Long>()
    private var categoryOf = HashMap<String, String>()
    private var launchers = emptySet<String>()
    private var alwaysAllowed = emptySet<String>()
    private var lastKeeperAt = 0L
    private var exposedPackages: List<String> = emptyList()
    private var pipPackages: Set<String> = emptySet()
    private var exposedDomains: Map<String, String> = emptyMap()
    @Volatile private var postedPackage: String? = null
    @Volatile private var postedDomain: String? = null
    @Volatile private var postedKey: String = ""
    private var countedBlockKey: String? = null

    private val tick = object : Runnable {
        override fun run() {
            val sample = sampleLayout()
            val now = System.currentTimeMillis()
            val fg = sample.foreground
            if (fg != null && fg != realPackage) {
                lastA11yAt = now
                remember(sample)
                enqueue(GuardEvent.Window(sample, now))
            } else {
                if (fg != null || sample.exposed.isNotEmpty()) lastA11yAt = now
                remember(sample)
                enqueue(GuardEvent.Tick(sample, now))
            }
            val keeperNow = System.currentTimeMillis()
            if (keeperNow - lastKeeperAt >= 60_000L) {
                lastKeeperAt = keeperNow
                FocusSessionKeeper.ensure(this@ScreenTimeAccessibilityService)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val recheck = Runnable {
        val now = System.currentTimeMillis()
        enqueue(GuardEvent.Tick(sampleLayout(), now))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        GuardNotifications.ensure(this)
        lastKeeperAt = System.currentTimeMillis()
        handler.post(tick)
        maybeRestartFocus()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(recheck)
        presenter.destroy()
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        val eventPkg = event.packageName?.toString()
        val sample = sampleLayout()
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (eventPkg == null || !Browsers.isBrowser(eventPkg)) return
            lastA11yAt = System.currentTimeMillis()
            if (sample.key() == postedKey) return
            remember(sample)
            val focusedBrowser = sample.foreground == eventPkg || realPackage == eventPkg
            if (focusedBrowser) enqueue(GuardEvent.Window(sample, lastA11yAt))
            else enqueue(GuardEvent.Tick(sample, lastA11yAt))
            return
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        lastA11yAt = System.currentTimeMillis()
        if (sample.key() == postedKey) return
        remember(sample)
        val fg = sample.foreground
        if (fg != null && fg != realPackage) enqueue(GuardEvent.Window(sample, lastA11yAt))
        else enqueue(GuardEvent.Tick(sample, lastA11yAt))
    }

    override fun onInterrupt() = Unit

    private fun enqueue(event: GuardEvent) {
        synchronized(queue) { queue.add(event) }
        kick()
    }

    private fun kick() {
        scope.launch {
            mutex.withLock {
                while (true) {
                    val batch = ArrayList<GuardEvent>()
                    synchronized(queue) {
                        while (queue.isNotEmpty()) batch += queue.removeFirst()
                    }
                    if (batch.isEmpty()) break
                    drain(batch)
                }
            }
        }
    }

    private suspend fun drain(batch: List<GuardEvent>) {
        val now = System.currentTimeMillis()
        var goHome = false
        for (event in batch) {
            when (event) {
                is GuardEvent.Window -> noteLayout(event.sample, event.now, countOpen = true)
                is GuardEvent.Tick -> noteLayout(event.sample, event.now, countOpen = false)
                is GuardEvent.Extra -> onExtra(event.targetKey, event.reason, now)
                GuardEvent.Close -> {
                    suppressUntil = now + SUPPRESS_MS
                    goHome = true
                }
            }
        }
        flushWeb(now, force = false)
        var rules = repo.snapshot(now)
        categoryOf = HashMap(rules.categories)
        launchers = rules.launchers
        alwaysAllowed = rules.alwaysAllowed
        adoptUsageFallback(now, rules.categories)
        expireCooldowns(rules.cooldowns, now)
        flushWeb(now, force = true)
        rules = repo.snapshot(System.currentTimeMillis())
        val candidates = ArrayList<String>()
        val pkg = realPackage
        if (!pkg.isNullOrBlank()) candidates += pkg
        for (other in exposedPackages) {
            if (other.isNotBlank() && other != pkg && other !in candidates) candidates += other
        }
        if (candidates.isEmpty()) {
            noteAchievements(rules, null)
            withContext(Dispatchers.Main) { presenter.hide() }
            noteReminders()
            return
        }
        val gap = if (rules.usageAsOf in 1 until rules.now) {
            (rules.now - rules.usageAsOf).coerceAtMost(8_000L)
        } else {
            0L
        }
        val session = sessionNow(rules.now)
        var decision = BlockDecision.allow()
        var chosenPkg: String? = null
        val blockedPkgs = ArrayList<String>()
        for (candidate in candidates) {
            val tail = if (candidate == pkg) minOf(gap, liveAppExtra(rules.now)) else 0L
            val domain = if (candidate == pkg) currentDomain else exposedDomains[candidate]
            val evaluation = BlockEvaluator.evaluate(
                rules,
                ForegroundQuery(
                    packageName = candidate,
                    domain = domain,
                    sessionMillis = session,
                    exhausted = exhausted.toSet(),
                    isLauncher = candidate in rules.launchers,
                    liveTailMillis = tail,
                ),
            )
            if (chosenPkg == null) applyEvaluation(evaluation, rules.now)
            if (evaluation.decision.blocked) {
                blockedPkgs += candidate
                if (chosenPkg == null) {
                    decision = evaluation.decision
                    chosenPkg = candidate
                }
            }
        }
        noteAchievements(rules, decision)
        val pip = blockedPkgs.firstOrNull { it in pipPackages }
        val otherPane = exposedPackages.any { it != chosenPkg }
        withContext(Dispatchers.Main) {
            val stamp = System.currentTimeMillis()
            if (goHome) {
                val moved = performGlobalAction(GLOBAL_ACTION_HOME)
                if (!moved) goHomeFallback(this@ScreenTimeAccessibilityService)
            }
            if (!decision.blocked) {
                if (frozen) unfreeze(stamp)
                presenter.hide()
            } else if (stamp < suppressUntil) {
                presenter.hide()
                scheduleRecheck(suppressUntil)
            } else {
                if (!frozen) freeze(stamp)
                presenter.show(decision, pip, otherPane)
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
                GuardNotifications.warn(this, warn.targetKey, warn.label, warn.minutes)
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
            AchievementStore.get(this).noteGuard(rules.dayStart, armed || fresh, fresh)
        }
    }

    private fun guardIsArmed(rules: RulesSnapshot): Boolean {
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

    private fun noteReminders() {
        val pkg = realPackage
        val counting = pkg != null && !frozen && pkg !in launchers && pkg !in alwaysAllowed
        runCatching { reminders.onSample(pkg, counting, System.currentTimeMillis()) }
    }

    private fun scheduleRecheck(at: Long) {
        handler.removeCallbacks(recheck)
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0L) + 80L
        handler.postDelayed(recheck, delay)
    }

    private suspend fun noteLayout(sample: ScreenSample, now: Long, countOpen: Boolean) {
        exposedPackages = sample.exposed
        pipPackages = sample.pictureInPicture
        exposedDomains = sample.domains
        val fg = sample.foreground?.takeIf { it.isNotBlank() && !ForegroundApps.isNoise(this, it) }
        if (fg == null) {
            val current = realPackage
            if (current != null && current !in sample.exposed && current !in sample.pictureInPicture) {
                clearForeground(now)
            }
            return
        }
        if (fg != realPackage) switchPackage(fg, now, countOpen)
        val domain = sample.domains[fg] ?: sample.domain
        if (Browsers.isBrowser(fg)) {
            if (domain != null) updateDomain(domain, now)
        } else if (currentDomain != null) {
            updateDomain(null, now)
        }
    }

    private suspend fun clearForeground(now: Long) {
        if (realPackage == null && currentDomain == null) return
        val elapsed = takeSegment(now)
        val prev = realPackage
        if (prev != null && elapsed > 0L) {
            addAccum(LimitKeys.app(prev), elapsed)
            categoryOf[prev]?.let { cat -> addAccum(LimitKeys.category(cat), elapsed) }
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

    private fun sampleLayout(): ScreenSample {
        val layout = ForegroundApps.capture(this, null)
        val domains = HashMap<String, String>()
        val fg = layout.foreground
        if (fg != null && Browsers.isBrowser(fg)) readDomain(fg)?.let { domains[fg] = it }
        for (pkg in layout.exposed) {
            if (Browsers.isBrowser(pkg)) readDomain(pkg)?.let { domains[pkg] = it }
        }
        return ScreenSample(
            foreground = fg,
            domain = fg?.let { domains[it] },
            exposed = layout.exposed,
            pictureInPicture = layout.pictureInPicture,
            domains = domains,
        )
    }

    private fun remember(sample: ScreenSample) {
        postedPackage = sample.foreground
        postedDomain = sample.domain
        postedKey = sample.key()
    }

    private suspend fun switchPackage(pkg: String, now: Long, countOpen: Boolean = true) {
        val elapsed = takeSegment(now)
        val prev = realPackage
        if (prev != null && elapsed > 0L) {
            addAccum(LimitKeys.app(prev), elapsed)
            categoryOf[prev]?.let { cat -> addAccum(LimitKeys.category(cat), elapsed) }
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
        return map
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
            if (key.startsWith("web:") && currentDomain == LimitKeys.payload(key)) {
                webSegmentStart = now
            }
        }
    }

    private suspend fun adoptUsageFallback(now: Long, categories: Map<String, String>) {
        val quiet = lastA11yAt == 0L || now - lastA11yAt >= FALLBACK_QUIET_MS
        if (realPackage != null && !quiet) return
        val lookback = when {
            realPackage == null || lastA11yAt == 0L -> 3L * 60L * 60L * 1000L
            else -> (now - lastA11yAt + 5_000L).coerceIn(60_000L, 3L * 60L * 60L * 1000L)
        }
        val latest = repo.currentForeground(now, lookback) ?: return
        if (latest == realPackage || latest == packageName) return
        if (ForegroundApps.isNoise(this, latest)) return
        categoryOf = HashMap(categories)
        switchPackage(latest, now, countOpen = false)
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

    /**
     * Lit uniquement les identifiants de barre d’adresse. Le texte de la page n’est pas parcouru.
     * Si la fenêtre active n’est pas le navigateur (clavier, bulle), les autres fenêtres sont essayées.
     */
    @Suppress("DEPRECATION")
    private fun readDomain(pkg: String?): String? {
        if (pkg == null || !Browsers.isBrowser(pkg)) return null
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

    private fun maybeRestartFocus() {
        FocusSessionKeeper.ensure(this)
    }

    private sealed interface GuardEvent {
        data class Window(val sample: ScreenSample, val now: Long) : GuardEvent
        data class Tick(val sample: ScreenSample, val now: Long) : GuardEvent
        data class Extra(val targetKey: String, val reason: String) : GuardEvent
        data object Close : GuardEvent
    }

    companion object {
        private const val TICK_MS = 8_000L
        private const val SESSION_GAP_MS = 30_000L
        private const val FALLBACK_QUIET_MS = 12_000L
        private const val SUPPRESS_MS = 1_500L

        @Volatile
        private var instance: ScreenTimeAccessibilityService? = null

        fun requestClose(): Boolean {
            val service = instance ?: return false
            service.enqueue(GuardEvent.Close)
            return true
        }

        fun requestExtra(targetKey: String, reason: String) {
            val service = instance ?: return
            service.enqueue(GuardEvent.Extra(targetKey, reason))
        }

        fun goHomeFallback(context: android.content.Context) {
            val home = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(home) }
        }
    }
}

private data class ScreenSample(
    val foreground: String?,
    val domain: String?,
    val exposed: List<String>,
    val pictureInPicture: Set<String>,
    val domains: Map<String, String>,
) {
    fun key(): String {
        val hosts = domains.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        return listOf(
            foreground.orEmpty(),
            domain.orEmpty(),
            exposed.joinToString(","),
            pictureInPicture.sorted().joinToString(","),
            hosts,
        ).joinToString("#")
    }
}
