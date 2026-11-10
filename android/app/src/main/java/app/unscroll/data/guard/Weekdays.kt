package app.unscroll.data.guard

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Masque ISO : bit 0 = lundi … bit 6 = dimanche. */
object Weekdays {
    const val ALL: Int = 0b1111111

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun contains(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0

    fun toggle(mask: Int, day: DayOfWeek): Int = mask xor bit(day)

    fun coversAll(mask: Int): Boolean = mask and ALL == ALL

    fun any(mask: Int): Boolean = mask and ALL != 0
}

object ScheduleWindow {
    fun isActive(now: Long, zone: ZoneId, startMinute: Int, endMinute: Int, mask: Int): Boolean {
        if (startMinute == endMinute) return false
        val zdt = Instant.ofEpochMilli(now).atZone(zone)
        val minute = zdt.hour * 60 + zdt.minute
        return if (startMinute < endMinute) {
            Weekdays.contains(mask, zdt.dayOfWeek) && minute >= startMinute && minute < endMinute
        } else {
            val yesterday = zdt.dayOfWeek.minus(1)
            (Weekdays.contains(mask, zdt.dayOfWeek) && minute >= startMinute) ||
                (Weekdays.contains(mask, yesterday) && minute < endMinute)
        }
    }

    fun endMillis(now: Long, zone: ZoneId, startMinute: Int, endMinute: Int): Long {
        val zdt = Instant.ofEpochMilli(now).atZone(zone)
        val minute = zdt.hour * 60 + zdt.minute
        val endOn = { day: ZonedDateTime ->
            day.toLocalDate().atStartOfDay(zone).plusMinutes(endMinute.toLong()).toInstant().toEpochMilli()
        }
        return if (startMinute < endMinute || minute < endMinute) {
            endOn(zdt)
        } else {
            endOn(zdt.plusDays(1))
        }
    }
}

fun parseCsv(raw: String): Set<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

fun toCsv(values: Collection<String>): String =
    values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

object FocusDurations {
    val PRESETS: List<Int> = listOf(25, 50, 90)
    const val MAX: Int = 180
}

/** Fenêtre d’une session récurrente. La durée reste sous 24 h, donc la fin ne rejoint pas le début. */
object FocusWindows {
    fun endMinute(startMinute: Int, durationMinutes: Int): Int {
        val span = durationMinutes.coerceIn(1, 24 * 60 - 1)
        return (startMinute.coerceIn(0, 24 * 60 - 1) + span) % (24 * 60)
    }

    fun isActive(now: Long, zone: ZoneId, startMinute: Int, durationMinutes: Int, mask: Int): Boolean {
        if (durationMinutes <= 0 || !Weekdays.any(mask)) return false
        val end = endMinute(startMinute, durationMinutes)
        return ScheduleWindow.isActive(now, zone, startMinute, end, mask)
    }

    fun endMillis(now: Long, zone: ZoneId, startMinute: Int, durationMinutes: Int): Long {
        val end = endMinute(startMinute, durationMinutes)
        return ScheduleWindow.endMillis(now, zone, startMinute, end)
    }
}
