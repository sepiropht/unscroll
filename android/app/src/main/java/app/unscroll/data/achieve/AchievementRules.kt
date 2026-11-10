package app.unscroll.data.achieve

import java.time.LocalDate

/**
 * Badges locaux. Une journée compte lorsqu’elle est terminée : aujourd’hui
 * alimente la série affichée, et le badge est posé au lendemain si la journée
 * est restée dans l’objectif. Un badge déjà posé n’est pas retiré.
 */
object AchievementRules {
    const val LIGHT_MILLIS = 2L * 60L * 60L * 1000L
    const val FOCUS_TARGET = 10
    val GOAL_MINUTES = intArrayOf(60, 90, 120, 180, 240, 300, 360)
    const val DEFAULT_GOAL_MINUTES = 180

    const val FIRST_GOAL = "first_goal"
    const val STREAK_3 = "streak_3"
    const val STREAK_7 = "streak_7"
    const val STREAK_30 = "streak_30"
    const val LIGHT_DAY = "light_day"
    const val CLEAR_DAY = "clear_day"
    const val FOCUS_10 = "focus_10"

    val ALL = listOf(
        FIRST_GOAL,
        STREAK_3,
        STREAK_7,
        STREAK_30,
        LIGHT_DAY,
        CLEAR_DAY,
        FOCUS_10,
    )

    fun evaluate(input: AchievementInput): AchievementResult {
        val goal = input.goalMillis.coerceAtLeast(1L)
        val todayRow = input.days.firstOrNull { it.date == input.today }
        val todayMillis = todayRow?.durationMillis ?: 0L
        val todayObserved = todayRow?.known == true
        val todayUnder = todayObserved && todayMillis <= goal
        val finishedStreak = finishedStreak(input.today, input.days, goal)
        val best = bestFinished(input.today, input.days, goal)
        val live = finishedStreak + if (todayUnder) 1 else 0
        val focus = input.focusSessions.coerceAtLeast(0)

        val earned = LinkedHashSet<String>()
        if (input.days.any { it.date < input.today && it.known && it.durationMillis <= goal }) {
            earned += FIRST_GOAL
        }
        if (best >= 3) earned += STREAK_3
        if (best >= 7) earned += STREAK_7
        if (best >= 30) earned += STREAK_30
        if (input.days.any { it.date < input.today && it.known && it.durationMillis < LIGHT_MILLIS }) {
            earned += LIGHT_DAY
        }
        if (clearDay(input)) earned += CLEAR_DAY
        if (focus >= FOCUS_TARGET) earned += FOCUS_10

        val newly = earned.filter { it !in input.unlocked }.toSet()
        val badges = ALL.map { key ->
            val at = input.unlocked[key] ?: if (key in earned) input.now else null
            BadgeProgress(
                key = key,
                unlocked = at != null,
                unlockedAt = at,
                progress = progress(key, finishedStreak, focus),
                target = target(key),
            )
        }
        return AchievementResult(
            goalMillis = goal,
            todayMillis = todayMillis,
            todayObserved = todayObserved,
            todayUnder = todayUnder,
            finishedStreak = finishedStreak,
            liveStreak = live,
            bestStreak = best,
            focusSessions = focus,
            todayGuard = input.guards[todayRow?.dayStart ?: Long.MIN_VALUE],
            badges = badges,
            newlyUnlocked = newly,
        )
    }

    private fun progress(key: String, finishedStreak: Int, focus: Int): Int = when (key) {
        STREAK_3 -> finishedStreak
        STREAK_7 -> finishedStreak
        STREAK_30 -> finishedStreak
        FOCUS_10 -> focus
        else -> 0
    }

    private fun target(key: String): Int = when (key) {
        STREAK_3 -> 3
        STREAK_7 -> 7
        STREAK_30 -> 30
        FOCUS_10 -> FOCUS_TARGET
        else -> 1
    }

    private fun clearDay(input: AchievementInput): Boolean {
        val byStart = input.days.associateBy { it.dayStart }
        return input.guards.any { (dayStart, mark) ->
            val day = byStart[dayStart] ?: return@any false
            day.date < input.today && mark.armed && mark.launches == 0
        }
    }

    private fun finishedStreak(today: LocalDate, days: List<UsageDay>, goal: Long): Int {
        val byDate = days.associateBy { it.date }
        var streak = 0
        var cursor = today.minusDays(1)
        while (streak < 400) {
            val day = byDate[cursor] ?: break
            if (!day.known || day.durationMillis > goal) break
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun bestFinished(today: LocalDate, days: List<UsageDay>, goal: Long): Int {
        val finished = days.filter { it.date < today }.sortedBy { it.date }
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in finished) {
            val ok = day.known && day.durationMillis <= goal
            run = when {
                !ok -> 0
                previous != null && day.date == previous.plusDays(1) && run > 0 -> run + 1
                else -> 1
            }
            if (run > best) best = run
            previous = day.date
        }
        return best
    }
}

data class UsageDay(
    val date: LocalDate,
    val dayStart: Long,
    /** Vrai lorsqu’une lecture existe, même si la durée est nulle. */
    val known: Boolean,
    val durationMillis: Long,
)

data class GuardMark(
    val launches: Int,
    val armed: Boolean,
)

data class AchievementInput(
    val today: LocalDate,
    val goalMillis: Long,
    val days: List<UsageDay>,
    val guards: Map<Long, GuardMark>,
    val focusSessions: Int,
    val unlocked: Map<String, Long>,
    val now: Long,
)

data class BadgeProgress(
    val key: String,
    val unlocked: Boolean,
    val unlockedAt: Long?,
    val progress: Int,
    val target: Int,
)

data class AchievementResult(
    val goalMillis: Long,
    val todayMillis: Long,
    val todayObserved: Boolean,
    val todayUnder: Boolean,
    val finishedStreak: Int,
    val liveStreak: Int,
    val bestStreak: Int,
    val focusSessions: Int,
    val todayGuard: GuardMark?,
    val badges: List<BadgeProgress>,
    val newlyUnlocked: Set<String>,
)
