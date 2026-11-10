package app.unscroll.data.guard

import app.unscroll.data.db.BlockSchedule
import app.unscroll.data.db.ExtraGrant
import app.unscroll.data.db.FocusRoutine
import app.unscroll.data.db.InstantBlock
import app.unscroll.data.db.ScheduleKinds
import app.unscroll.data.db.UsageLimit
import app.unscroll.data.stats.LimitKeys
import java.time.Instant
import java.time.ZoneId

enum class BlockReason {
    DAILY,
    SESSION,
    COOLDOWN,
    OPENS,
    INSTANT,
    SCHEDULE,
    SLEEP,
    FOCUS,
}

enum class SubjectKind { APP, CATEGORY, WEB }

data class Warn(
    val targetKey: String,
    val label: String,
    val minutes: Int,
)

data class BlockDecision(
    val blocked: Boolean,
    val reason: BlockReason? = null,
    val subjectKind: SubjectKind = SubjectKind.APP,
    val subject: String = "",
    val targetKey: String = "",
    val untilMillis: Long? = null,
    val allowExtra: Boolean = false,
    val scheduleName: String = "",
    val warn: Warn? = null,
) {
    companion object {
        fun allow(warn: Warn? = null) = BlockDecision(blocked = false, warn = warn)
    }
}

data class RulesSnapshot(
    val now: Long,
    val zone: ZoneId,
    val dayStart: Long,
    val dayEnd: Long,
    val pauseUntil: Long,
    val extraEnabled: Boolean,
    val limits: List<UsageLimit>,
    val blocks: List<InstantBlock>,
    val schedules: List<BlockSchedule>,
    val cooldowns: Map<String, Long>,
    val opens: Map<String, Int>,
    val engineOpens: Map<String, Int>,
    val extras: Map<String, ExtraGrant>,
    val appUsage: Map<String, Long>,
    /** Horodatage de la mesure [appUsage]. Sert à combler le court retard des statistiques. */
    val usageAsOf: Long,
    val webUsage: Map<String, Long>,
    val categories: Map<String, String>,
    val categoryLabels: Map<String, String>,
    val focusUntil: Long,
    val focusPackages: Set<String>,
    val focusDomains: Set<String>,
    val routines: List<FocusRoutine>,
    /** Fin d’ignorance par identifiant de session récurrente. */
    val routineSkips: Map<Long, Long>,
    val selfPackage: String,
    val alwaysAllowed: Set<String>,
    val launchers: Set<String>,
    val appLabels: Map<String, String>,
)

data class ForegroundQuery(
    val packageName: String,
    val domain: String?,
    /** Temps de session déjà accumulé, segment en cours inclus, par clé de cible. */
    val sessionMillis: Map<String, Long>,
    val exhausted: Set<String>,
    val isLauncher: Boolean,
    /** Temps du premier plan encore absent des statistiques, déjà borné par l’appelant. */
    val liveTailMillis: Long = 0L,
)

data class Evaluation(
    val decision: BlockDecision,
    val cooldownsToSet: List<Pair<String, Long>> = emptyList(),
    val ceilingsToClear: List<String> = emptyList(),
    val exhaust: List<String> = emptyList(),
    val warnings: List<Warn> = emptyList(),
)

private const val FIVE_MIN = 5L * 60L * 1000L
private const val ONE_MIN = 60L * 1000L

object BlockEvaluator {
    fun evaluate(rules: RulesSnapshot, query: ForegroundQuery): Evaluation {
        val pkg = query.packageName
        if (pkg.isBlank() || pkg == rules.selfPackage) return Evaluation(BlockDecision.allow())
        if (pkg in rules.alwaysAllowed || query.isLauncher || pkg in rules.launchers) {
            return Evaluation(BlockDecision.allow())
        }

        val paused = rules.pauseUntil > rules.now
        val warnings = mutableListOf<Warn>()
        val cooldowns = mutableListOf<Pair<String, Long>>()
        val clearCeilings = mutableListOf<String>()
        val exhaust = mutableListOf<String>()

        fun blocked(
            reason: BlockReason,
            key: String,
            kind: SubjectKind,
            subject: String,
            until: Long?,
            scheduleName: String = "",
        ): Evaluation {
            val grant = rules.extras[key]
            val already = grant != null && grant.dayStart == rules.dayStart
            return Evaluation(
                decision = BlockDecision(
                    blocked = true,
                    reason = reason,
                    subjectKind = kind,
                    subject = subject,
                    targetKey = key,
                    untilMillis = until,
                    allowExtra = rules.extraEnabled && !already,
                    scheduleName = scheduleName,
                    warn = null,
                ),
                cooldownsToSet = cooldowns,
                ceilingsToClear = clearCeilings,
                exhaust = exhaust,
                warnings = warnings,
            )
        }

        if (rules.focusUntil > rules.now) {
            val matchedDomain = query.domain?.let { Domains.bestMatch(it, rules.focusDomains) }
            val appHit = pkg in rules.focusPackages
            if (matchedDomain != null && !appHit) {
                val key = LimitKeys.web(matchedDomain)
                if (!bypasses(rules, key, BlockReason.FOCUS)) {
                    return blocked(BlockReason.FOCUS, key, SubjectKind.WEB, matchedDomain, rules.focusUntil)
                }
            } else if (appHit) {
                val key = LimitKeys.app(pkg)
                if (!bypasses(rules, key, BlockReason.FOCUS)) {
                    return blocked(BlockReason.FOCUS, key, SubjectKind.APP, labelOf(rules, pkg), rules.focusUntil)
                }
            }
        }

        for (routine in rules.routines) {
            if (!routine.enabled || routine.durationMinutes <= 0) continue
            val skipUntil = rules.routineSkips[routine.id] ?: 0L
            if (skipUntil > rules.now) continue
            if (!FocusWindows.isActive(
                    rules.now,
                    rules.zone,
                    routine.startMinute,
                    routine.durationMinutes,
                    routine.weekdaysMask,
                )
            ) {
                continue
            }
            if (pkg !in parseCsv(routine.packagesCsv)) continue
            val key = LimitKeys.app(pkg)
            if (!bypasses(rules, key, BlockReason.FOCUS)) {
                val until = FocusWindows.endMillis(
                    rules.now,
                    rules.zone,
                    routine.startMinute,
                    routine.durationMinutes,
                )
                return blocked(BlockReason.FOCUS, key, SubjectKind.APP, labelOf(rules, pkg), until, routine.name)
            }
        }

        val sleep = rules.schedules.firstOrNull { it.kind == ScheduleKinds.SLEEP && it.enabled }
        if (sleep != null && ScheduleWindow.isActive(rules.now, rules.zone, sleep.startMinute, sleep.endMinute, sleep.weekdaysMask)) {
            val whitelist = parseCsv(sleep.packagesCsv)
            if (pkg !in whitelist) {
                val key = LimitKeys.app(pkg)
                if (!bypasses(rules, key, BlockReason.SLEEP)) {
                    val until = ScheduleWindow.endMillis(rules.now, rules.zone, sleep.startMinute, sleep.endMinute)
                    return blocked(BlockReason.SLEEP, key, SubjectKind.APP, labelOf(rules, pkg), until)
                }
            }
        }

        for (schedule in rules.schedules) {
            if (schedule.kind != ScheduleKinds.WINDOW || !schedule.enabled) continue
            if (!ScheduleWindow.isActive(rules.now, rules.zone, schedule.startMinute, schedule.endMinute, schedule.weekdaysMask)) {
                continue
            }
            val matchedDomain = query.domain?.let { Domains.bestMatch(it, parseCsv(schedule.domainsCsv)) }
            val appHit = pkg in parseCsv(schedule.packagesCsv)
            if (matchedDomain != null && !appHit) {
                val key = LimitKeys.web(matchedDomain)
                if (!bypasses(rules, key, BlockReason.SCHEDULE)) {
                    val until = ScheduleWindow.endMillis(rules.now, rules.zone, schedule.startMinute, schedule.endMinute)
                    return blocked(BlockReason.SCHEDULE, key, SubjectKind.WEB, matchedDomain, until, schedule.name)
                }
            } else if (appHit) {
                val key = LimitKeys.app(pkg)
                if (!bypasses(rules, key, BlockReason.SCHEDULE)) {
                    val until = ScheduleWindow.endMillis(rules.now, rules.zone, schedule.startMinute, schedule.endMinute)
                    return blocked(BlockReason.SCHEDULE, key, SubjectKind.APP, labelOf(rules, pkg), until, schedule.name)
                }
            }
        }

        if (!paused) {
            instant(rules, pkg, query.domain)?.let { decision ->
                return Evaluation(decision)
            }

            val tail = query.liveTailMillis.coerceIn(0L, 8_000L)
            val appKey = LimitKeys.app(pkg)
            considerLimit(
                rules = rules,
                limit = rules.limits.find { it.targetKey == appKey },
                key = appKey,
                kind = SubjectKind.APP,
                subject = labelOf(rules, pkg),
                usage = (rules.appUsage[pkg] ?: 0L) + tail,
                opens = opensOf(rules, appKey, pkg),
                session = query.sessionMillis[appKey] ?: 0L,
                exhausted = appKey in query.exhausted,
                warnings = warnings,
                cooldowns = cooldowns,
                clearCeilings = clearCeilings,
                exhaust = exhaust,
            )?.let { return it }

            val categoryKey = rules.categories[pkg]
            if (categoryKey != null) {
                val key = LimitKeys.category(categoryKey)
                val members = rules.categories.filterValues { it == categoryKey }.keys
                val usage = members.sumOf { rules.appUsage[it] ?: 0L } + if (pkg in members) tail else 0L
                val opens = members.sumOf { opensOf(rules, LimitKeys.app(it), it) }
                considerLimit(
                    rules = rules,
                    limit = rules.limits.find { it.targetKey == key },
                    key = key,
                    kind = SubjectKind.CATEGORY,
                    subject = rules.categoryLabels[categoryKey] ?: categoryKey,
                    usage = usage,
                    opens = opens,
                    session = query.sessionMillis[key] ?: 0L,
                    exhausted = key in query.exhausted,
                    warnings = warnings,
                    cooldowns = cooldowns,
                    clearCeilings = clearCeilings,
                    exhaust = exhaust,
                )?.let { return it }
            }

            val domain = query.domain
            if (domain != null) {
                val matched = rules.limits
                    .filter { LimitKeys.isWeb(it.targetKey) && Domains.covers(domain, LimitKeys.payload(it.targetKey)) }
                    .sortedByDescending { LimitKeys.payload(it.targetKey).length }
                for (limit in matched) {
                    val ruleHost = LimitKeys.payload(limit.targetKey)
                    considerLimit(
                        rules = rules,
                        limit = limit,
                        key = limit.targetKey,
                        kind = SubjectKind.WEB,
                        subject = ruleHost,
                        usage = coveredWebUsage(rules, ruleHost),
                        opens = coveredWebOpens(rules, ruleHost),
                        session = coveredWebSession(query, ruleHost),
                        exhausted = limit.targetKey in query.exhausted,
                        warnings = warnings,
                        cooldowns = cooldowns,
                        clearCeilings = clearCeilings,
                        exhaust = exhaust,
                    )?.let { return it }
                }
            }
        }

        val warn = warnings.minByOrNull { it.minutes }
        return Evaluation(
            decision = BlockDecision.allow(warn),
            cooldownsToSet = cooldowns,
            ceilingsToClear = clearCeilings,
            exhaust = exhaust,
            warnings = warnings,
        )
    }

    private fun instant(rules: RulesSnapshot, pkg: String, domain: String?): BlockDecision? {
        val now = rules.now
        val appBlock = rules.blocks.find { it.targetKey == pkg || it.targetKey == LimitKeys.app(pkg) }
        if (appBlock != null && appBlock.untilMillis > now) {
            val key = LimitKeys.app(pkg)
            if (!bypasses(rules, key, BlockReason.INSTANT)) {
                return hard(rules, BlockReason.INSTANT, key, SubjectKind.APP, labelOf(rules, pkg), appBlock.untilMillis)
            }
        }
        if (domain != null) {
            val webBlock = rules.blocks
                .filter { it.targetKey.startsWith("web:") && it.untilMillis > now }
                .filter { Domains.covers(domain, LimitKeys.payload(it.targetKey)) }
                .maxByOrNull { LimitKeys.payload(it.targetKey).length }
            if (webBlock != null && !bypasses(rules, webBlock.targetKey, BlockReason.INSTANT)) {
                val host = LimitKeys.payload(webBlock.targetKey)
                return hard(rules, BlockReason.INSTANT, webBlock.targetKey, SubjectKind.WEB, host, webBlock.untilMillis)
            }
        }
        return null
    }

    private fun hard(
        rules: RulesSnapshot,
        reason: BlockReason,
        key: String,
        kind: SubjectKind,
        subject: String,
        until: Long?,
    ): BlockDecision {
        val grant = rules.extras[key]
        val already = grant != null && grant.dayStart == rules.dayStart
        return BlockDecision(
            blocked = true,
            reason = reason,
            subjectKind = kind,
            subject = subject,
            targetKey = key,
            untilMillis = until,
            allowExtra = rules.extraEnabled && !already,
        )
    }

    private fun considerLimit(
        rules: RulesSnapshot,
        limit: UsageLimit?,
        key: String,
        kind: SubjectKind,
        subject: String,
        usage: Long,
        opens: Int,
        session: Long,
        exhausted: Boolean,
        warnings: MutableList<Warn>,
        cooldowns: MutableList<Pair<String, Long>>,
        clearCeilings: MutableList<String>,
        exhaust: MutableList<String>,
    ): Evaluation? {
        if (limit == null) return null
        val grant = rules.extras[key]
        val todayGrant = grant?.takeIf { it.dayStart == rules.dayStart }

        val cooldownUntil = rules.cooldowns[key] ?: 0L
        if (cooldownUntil > rules.now && !bypasses(rules, key, BlockReason.COOLDOWN)) {
            return Evaluation(
                decision = hard(rules, BlockReason.COOLDOWN, key, kind, subject, cooldownUntil),
                cooldownsToSet = cooldowns,
                ceilingsToClear = clearCeilings,
                exhaust = exhaust,
                warnings = warnings,
            )
        }

        if (limit.sessionLimitMillis > 0L) {
            val ceiling = todayGrant?.sessionCeilingMillis ?: 0L
            val allowance = maxOf(limit.sessionLimitMillis, ceiling)
            val over = exhausted || session >= allowance
            if (over && !bypasses(rules, key, BlockReason.SESSION)) {
                if (limit.cooldownMillis > 0L) {
                    val until = rules.now + limit.cooldownMillis
                    cooldowns += key to until
                    clearCeilings += key
                    return Evaluation(
                        decision = hard(rules, BlockReason.COOLDOWN, key, kind, subject, until),
                        cooldownsToSet = cooldowns,
                        ceilingsToClear = clearCeilings,
                        exhaust = exhaust,
                        warnings = warnings,
                    )
                }
                exhaust += key
                return Evaluation(
                    decision = hard(rules, BlockReason.SESSION, key, kind, subject, null),
                    cooldownsToSet = cooldowns,
                    ceilingsToClear = clearCeilings,
                    exhaust = exhaust,
                    warnings = warnings,
                )
            }
            val remain = allowance - session
            if (remain in 1..FIVE_MIN) warnings += Warn(key, subject, if (remain <= ONE_MIN) 1 else 5)
        }

        if (limit.openLimit > 0 && opens > limit.openLimit && !bypasses(rules, key, BlockReason.OPENS)) {
            return Evaluation(
                decision = hard(rules, BlockReason.OPENS, key, kind, subject, rules.dayEnd),
                cooldownsToSet = cooldowns,
                ceilingsToClear = clearCeilings,
                exhaust = exhaust,
                warnings = warnings,
            )
        }

        if (limit.dailyLimitMillis > 0L && appliesToday(rules, limit.weekdaysMask)) {
            val allowance = limit.dailyLimitMillis + (todayGrant?.bonusMillis ?: 0L)
            if (usage >= allowance && !bypasses(rules, key, BlockReason.DAILY)) {
                return Evaluation(
                    decision = hard(rules, BlockReason.DAILY, key, kind, subject, rules.dayEnd),
                    cooldownsToSet = cooldowns,
                    ceilingsToClear = clearCeilings,
                    exhaust = exhaust,
                    warnings = warnings,
                )
            }
            val remain = allowance - usage
            if (remain in 1..FIVE_MIN) warnings += Warn(key, subject, if (remain <= ONE_MIN) 1 else 5)
        }
        return null
    }

    private fun bypasses(rules: RulesSnapshot, key: String, reason: BlockReason): Boolean {
        val grant = rules.extras[key] ?: return false
        if (grant.dayStart != rules.dayStart) return false
        if (grant.reason != reason.name) return false
        return grant.bypassUntil > rules.now
    }

    private fun appliesToday(rules: RulesSnapshot, mask: Int): Boolean {
        val day = Instant.ofEpochMilli(rules.now).atZone(rules.zone).dayOfWeek
        return Weekdays.contains(mask, day)
    }

    private fun opensOf(rules: RulesSnapshot, key: String, engineKey: String): Int {
        val tracked = rules.opens[key] ?: 0
        val engine = rules.engineOpens[engineKey] ?: rules.engineOpens[key] ?: 0
        return maxOf(tracked, engine)
    }

    private fun labelOf(rules: RulesSnapshot, pkg: String): String = rules.appLabels[pkg] ?: pkg

    private fun coveredWebUsage(rules: RulesSnapshot, ruleHost: String): Long {
        var sum = 0L
        for ((host, millis) in rules.webUsage) {
            if (Domains.covers(host, ruleHost)) sum += millis
        }
        return sum
    }

    private fun coveredWebOpens(rules: RulesSnapshot, ruleHost: String): Int {
        var sum = 0
        for ((key, count) in rules.opens) {
            if (LimitKeys.isWeb(key) && Domains.covers(LimitKeys.payload(key), ruleHost)) sum += count
        }
        return sum
    }

    private fun coveredWebSession(query: ForegroundQuery, ruleHost: String): Long {
        var sum = 0L
        for ((key, millis) in query.sessionMillis) {
            if (LimitKeys.isWeb(key) && Domains.covers(LimitKeys.payload(key), ruleHost)) sum += millis
        }
        return sum
    }
}
