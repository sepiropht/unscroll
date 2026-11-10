package app.unscroll.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "daily_usage",
    primaryKeys = ["dayStart", "packageName"],
)
data class DailyUsage(
    val dayStart: Long,
    val packageName: String,
    val durationMillis: Long,
    val openCount: Int,
    /** 24 durées en millisecondes, heure locale 0–23, séparées par des virgules. */
    val hourlyMillis: String,
)

@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["dayStart"]),
        Index(value = ["packageName", "dayStart"]),
    ],
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long,
    val dayStart: Long,
)

@Entity(tableName = "app_categories")
data class AppCategory(
    @PrimaryKey val packageName: String,
    val categoryKey: String,
    val updatedAt: Long,
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey val key: String,
    val label: String,
    val colorArgb: Int,
    val custom: Boolean,
    val position: Int,
)

@Entity(tableName = "daily_totals")
data class DailyTotal(
    @PrimaryKey val dayStart: Long,
    val durationMillis: Long,
    val unlockCount: Int,
    val hourlyMillis: String,
    val updatedAt: Long,
)

/** Nombre de notifications reçues, une ligne par application et par jour local. */
@Entity(
    tableName = "notification_counts",
    primaryKeys = ["dayStart", "packageName"],
)
data class NotificationCount(
    val dayStart: Long,
    val packageName: String,
    val count: Int,
)

/**
 * Plafonds pour une application (`app:`), une catégorie (`cat:`) ou un domaine (`web:`).
 * Une durée ou un nombre à 0 désactive ce plafond. [weekdaysMask] ne concerne que la limite quotidienne.
 */
@Entity(tableName = "usage_limits")
data class UsageLimit(
    @PrimaryKey val targetKey: String,
    val dailyLimitMillis: Long = 0,
    /** Bits ISO : bit 0 = lundi … bit 6 = dimanche. */
    val weekdaysMask: Int = 0b1111111,
    val sessionLimitMillis: Long = 0,
    val cooldownMillis: Long = 0,
    val openLimit: Int = 0,
)

/**
 * Blocage immédiat. [targetKey] est un nom de paquet, ou `web:<domaine>`.
 * [untilMillis] vaut [Long.MAX_VALUE] lorsqu'il n'y a pas de fin.
 */
@Entity(tableName = "instant_blocks")
data class InstantBlock(
    @PrimaryKey val targetKey: String,
    val untilMillis: Long,
    val createdAt: Long,
)

/**
 * Plage horaire. [kind] vaut `window` (liste bloquée) ou `sleep` (tout sauf la liste blanche).
 * Une fin avant le début passe minuit.
 */
@Entity(tableName = "schedules")
data class BlockSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val startMinute: Int,
    val endMinute: Int,
    val weekdaysMask: Int,
    val packagesCsv: String,
    val domainsCsv: String,
    val kind: String,
)

@Entity(
    tableName = "web_daily",
    primaryKeys = ["dayStart", "domain"],
)
data class WebDaily(
    val dayStart: Long,
    val domain: String,
    val durationMillis: Long,
    val visitCount: Int,
)

@Entity(
    tableName = "daily_opens",
    primaryKeys = ["dayStart", "targetKey"],
)
data class DailyOpen(
    val dayStart: Long,
    val targetKey: String,
    val count: Int,
)

@Entity(tableName = "cooldowns")
data class Cooldown(
    @PrimaryKey val targetKey: String,
    val untilMillis: Long,
)

/** Un seul coup de pouce par cible et par jour. */
@Entity(
    tableName = "extra_grants",
    primaryKeys = ["dayStart", "targetKey"],
)
data class ExtraGrant(
    val dayStart: Long,
    val targetKey: String,
    val reason: String,
    val bonusMillis: Long,
    val sessionCeilingMillis: Long,
    val bypassUntil: Long,
)

@Entity(
    tableName = "limit_warnings",
    primaryKeys = ["dayStart", "targetKey", "minutes"],
)
data class LimitWarning(
    val dayStart: Long,
    val targetKey: String,
    val minutes: Int,
)

fun UsageLimit.hasAnyCap(): Boolean =
    dailyLimitMillis > 0L || sessionLimitMillis > 0L || openLimit > 0

object ScheduleKinds {
    const val WINDOW = "window"
    const val SLEEP = "sleep"
}

/**
 * Créneau de concentration qui revient. [durationMinutes] part de [startMinute].
 * Une fin après minuit est portée par le calcul, pas par une seconde heure saisie.
 */
@Entity(tableName = "focus_routines")
data class FocusRoutine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val startMinute: Int,
    val durationMinutes: Int,
    val weekdaysMask: Int,
    val packagesCsv: String,
)
