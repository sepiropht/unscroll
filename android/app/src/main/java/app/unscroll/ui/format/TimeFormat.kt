package app.unscroll.ui.format

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.unscroll.R
import app.unscroll.prefs.AppPrefs
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun formatCountdown(millis: Long): String = formatCountdown(millis, LocalContext.current)

fun formatCountdown(millis: Long, context: Context): String {
    val total = (millis / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) {
        context.getString(R.string.countdown_hours, hours, minutes)
    } else {
        context.getString(R.string.countdown_clock, minutes, seconds)
    }
}

@Composable
fun formatDuration(millis: Long): String = formatDuration(millis, LocalContext.current)

fun formatDuration(millis: Long, context: Context): String {
    if (millis <= 0L) return context.getString(R.string.duration_zero)
    val minutes = millis / 60_000L
    if (minutes < 1L) return context.getString(R.string.duration_under_minute)
    val hours = minutes / 60L
    val rem = minutes % 60L
    return when {
        hours <= 0L -> context.resources.getQuantityString(
            R.plurals.duration_minutes,
            quantityOf(minutes),
            minutes,
        )
        rem == 0L -> context.resources.getQuantityString(
            R.plurals.duration_hours,
            quantityOf(hours),
            hours,
        )
        else -> context.getString(R.string.duration_hours_minutes, hours, rem)
    }
}

/** Le sélecteur de pluriel est un Int. Au-delà, la forme « other » / « many » reste la bonne. */
private fun quantityOf(value: Long): Int = value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

fun formatHoursAndMinutes(hours: Long, minutes: Long, context: Context): String {
    return context.getString(R.string.duration_hours_and_minutes, hours, minutes)
}

fun percentDelta(current: Long, baseline: Long): Int? {
    if (baseline <= 0L) return null
    return ((current - baseline).toDouble() / baseline.toDouble() * 100.0).roundToInt()
}

fun formatClock(millis: Long, context: Context): String {
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(clockPattern(context), Locale.getDefault()))
}

fun formatMinute(minuteOfDay: Int, context: Context): String {
    val minute = minuteOfDay.coerceIn(0, 24 * 60 - 1)
    return LocalTime.of(minute / 60, minute % 60)
        .format(DateTimeFormatter.ofPattern(clockPattern(context), Locale.getDefault()))
}

private fun clockPattern(context: Context): String =
    if (AppPrefs(context).uses24Hour(context)) "HH:mm" else "h:mm a"

fun formatDayLong(date: LocalDate): String {
    val locale = Locale.getDefault()
    val raw = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))
    return raw.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase(locale) else char.toString()
    }
}

fun formatDayMonth(date: LocalDate): String {
    return date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
}

fun formatDayShort(date: LocalDate): String {
    return date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
}

fun formatWeekdayShort(date: LocalDate): String {
    return date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).trimEnd('.')
}

fun formatWhen(millis: Long, context: Context): String {
    val locale = Locale.getDefault()
    val pattern = if (AppPrefs(context).uses24Hour(context)) "EEE d MMM, HH:mm" else "EEE d MMM, h:mm a"
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern, locale))
}

