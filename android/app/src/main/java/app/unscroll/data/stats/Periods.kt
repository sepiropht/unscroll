package app.unscroll.data.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Semaine civile. Le premier jour vient des réglages (lundi par défaut). */
object Periods {
    fun range(anchor: LocalDate, kind: PeriodKind, weekStart: DayOfWeek): Pair<LocalDate, LocalDate> {
        return when (kind) {
            PeriodKind.DAY -> anchor to anchor.plusDays(1)
            PeriodKind.WEEK -> {
                val start = anchor.with(TemporalAdjusters.previousOrSame(weekStart))
                start to start.plusWeeks(1)
            }
            PeriodKind.MONTH -> {
                val start = anchor.withDayOfMonth(1)
                start to start.plusMonths(1)
            }
        }
    }

    /** Fenêtre à charger : période affichée + période de comparaison. */
    fun loadSpan(anchor: LocalDate, kind: PeriodKind, weekStart: DayOfWeek): Pair<LocalDate, LocalDate> {
        val (start, end) = range(anchor, kind, weekStart)
        val from = when (kind) {
            PeriodKind.DAY -> anchor.minusDays(7)
            PeriodKind.WEEK -> start.minusWeeks(1)
            PeriodKind.MONTH -> start.minusMonths(1)
        }
        return from to end
    }

    fun shift(anchor: LocalDate, kind: PeriodKind, by: Int, today: LocalDate): LocalDate {
        val moved = when (kind) {
            PeriodKind.DAY -> anchor.plusDays(by.toLong())
            PeriodKind.WEEK -> anchor.plusWeeks(by.toLong())
            PeriodKind.MONTH -> anchor.plusMonths(by.toLong())
        }
        return if (moved.isAfter(today)) today else moved
    }

    fun canGoForward(anchor: LocalDate, kind: PeriodKind, today: LocalDate, weekStart: DayOfWeek): Boolean {
        val (start, _) = range(anchor, kind, weekStart)
        val next = when (kind) {
            PeriodKind.DAY -> start.plusDays(1)
            PeriodKind.WEEK -> start.plusWeeks(1)
            PeriodKind.MONTH -> start.plusMonths(1)
        }
        return !next.isAfter(today)
    }
}
