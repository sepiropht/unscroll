package app.unscroll.data.stats

import java.time.LocalDate

/** Une journée locale, déjà découpée par heure. */
data class DaySlice(
    val dayStart: Long,
    val date: LocalDate,
    val totalMillis: Long,
    val unlockCount: Int,
    val hourly: List<Long>,
    val packages: List<PkgSlice>,
    val sessions: List<SessionSlice>,
    val eventCount: Int,
) {
    companion object {
        fun empty(date: LocalDate, dayStart: Long) = DaySlice(
            dayStart = dayStart,
            date = date,
            totalMillis = 0L,
            unlockCount = 0,
            hourly = List(24) { 0L },
            packages = emptyList(),
            sessions = emptyList(),
            eventCount = 0,
        )
    }
}

data class PkgSlice(
    val packageName: String,
    val durationMillis: Long,
    val openCount: Int,
    val hourly: List<Long>,
)

data class SessionSlice(
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long,
)

enum class PeriodKind { DAY, WEEK, MONTH }

object HourlyCodec {
    fun encode(values: List<Long>): String {
        val normalized = List(24) { index -> values.getOrElse(index) { 0L } }
        return normalized.joinToString(",")
    }

    fun decode(csv: String): List<Long> {
        if (csv.isBlank()) return List(24) { 0L }
        val parts = csv.split(',')
        return List(24) { index -> parts.getOrNull(index)?.toLongOrNull() ?: 0L }
    }
}
