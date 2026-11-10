package app.unscroll.data.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal enum class FoldKind {
    FOREGROUND,
    BACKGROUND,
    SCREEN_ON,
    SCREEN_OFF,
    UNLOCK,
    LOCK,
    SHUTDOWN,
    OTHER,
}

internal data class FoldEvent(
    val timestamp: Long,
    val kind: FoldKind,
    val packageName: String?,
)

/**
 * Replie une suite d'événements en sessions.
 *
 * Le temps compte tant qu'une application est au premier plan, écran allumé,
 * sans verrouillage. Une pause suivie d'un retour dans la même application
 * en moins de 1,5 s ne coupe pas la session. Les durées sont découpées aux
 * bornes d'heure et de journée. Un déverrouillage est un KEYGUARD_HIDDEN.
 * Une ouverture est un passage au premier plan après une coupure ou un
 * changement d'application.
 */
internal class UsageEventFolder(
    private val zone: ZoneId,
    private val excludes: Set<String>,
) {
    fun fold(
        events: List<FoldEvent>,
        rangeStart: Long,
        rangeEnd: Long,
        now: Long,
    ): List<DaySlice> {
        if (rangeEnd <= rangeStart) return emptyList()
        val effectiveEnd = minOf(rangeEnd, now).coerceAtLeast(rangeStart)
        val accs = mutableMapOf<Long, Acc>()

        var screenOn = true
        var keyguardOn = false
        var foreground: String? = null
        var countingFrom: Long? = null
        var broken = true
        var pausedAt: Long? = null

        fun record(pkg: String, start: Long, end: Long) {
            val clippedStart = maxOf(start, rangeStart)
            val clippedEnd = minOf(end, effectiveEnd)
            if (clippedEnd <= clippedStart) return
            addSessionAndTime(accs, pkg, clippedStart, clippedEnd)
        }

        fun stopAt(at: Long) {
            val from = countingFrom ?: return
            val pkg = foreground ?: return
            countingFrom = null
            record(pkg, from, at)
        }

        fun arm(at: Long, pkg: String) {
            if (!screenOn || keyguardOn || countingFrom != null) return
            countingFrom = at
            if (!broken) return
            broken = false
            if (at in rangeStart until effectiveEnd) addOpen(accs, pkg, at)
        }

        for (event in events) {
            if (event.timestamp >= effectiveEnd) break
            val t = event.timestamp
            if (t in rangeStart until effectiveEnd) bumpEvent(accs, t)
            when (event.kind) {
                FoldKind.BACKGROUND -> {
                    if (countingFrom != null && pausedAt == null &&
                        (event.packageName == null || event.packageName == foreground)
                    ) {
                        pausedAt = t
                    }
                }
                FoldKind.FOREGROUND -> {
                    val pkg = event.packageName
                    if (pkg.isNullOrBlank() || pkg in excludes) {
                        val endAt = pausedAt ?: t
                        pausedAt = null
                        stopAt(endAt)
                        if (foreground != null) {
                            foreground = null
                            broken = true
                        }
                    } else {
                        val same = pkg == foreground
                        if (pausedAt != null) {
                            val pause = pausedAt!!
                            if (same && t - pause <= GAP_MS) {
                                pausedAt = null
                            } else {
                                stopAt(pause)
                                pausedAt = null
                                foreground = pkg
                                broken = true
                            }
                        } else if (!same) {
                            stopAt(t)
                            foreground = pkg
                            broken = true
                        }
                        foreground = pkg
                        arm(t, pkg)
                    }
                }
                FoldKind.SCREEN_OFF -> {
                    val endAt = minOf(pausedAt ?: t, t)
                    pausedAt = null
                    stopAt(endAt)
                    screenOn = false
                    broken = true
                }
                FoldKind.SCREEN_ON -> {
                    screenOn = true
                    val pkg = foreground
                    if (pkg != null) arm(t, pkg)
                }
                FoldKind.LOCK -> {
                    val endAt = minOf(pausedAt ?: t, t)
                    pausedAt = null
                    stopAt(endAt)
                    keyguardOn = true
                    broken = true
                }
                FoldKind.UNLOCK -> {
                    if (t in rangeStart until effectiveEnd) addUnlock(accs, t)
                    keyguardOn = false
                    val pkg = foreground
                    if (screenOn && pkg != null) arm(t, pkg)
                }
                FoldKind.SHUTDOWN -> {
                    val endAt = pausedAt ?: t
                    pausedAt = null
                    stopAt(endAt)
                    foreground = null
                    screenOn = false
                    keyguardOn = true
                    broken = true
                }
                FoldKind.OTHER -> Unit
            }
        }
        stopAt(pausedAt ?: effectiveEnd)
        return materialize(accs, rangeStart, rangeEnd)
    }

    private fun addSessionAndTime(
        accs: MutableMap<Long, Acc>,
        pkg: String,
        start: Long,
        end: Long,
    ) {
        var cursor = start
        while (cursor < end) {
            val zdt = Instant.ofEpochMilli(cursor).atZone(zone)
            val dayStartZdt = zdt.toLocalDate().atStartOfDay(zone)
            val nextDay = dayStartZdt.plusDays(1).toInstant().toEpochMilli()
            val sessionEnd = minOf(end, nextDay)
            if (sessionEnd <= cursor) break
            val dayStart = dayStartZdt.toInstant().toEpochMilli()
            accs.getOrPut(dayStart) { Acc() }.sessions += SessionSlice(pkg, cursor, sessionEnd)
            cursor = sessionEnd
        }

        cursor = start
        while (cursor < end) {
            val zdt = Instant.ofEpochMilli(cursor).atZone(zone)
            val dayStart = zdt.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            val nextHour = zdt.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val nextDay = Instant.ofEpochMilli(dayStart).atZone(zone).plusDays(1).toInstant().toEpochMilli()
            val boundary = minOf(end, nextHour, nextDay)
            if (boundary <= cursor) break
            val delta = boundary - cursor
            val pkgAcc = accs.getOrPut(dayStart) { Acc() }.packages.getOrPut(pkg) { PkgAcc() }
            pkgAcc.duration += delta
            pkgAcc.hourly[zdt.hour] += delta
            cursor = boundary
        }
    }

    private fun addOpen(accs: MutableMap<Long, Acc>, pkg: String, at: Long) {
        val dayStart = dayStartOf(at)
        val pkgAcc = accs.getOrPut(dayStart) { Acc() }.packages.getOrPut(pkg) { PkgAcc() }
        pkgAcc.opens += 1
    }

    private fun addUnlock(accs: MutableMap<Long, Acc>, at: Long) {
        accs.getOrPut(dayStartOf(at)) { Acc() }.unlocks += 1
    }

    private fun bumpEvent(accs: MutableMap<Long, Acc>, at: Long) {
        accs.getOrPut(dayStartOf(at)) { Acc() }.events += 1
    }

    private fun dayStartOf(at: Long): Long {
        return Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun materialize(
        accs: Map<Long, Acc>,
        rangeStart: Long,
        rangeEnd: Long,
    ): List<DaySlice> {
        val startDate = Instant.ofEpochMilli(rangeStart).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(rangeEnd - 1).atZone(zone).toLocalDate()
        val out = mutableListOf<DaySlice>()
        var date = startDate
        while (!date.isAfter(endDate)) {
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            out += accs[dayStart]?.toSlice(date, dayStart) ?: DaySlice.empty(date, dayStart)
            date = date.plusDays(1)
        }
        return out
    }

    private class Acc {
        var unlocks: Int = 0
        var events: Int = 0
        val packages: MutableMap<String, PkgAcc> = mutableMapOf()
        val sessions: MutableList<SessionSlice> = mutableListOf()

        fun toSlice(date: LocalDate, dayStart: Long): DaySlice {
            val pkgs = packages.map { (name, acc) ->
                PkgSlice(
                    packageName = name,
                    durationMillis = acc.duration,
                    openCount = acc.opens,
                    hourly = acc.hourly.toList(),
                )
            }.sortedByDescending { it.durationMillis }
            val hourly = LongArray(24)
            for (pkg in packages.values) {
                for (hour in 0 until 24) hourly[hour] += pkg.hourly[hour]
            }
            return DaySlice(
                dayStart = dayStart,
                date = date,
                totalMillis = pkgs.sumOf { it.durationMillis },
                unlockCount = unlocks,
                hourly = hourly.toList(),
                packages = pkgs,
                sessions = sessions.sortedBy { it.startMillis },
                eventCount = events,
            )
        }
    }

    private class PkgAcc {
        var duration: Long = 0L
        var opens: Int = 0
        val hourly: LongArray = LongArray(24)
    }

    companion object {
        private const val GAP_MS = 1_500L
    }
}
