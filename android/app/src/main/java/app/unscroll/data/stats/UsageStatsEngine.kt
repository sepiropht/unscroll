package app.unscroll.data.stats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/**
 * Lit [UsageStatsManager.queryEvents] et produit des journées de sessions,
 * d'ouvertures, de déverrouillages et de barres horaires.
 */
class UsageStatsEngine(context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val excludes = setOf(
        "android",
        "com.android.systemui",
        context.packageName,
    )

    fun compute(start: LocalDate, endExclusive: LocalDate): Map<Long, DaySlice> {
        val zone = ZoneId.systemDefault()
        if (!endExclusive.isAfter(start)) return emptyMap()
        val result = linkedMapOf<Long, DaySlice>()
        var cursor = start
        while (cursor.isBefore(endExclusive)) {
            val chunkEnd = minOf(endExclusive, cursor.plusDays(CHUNK_DAYS))
            val from = cursor.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = chunkEnd.atStartOfDay(zone).toInstant().toEpochMilli()
            computeChunk(zone, from, to).forEach { slice -> result[slice.dayStart] = slice }
            cursor = chunkEnd
        }
        return result
    }

    private fun computeChunk(zone: ZoneId, rangeStart: Long, rangeEnd: Long): List<DaySlice> {
        val folder = UsageEventFolder(zone, excludes)
        val now = System.currentTimeMillis()
        if (usm == null || now <= rangeStart) {
            return folder.fold(emptyList(), rangeStart, rangeEnd, now)
        }
        val queryTo = minOf(rangeEnd, now)
        val queryFrom = (rangeStart - LOOKBACK_MS).coerceAtLeast(0L)
        val events = try {
            readEvents(queryFrom, queryTo)
        } catch (_: SecurityException) {
            emptyList()
        }
        return folder.fold(events, rangeStart, rangeEnd, now)
    }

    /**
     * Dernière application encore au premier plan dans la fenêtre demandée.
     * Retourne null si l’écran est éteint ou le téléphone verrouillé.
     */
    fun currentForeground(now: Long, lookbackMs: Long): String? {
        if (usm == null) return null
        val span = lookbackMs.coerceIn(1_000L, 6L * 60L * 60L * 1000L)
        val from = (now - span).coerceAtLeast(0L)
        if (now <= from) return null
        val events = try {
            readEvents(from, now)
        } catch (_: SecurityException) {
            return null
        }
        var interactive = true
        var current: String? = null
        for (event in events) {
            when (event.kind) {
                FoldKind.SCREEN_OFF, FoldKind.SHUTDOWN, FoldKind.LOCK -> {
                    interactive = false
                    current = null
                }
                FoldKind.SCREEN_ON, FoldKind.UNLOCK -> interactive = true
                FoldKind.FOREGROUND -> {
                    val pkg = event.packageName
                    if (!pkg.isNullOrBlank() && pkg !in excludes) {
                        current = pkg
                        interactive = true
                    }
                }
                FoldKind.BACKGROUND -> if (event.packageName == current) current = null
                FoldKind.OTHER -> Unit
            }
        }
        if (!interactive) return null
        return current
    }

    @Suppress("DEPRECATION")
    private fun readEvents(from: Long, to: Long): List<FoldEvent> {
        if (to <= from) return emptyList()
        val raw = usm?.queryEvents(from, to) ?: return emptyList()
        val out = ArrayList<FoldEvent>(256)
        val event = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(event)
            out += FoldEvent(
                timestamp = event.timeStamp,
                kind = mapKind(event.eventType),
                packageName = event.packageName,
            )
        }
        out.sortBy { it.timestamp }
        return out
    }

    @Suppress("DEPRECATION")
    private fun mapKind(type: Int): FoldKind = when (type) {
        UsageEvents.Event.MOVE_TO_FOREGROUND -> FoldKind.FOREGROUND
        UsageEvents.Event.MOVE_TO_BACKGROUND -> FoldKind.BACKGROUND
        UsageEvents.Event.SCREEN_INTERACTIVE -> FoldKind.SCREEN_ON
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> FoldKind.SCREEN_OFF
        UsageEvents.Event.KEYGUARD_HIDDEN -> FoldKind.UNLOCK
        UsageEvents.Event.KEYGUARD_SHOWN -> FoldKind.LOCK
        UsageEvents.Event.DEVICE_SHUTDOWN -> FoldKind.SHUTDOWN
        else -> FoldKind.OTHER
    }

    private companion object {
        const val LOOKBACK_MS = 12L * 60L * 60L * 1000L
        const val CHUNK_DAYS = 7L
    }
}
