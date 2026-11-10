package app.unscroll.data.stats

import java.time.DayOfWeek

enum class AdviceId {
    EMPTY,
    CONCENTRATED,
    RISING,
    FALLING,
    LATE,
    UNLOCKS,
    LIGHT,
    WEEKEND,
    STEADY,
}

/**
 * Choisit jusqu'à trois conseils à partir des chiffres de la semaine,
 * sans texte figé : l'écran résout les phrases.
 */
fun selectWeeklyAdvice(
    thisWeekMillis: Long,
    previousSameSpanMillis: Long,
    topAppFraction: Float,
    peakHour: Int?,
    averageDailyMillis: Long,
    averageUnlocks: Double,
    busiestDay: DayOfWeek?,
): List<AdviceId> {
    if (thisWeekMillis <= 0L) return listOf(AdviceId.EMPTY)
    val picked = ArrayList<AdviceId>(3)
    if (topAppFraction >= 0.40f) picked += AdviceId.CONCENTRATED
    if (previousSameSpanMillis > 0L && thisWeekMillis > previousSameSpanMillis * 1.2) {
        picked += AdviceId.RISING
    } else if (previousSameSpanMillis > 0L && thisWeekMillis < previousSameSpanMillis * 0.8) {
        picked += AdviceId.FALLING
    }
    if (peakHour != null && (peakHour >= 22 || peakHour < 6)) picked += AdviceId.LATE
    if (averageUnlocks >= 60.0) picked += AdviceId.UNLOCKS
    if (averageDailyMillis in 1 until TWO_HOURS) picked += AdviceId.LIGHT
    if (busiestDay == DayOfWeek.SATURDAY || busiestDay == DayOfWeek.SUNDAY) {
        picked += AdviceId.WEEKEND
    }
    if (picked.isEmpty()) picked += AdviceId.STEADY
    return picked.take(3)
}

private const val TWO_HOURS = 2L * 60L * 60L * 1000L
