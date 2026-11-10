package app.unscroll.data.guard

import app.unscroll.data.db.BlockSchedule
import app.unscroll.data.db.ExtraGrant
import app.unscroll.data.db.FocusRoutine
import app.unscroll.data.db.InstantBlock
import app.unscroll.data.db.ScheduleKinds
import app.unscroll.data.db.UsageLimit
import app.unscroll.data.stats.LimitKeys
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockEvaluatorTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val monday: LocalDate = LocalDate.of(2026, 1, 5)

    @Test
    fun passesTheAppItselfLaunchersAndSettings() {
        val limit = daily(LimitKeys.app(PKG), 1 * MIN)
        val over = mapOf(PKG to 5 * MIN)
        assertFalse(eval(rules(limits = listOf(limit), appUsage = over), query(SELF)).decision.blocked)
        assertFalse(eval(rules(limits = listOf(limit), appUsage = over), query("")).decision.blocked)
        assertFalse(eval(rules(limits = listOf(limit), appUsage = over), query(isLauncher = true)).decision.blocked)
        assertFalse(eval(rules(limits = listOf(limit), appUsage = over), query("com.android.launcher3")).decision.blocked)
        assertFalse(eval(rules(limits = listOf(limit), appUsage = over), query("com.android.settings")).decision.blocked)
        assertFalse(eval(rules(), query()).decision.blocked)
    }

    @Test
    fun dailyLimitBlocksUntilTheDayEnds() {
        val now = clock(monday, 10)
        val rules = rules(
            now = now,
            limits = listOf(daily(LimitKeys.app(PKG), 30 * MIN)),
            appUsage = mapOf(PKG to 30 * MIN),
        )
        val decision = eval(rules, query()).decision
        assertTrue(decision.blocked)
        assertEquals(BlockReason.DAILY, decision.reason)
        assertEquals(SubjectKind.APP, decision.subjectKind)
        assertEquals("Notes", decision.subject)
        assertEquals(LimitKeys.app(PKG), decision.targetKey)
        assertEquals(rules.dayEnd, decision.untilMillis)
        assertTrue(decision.allowExtra)
    }

    @Test
    fun dailyLimitFollowsTheWeekdayMaskAndWarnsNearTheEnd() {
        val limit = UsageLimit(
            targetKey = LimitKeys.app(PKG),
            dailyLimitMillis = 30 * MIN,
            weekdaysMask = Weekdays.bit(DayOfWeek.TUESDAY),
        )
        val over = mapOf(PKG to 30 * MIN)
        assertFalse(eval(rules(limits = listOf(limit), appUsage = over), query()).decision.blocked)
        val tuesday = rules(now = clock(monday.plusDays(1), 10), limits = listOf(limit), appUsage = over)
        assertEquals(BlockReason.DAILY, eval(tuesday, query()).decision.reason)

        val almost = 30 * MIN - FIVE
        val warn = eval(rules(limits = listOf(daily(LimitKeys.app(PKG), 30 * MIN)), appUsage = mapOf(PKG to almost)), query())
        assertFalse(warn.decision.blocked)
        assertEquals(5, warn.decision.warn?.minutes)
        val justUnder = almost - 1
        val quiet = eval(
            rules(limits = listOf(daily(LimitKeys.app(PKG), 30 * MIN)), appUsage = mapOf(PKG to justUnder)),
            query(),
        )
        assertNull(quiet.decision.warn)
        val lastMinute = eval(
            rules(
                limits = listOf(daily(LimitKeys.app(PKG), 30 * MIN)),
                appUsage = mapOf(PKG to 30 * MIN - 30_000),
            ),
            query(),
        )
        assertEquals(1, lastMinute.decision.warn?.minutes)
    }

    @Test
    fun bonusAndYesterdaysGrantChangeTheDailyCeiling() {
        val limit = daily(LimitKeys.app(PKG), 30 * MIN)
        val key = LimitKeys.app(PKG)
        val today = rules(limits = listOf(limit), appUsage = mapOf(PKG to 30 * MIN))
        val withBonus = today.copy(
            extras = mapOf(key to grant(today.dayStart, key, BlockReason.DAILY, bonus = FIVE)),
        )
        assertFalse(eval(withBonus, query()).decision.blocked)
        val spent = withBonus.copy(appUsage = mapOf(PKG to 35 * MIN))
        val blocked = eval(spent, query()).decision
        assertEquals(BlockReason.DAILY, blocked.reason)
        assertFalse(blocked.allowExtra)

        val yesterday = today.dayStart - 24 * 60 * MIN
        val stale = today.copy(
            extras = mapOf(key to grant(yesterday, key, BlockReason.DAILY, bonus = FIVE, bypassUntil = today.now + MIN)),
        )
        val again = eval(stale, query()).decision
        assertEquals(BlockReason.DAILY, again.reason)
        assertTrue(again.allowExtra)
    }

    @Test
    fun pauseHoldsDailyAndInstantBlocksButNotAScheduleOrFocus() {
        val now = clock(monday, 10)
        val paused = rules(
            now = now,
            pauseUntil = now + MIN,
            limits = listOf(daily(LimitKeys.app(PKG), MIN)),
            appUsage = mapOf(PKG to 5 * MIN),
            blocks = listOf(InstantBlock(PKG, now + 15 * MIN, now)),
            schedules = listOf(window(9 * 60, 12 * 60)),
        )
        assertEquals(BlockReason.SCHEDULE, eval(paused, query()).decision.reason)
        val withoutSchedule = paused.copy(schedules = emptyList(), focusUntil = now + 25 * MIN, focusPackages = setOf(PKG))
        assertEquals(BlockReason.FOCUS, eval(withoutSchedule, query()).decision.reason)
        val onlyCaps = withoutSchedule.copy(focusUntil = 0L, focusPackages = emptySet())
        assertFalse(eval(onlyCaps, query()).decision.blocked)
    }

    @Test
    fun scheduleWindowBlocksUntilItEndsIncludingOvernight() {
        val morning = rules(
            now = clock(monday, 10),
            schedules = listOf(window(9 * 60, 12 * 60, name = "Cours")),
        )
        val hit = eval(morning, query()).decision
        assertEquals(BlockReason.SCHEDULE, hit.reason)
        assertEquals("Cours", hit.scheduleName)
        assertEquals(clock(monday, 12), hit.untilMillis)
        assertEquals(SubjectKind.APP, hit.subjectKind)
        assertFalse(eval(rules(now = clock(monday, 13), schedules = listOf(window(9 * 60, 12 * 60))), query()).decision.blocked)
        assertFalse(
            eval(
                rules(schedules = listOf(window(9 * 60, 12 * 60).copy(enabled = false))),
                query(),
            ).decision.blocked,
        )
        assertFalse(eval(rules(schedules = listOf(window(10 * 60, 10 * 60))), query()).decision.blocked)

        val night = window(22 * 60, 7 * 60, mask = Weekdays.bit(DayOfWeek.MONDAY), name = "Nuit")
        val late = eval(rules(now = clock(monday, 23), schedules = listOf(night)), query()).decision
        assertEquals(BlockReason.SCHEDULE, late.reason)
        assertEquals(clock(monday.plusDays(1), 7), late.untilMillis)
        val early = eval(rules(now = clock(monday.plusDays(1), 6), schedules = listOf(night)), query()).decision
        assertEquals(BlockReason.SCHEDULE, early.reason)
        assertEquals(clock(monday.plusDays(1), 7), early.untilMillis)
        assertFalse(eval(rules(now = clock(monday.plusDays(1), 8), schedules = listOf(night)), query()).decision.blocked)
        assertFalse(eval(rules(now = clock(monday.plusDays(1), 23), schedules = listOf(night)), query()).decision.blocked)
    }

    @Test
    fun scheduleCanBlockASiteOrPreferTheApp() {
        val browser = "com.android.chrome"
        val siteOnly = window(9 * 60, 12 * 60, packages = "", domains = "example.com")
        val web = eval(rules(schedules = listOf(siteOnly)), query(browser, domain = "a.example.com")).decision
        assertEquals(BlockReason.SCHEDULE, web.reason)
        assertEquals(SubjectKind.WEB, web.subjectKind)
        assertEquals("example.com", web.subject)
        assertEquals(LimitKeys.web("example.com"), web.targetKey)

        val both = window(9 * 60, 12 * 60, packages = browser, domains = "example.com")
        val app = eval(rules(schedules = listOf(both)), query(browser, domain = "a.example.com")).decision
        assertEquals(SubjectKind.APP, app.subjectKind)
        assertEquals(LimitKeys.app(browser), app.targetKey)
    }

    @Test
    fun sleepBlocksEverythingOutsideTheWhitelist() {
        val sleep = BlockSchedule(
            id = 1,
            name = "Sommeil",
            enabled = true,
            startMinute = 22 * 60,
            endMinute = 7 * 60,
            weekdaysMask = Weekdays.ALL,
            packagesCsv = "com.android.dialer,com.android.deskclock",
            domainsCsv = "",
            kind = ScheduleKinds.SLEEP,
        )
        val night = rules(now = clock(monday, 23), schedules = listOf(sleep))
        val blocked = eval(night, query()).decision
        assertEquals(BlockReason.SLEEP, blocked.reason)
        assertEquals("", blocked.scheduleName)
        assertEquals(clock(monday.plusDays(1), 7), blocked.untilMillis)
        assertFalse(eval(night, query("com.android.dialer")).decision.blocked)
        assertFalse(eval(night.copy(schedules = listOf(sleep.copy(enabled = false))), query()).decision.blocked)
    }

    @Test
    fun focusAndRoutineBlockEvenWhenTheDailyCapIsFine() {
        val now = clock(monday, 9, 30)
        val focus = rules(now = now, focusUntil = now + 25 * MIN, focusPackages = setOf(PKG))
        val decision = eval(focus, query()).decision
        assertEquals(BlockReason.FOCUS, decision.reason)
        assertEquals(now + 25 * MIN, decision.untilMillis)

        val browser = "com.android.chrome"
        val onSite = focus.copy(focusPackages = emptySet(), focusDomains = setOf("example.com"))
        val web = eval(onSite, query(browser, domain = "news.example.com")).decision
        assertEquals(SubjectKind.WEB, web.subjectKind)
        assertEquals("example.com", web.subject)
        val appWins = eval(
            onSite.copy(focusPackages = setOf(browser)),
            query(browser, domain = "news.example.com"),
        ).decision
        assertEquals(SubjectKind.APP, appWins.subjectKind)

        val routine = FocusRoutine(
            id = 7,
            name = "Matin",
            enabled = true,
            startMinute = 9 * 60,
            durationMinutes = 50,
            weekdaysMask = Weekdays.ALL,
            packagesCsv = " $PKG , com.other ",
        )
        val active = rules(now = now, routines = listOf(routine))
        val hit = eval(active, query()).decision
        assertEquals(BlockReason.FOCUS, hit.reason)
        assertEquals("Matin", hit.scheduleName)
        assertEquals(clock(monday, 9, 50), hit.untilMillis)
        assertFalse(eval(active.copy(routines = listOf(routine.copy(enabled = false))), query()).decision.blocked)
        assertFalse(
            eval(active.copy(routineSkips = mapOf(7L to now + 1_000)), query()).decision.blocked,
        )
        assertFalse(eval(active, query("com.other.app")).decision.blocked)
    }

    @Test
    fun sessionLimitStartsACooldownOrStaysOpen() {
        val key = LimitKeys.app(PKG)
        val limit = UsageLimit(
            targetKey = key,
            sessionLimitMillis = 10 * MIN,
            cooldownMillis = 5 * MIN,
        )
        val now = clock(monday, 10)
        val rules = rules(now = now, limits = listOf(limit))
        val hit = eval(rules, query(sessionMillis = mapOf(key to 10 * MIN)))
        assertEquals(BlockReason.COOLDOWN, hit.decision.reason)
        assertEquals(now + 5 * MIN, hit.decision.untilMillis)
        assertEquals(listOf(key to (now + 5 * MIN)), hit.cooldownsToSet)
        assertEquals(listOf(key), hit.ceilingsToClear)

        val open = UsageLimit(targetKey = key, sessionLimitMillis = 10 * MIN, cooldownMillis = 0)
        val stayed = eval(rules(now = now, limits = listOf(open)), query(sessionMillis = mapOf(key to 10 * MIN)))
        assertEquals(BlockReason.SESSION, stayed.decision.reason)
        assertNull(stayed.decision.untilMillis)
        assertEquals(listOf(key), stayed.exhaust)

        val cooling = rules.copy(cooldowns = mapOf(key to now + MIN))
        val held = eval(cooling, query(sessionMillis = mapOf(key to 0L)))
        assertEquals(BlockReason.COOLDOWN, held.decision.reason)
        assertEquals(now + MIN, held.decision.untilMillis)
        assertTrue(held.cooldownsToSet.isEmpty())

        val under = eval(rules, query(sessionMillis = mapOf(key to 5 * MIN)))
        assertFalse(under.decision.blocked)
        assertEquals(5, under.decision.warn?.minutes)
        val exactEdge = eval(rules, query(sessionMillis = mapOf(key to 10 * MIN - FIVE - 1)))
        assertNull(exactEdge.decision.warn)
    }

    @Test
    fun sessionBypassDoesNotHideADailyLimit() {
        val key = LimitKeys.app(PKG)
        val now = clock(monday, 10)
        val base = rules(
            now = now,
            limits = listOf(
                UsageLimit(
                    targetKey = key,
                    dailyLimitMillis = 30 * MIN,
                    sessionLimitMillis = 10 * MIN,
                    cooldownMillis = 0,
                ),
            ),
            appUsage = mapOf(PKG to 30 * MIN),
        )
        val bypassed = base.copy(
            extras = mapOf(key to grant(base.dayStart, key, BlockReason.SESSION, bypassUntil = now + MIN)),
        )
        assertEquals(BlockReason.DAILY, eval(bypassed, query(sessionMillis = mapOf(key to 10 * MIN))).decision.reason)
        assertEquals(BlockReason.SESSION, eval(base, query(sessionMillis = mapOf(key to 10 * MIN))).decision.reason)
    }

    @Test
    fun openLimitUsesTheHigherCountAndAllowsExactlyThatMany() {
        val key = LimitKeys.app(PKG)
        val limit = UsageLimit(targetKey = key, openLimit = 3)
        assertFalse(eval(rules(limits = listOf(limit), opens = mapOf(key to 3)), query()).decision.blocked)
        val over = eval(rules(limits = listOf(limit), opens = mapOf(key to 4)), query()).decision
        assertEquals(BlockReason.OPENS, over.reason)
        assertEquals(rules(limits = listOf(limit)).dayEnd, over.untilMillis)
        val fromEngine = eval(rules(limits = listOf(limit), engineOpens = mapOf(PKG to 4)), query()).decision
        assertEquals(BlockReason.OPENS, fromEngine.reason)
        assertFalse(eval(rules(limits = listOf(limit.copy(openLimit = 0)), opens = mapOf(key to 40)), query()).decision.blocked)
    }

    @Test
    fun instantBlockMatchesEitherKeyAndACoveredDomain() {
        val now = clock(monday, 10)
        val app = eval(
            rules(now = now, blocks = listOf(InstantBlock(LimitKeys.app(PKG), now + 15 * MIN, now))),
            query(),
        ).decision
        assertEquals(BlockReason.INSTANT, app.reason)
        assertEquals(now + 15 * MIN, app.untilMillis)
        val plain = eval(
            rules(now = now, blocks = listOf(InstantBlock(PKG, Long.MAX_VALUE, now))),
            query(),
        ).decision
        assertEquals(Long.MAX_VALUE, plain.untilMillis)
        assertFalse(
            eval(rules(now = now, blocks = listOf(InstantBlock(PKG, now, now))), query()).decision.blocked,
        )
        val web = eval(
            rules(
                now = now,
                blocks = listOf(
                    InstantBlock("web:example.com", now + MIN, now),
                    InstantBlock("web:a.example.com", now + 2 * MIN, now),
                ),
            ),
            query("com.android.chrome", domain = "a.example.com"),
        ).decision
        assertEquals(SubjectKind.WEB, web.subjectKind)
        assertEquals("a.example.com", web.subject)
        assertEquals(now + 2 * MIN, web.untilMillis)
    }

    @Test
    fun liveTailIsCappedAndCountsTowardTheApp() {
        val limit = daily(LimitKeys.app(PKG), 30 * MIN)
        val crossed = eval(
            rules(limits = listOf(limit), appUsage = mapOf(PKG to 30 * MIN - 1_000)),
            query(liveTailMillis = 100_000),
        ).decision
        assertEquals(BlockReason.DAILY, crossed.reason)
        val warned = eval(
            rules(limits = listOf(limit), appUsage = mapOf(PKG to 30 * MIN - 9_000)),
            query(liveTailMillis = 100_000),
        )
        assertFalse(warned.decision.blocked)
        assertEquals(1, warned.decision.warn?.minutes)
    }

    @Test
    fun categoryLimitSumsMembers() {
        val other = "com.example.video"
        val key = LimitKeys.category("social")
        val rules = rules(
            limits = listOf(daily(key, 30 * MIN)),
            appUsage = mapOf(PKG to 20 * MIN, other to 15 * MIN),
            categories = mapOf(PKG to "social", other to "social"),
            categoryLabels = mapOf("social" to "Social"),
        )
        val decision = eval(rules, query()).decision
        assertEquals(BlockReason.DAILY, decision.reason)
        assertEquals(SubjectKind.CATEGORY, decision.subjectKind)
        assertEquals("Social", decision.subject)
        assertEquals(key, decision.targetKey)
        val under = rules.copy(appUsage = mapOf(PKG to 10 * MIN, other to 10 * MIN))
        assertFalse(eval(under, query()).decision.blocked)
    }

    @Test
    fun webLimitPrefersTheLongestMatchingHost() {
        val broad = UsageLimit(targetKey = LimitKeys.web("example.com"), dailyLimitMillis = 10 * MIN)
        val narrow = UsageLimit(targetKey = LimitKeys.web("news.example.com"), dailyLimitMillis = 30 * MIN)
        val usage = mapOf(
            "news.example.com" to 5 * MIN,
            "example.com" to 8 * MIN,
            "other.com" to 100 * MIN,
        )
        val rules = rules(limits = listOf(broad, narrow), webUsage = usage)
        val parent = eval(rules, query("com.android.chrome", domain = "news.example.com")).decision
        assertEquals(BlockReason.DAILY, parent.reason)
        assertEquals("example.com", parent.subject)
        val tighter = rules.copy(webUsage = usage + ("news.example.com" to 30 * MIN))
        val specific = eval(tighter, query("com.android.chrome", domain = "news.example.com")).decision
        assertEquals("news.example.com", specific.subject)
    }

    @Test
    fun webSessionCooldownCoversSubdomains() {
        val key = LimitKeys.web("example.com")
        val now = clock(monday, 10)
        val rules = rules(
            now = now,
            limits = listOf(UsageLimit(targetKey = key, sessionLimitMillis = 5 * MIN, cooldownMillis = MIN)),
        )
        val hit = eval(
            rules,
            query(
                "com.android.chrome",
                domain = "a.example.com",
                sessionMillis = mapOf(LimitKeys.web("a.example.com") to 5 * MIN),
            ),
        )
        assertEquals(BlockReason.COOLDOWN, hit.decision.reason)
        assertEquals(key, hit.decision.targetKey)
        assertEquals(listOf(key), hit.ceilingsToClear)
    }

    @Test
    fun closestWarningWinsWhenNothingBlocks() {
        val key = LimitKeys.app(PKG)
        val rules = rules(
            limits = listOf(
                UsageLimit(
                    targetKey = key,
                    dailyLimitMillis = 30 * MIN,
                    sessionLimitMillis = 10 * MIN,
                ),
            ),
            appUsage = mapOf(PKG to 30 * MIN - 30_000),
        )
        val result = eval(rules, query(sessionMillis = mapOf(key to 6 * MIN)))
        assertFalse(result.decision.blocked)
        assertEquals(1, result.decision.warn?.minutes)
        assertEquals(setOf(5, 1), result.warnings.map { it.minutes }.toSet())
    }

    @Test
    fun extraMinutesCanBeTurnedOff() {
        val rules = rules(
            extraEnabled = false,
            limits = listOf(daily(LimitKeys.app(PKG), MIN)),
            appUsage = mapOf(PKG to MIN),
        )
        val decision = eval(rules, query()).decision
        assertEquals(BlockReason.DAILY, decision.reason)
        assertFalse(decision.allowExtra)
    }

    private fun eval(rules: RulesSnapshot, query: ForegroundQuery) = BlockEvaluator.evaluate(rules, query)

    private fun clock(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun daily(key: String, millis: Long) = UsageLimit(
        targetKey = key,
        dailyLimitMillis = millis,
        weekdaysMask = Weekdays.ALL,
    )

    private fun window(
        start: Int,
        end: Int,
        name: String = "Plage",
        mask: Int = Weekdays.ALL,
        packages: String = PKG,
        domains: String = "",
    ) = BlockSchedule(
        id = 1,
        name = name,
        enabled = true,
        startMinute = start,
        endMinute = end,
        weekdaysMask = mask,
        packagesCsv = packages,
        domainsCsv = domains,
        kind = ScheduleKinds.WINDOW,
    )

    private fun grant(
        dayStart: Long,
        key: String,
        reason: BlockReason,
        bonus: Long = 0L,
        bypassUntil: Long = 0L,
    ) = ExtraGrant(
        dayStart = dayStart,
        targetKey = key,
        reason = reason.name,
        bonusMillis = bonus,
        sessionCeilingMillis = 0L,
        bypassUntil = bypassUntil,
    )

    private fun rules(
        now: Long = clock(monday, 10),
        pauseUntil: Long = 0L,
        extraEnabled: Boolean = true,
        limits: List<UsageLimit> = emptyList(),
        blocks: List<InstantBlock> = emptyList(),
        schedules: List<BlockSchedule> = emptyList(),
        cooldowns: Map<String, Long> = emptyMap(),
        opens: Map<String, Int> = emptyMap(),
        engineOpens: Map<String, Int> = emptyMap(),
        extras: Map<String, ExtraGrant> = emptyMap(),
        appUsage: Map<String, Long> = emptyMap(),
        webUsage: Map<String, Long> = emptyMap(),
        categories: Map<String, String> = emptyMap(),
        categoryLabels: Map<String, String> = emptyMap(),
        focusUntil: Long = 0L,
        focusPackages: Set<String> = emptySet(),
        focusDomains: Set<String> = emptySet(),
        routines: List<FocusRoutine> = emptyList(),
        routineSkips: Map<Long, Long> = emptyMap(),
    ): RulesSnapshot {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return RulesSnapshot(
            now = now,
            zone = zone,
            dayStart = dayStart,
            dayEnd = dayEnd,
            pauseUntil = pauseUntil,
            extraEnabled = extraEnabled,
            limits = limits,
            blocks = blocks,
            schedules = schedules,
            cooldowns = cooldowns,
            opens = opens,
            engineOpens = engineOpens,
            extras = extras,
            appUsage = appUsage,
            usageAsOf = now,
            webUsage = webUsage,
            categories = categories,
            categoryLabels = categoryLabels,
            focusUntil = focusUntil,
            focusPackages = focusPackages,
            focusDomains = focusDomains,
            routines = routines,
            routineSkips = routineSkips,
            selfPackage = SELF,
            alwaysAllowed = setOf("com.android.settings"),
            launchers = setOf("com.android.launcher3"),
            appLabels = mapOf(PKG to "Notes"),
        )
    }

    private fun query(
        packageName: String = PKG,
        domain: String? = null,
        sessionMillis: Map<String, Long> = emptyMap(),
        exhausted: Set<String> = emptySet(),
        isLauncher: Boolean = false,
        liveTailMillis: Long = 0L,
    ) = ForegroundQuery(packageName, domain, sessionMillis, exhausted, isLauncher, liveTailMillis)

    private companion object {
        const val PKG = "com.example.notes"
        const val SELF = "app.unscroll"
        const val MIN = 60_000L
        const val FIVE = 5 * MIN
    }
}
