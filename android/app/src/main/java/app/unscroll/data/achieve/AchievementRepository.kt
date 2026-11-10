package app.unscroll.data.achieve

import android.content.Context
import app.unscroll.data.repo.UsageRepository
import app.unscroll.data.stats.excluding
import app.unscroll.permissions.PermissionChecks
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AchievementRepository(context: Context) {
    private val app = context.applicationContext
    private val usage = UsageRepository(app)
    private val store = AchievementStore.get(app)

    suspend fun load(): AchievementResult {
        val now = System.currentTimeMillis()
        store.settleDue(now)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val excluded = usage.statsExclusions()
        val access = PermissionChecks.hasUsageAccess(app)
        val recentFrom = today.minusDays(RECENT_DAYS - 1)
        val fresh = usage.loadDays(today.minusDays(LOOKBACK_DAYS), today.plusDays(1)).map { slice ->
            val visible = slice.excluding(excluded)
            val activity = visible.eventCount > 0 || visible.totalMillis > 0L || visible.unlockCount > 0
            UsageDay(
                date = visible.date,
                dayStart = visible.dayStart,
                known = activity || (access && !visible.date.isBefore(recentFrom)),
                durationMillis = visible.totalMillis.coerceAtLeast(0L),
            )
        }
        store.rememberUsage(fresh)
        val days = store.usageHistory().map { (start, duration) ->
            UsageDay(
                date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate(),
                dayStart = start,
                known = true,
                durationMillis = duration,
            )
        }
        val result = AchievementRules.evaluate(
            AchievementInput(
                today = today,
                goalMillis = store.goalMillis,
                days = days,
                guards = store.guardMarks(),
                focusSessions = store.focusSessions,
                unlocked = store.unlocks(),
                now = now,
            ),
        )
        store.remember(result.newlyUnlocked, now)
        return result
    }

    fun setGoalMinutes(minutes: Int) {
        if (minutes !in AchievementRules.GOAL_MINUTES) return
        store.goalMillis = minutes * 60_000L
    }

    private companion object {
        const val LOOKBACK_DAYS = 120L
        const val RECENT_DAYS = 7L
    }
}
