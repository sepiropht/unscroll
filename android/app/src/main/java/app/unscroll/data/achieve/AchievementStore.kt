package app.unscroll.data.achieve

import android.content.Context

/**
 * Préférences des succès. Le compteur de concentration ne retient que les
 * créneaux arrivés au bout : un arrêt anticipé retire le jeton.
 */
class AchievementStore private constructor(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val lock = Any()
    private var rulesArmedDay = Long.MIN_VALUE

    var goalMillis: Long
        get() {
            val stored = sp.getLong(KEY_GOAL, DEFAULT_GOAL_MILLIS)
            val minutes = stored / 60_000L
            return if (minutes.toInt() in GOAL_MINUTES) stored else DEFAULT_GOAL_MILLIS
        }
        set(value) {
            val minutes = (value / 60_000L).toInt()
            if (minutes !in GOAL_MINUTES) return
            sp.edit().putLong(KEY_GOAL, minutes * 60_000L).apply()
        }

    val focusSessions: Int
        get() = sp.getInt(KEY_FOCUS, 0).coerceAtLeast(0)

    fun unlocks(): Map<String, Long> = decodePairs(sp.getStringSet(KEY_UNLOCKS, emptySet()))

    fun clear() {
        synchronized(lock) {
            rulesArmedDay = Long.MIN_VALUE
            sp.edit().clear().commit()
        }
    }

    fun remember(keys: Set<String>, at: Long) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            val current = unlocks().toMutableMap()
            var changed = false
            for (key in keys) {
                if (key !in AchievementRules.ALL) continue
                if (current.putIfAbsent(key, at) == null) changed = true
            }
            if (!changed) return
            sp.edit().putStringSet(KEY_UNLOCKS, encodePairs(current)).apply()
        }
    }

    /** Mémorise les journées réellement lues, y compris une durée nulle. */
    fun rememberUsage(rows: List<UsageDay>) {
        val known = rows.filter { it.known && it.dayStart > 0L }
        if (known.isEmpty()) return
        synchronized(lock) {
            val current = usageHistory().toMutableMap()
            var changed = false
            for (row in known) {
                val duration = row.durationMillis.coerceAtLeast(0L)
                if (current[row.dayStart] != duration) {
                    current[row.dayStart] = duration
                    changed = true
                }
            }
            if (current.size > 120) {
                current.keys.sorted().take(current.size - 120).forEach { current.remove(it) }
                changed = true
            }
            if (!changed) return
            val encoded = current.map { (day, duration) -> "$day|$duration" }.toSet()
            sp.edit().putStringSet(KEY_USAGE, encoded).apply()
        }
    }

    fun usageHistory(): Map<Long, Long> {
        val raw = sp.getStringSet(KEY_USAGE, emptySet()) ?: return emptyMap()
        val out = LinkedHashMap<Long, Long>()
        for (item in raw) {
            val cut = item.indexOf('|')
            if (cut <= 0) continue
            val day = item.substring(0, cut).toLongOrNull() ?: continue
            val duration = item.substring(cut + 1).toLongOrNull() ?: continue
            out[day] = duration.coerceAtLeast(0L)
        }
        return out
    }

    fun guardMarks(): Map<Long, GuardMark> {
        val raw = sp.getStringSet(KEY_GUARDS, emptySet()) ?: emptySet()
        val out = LinkedHashMap<Long, GuardMark>()
        for (item in raw) {
            val parts = item.split('|')
            if (parts.size != 3) continue
            val day = parts[0].toLongOrNull() ?: continue
            val launches = parts[1].toIntOrNull() ?: continue
            val armed = parts[2] == "1"
            out[day] = GuardMark(launches.coerceAtLeast(0), armed)
        }
        return out
    }

    /** [countLaunch] est vrai une seule fois par arrivée sur une application bloquée. */
    fun noteGuard(dayStart: Long, armed: Boolean, countLaunch: Boolean) {
        if (dayStart <= 0L) return
        if (!countLaunch && !armed) return
        if (!countLaunch && rulesArmedDay == dayStart) return
        synchronized(lock) {
            if (!countLaunch && rulesArmedDay == dayStart) return
            val marks = guardMarks().toMutableMap()
            val current = marks[dayStart]
            val launches = (current?.launches ?: 0) + if (countLaunch) 1 else 0
            val nowArmed = (current?.armed == true) || armed || countLaunch
            if (!countLaunch && current?.armed == true) {
                rulesArmedDay = dayStart
                return
            }
            marks[dayStart] = GuardMark(launches, nowArmed)
            if (marks.size > 120) {
                marks.keys.sorted().take(marks.size - 120).forEach { marks.remove(it) }
            }
            val editor = sp.edit().putStringSet(KEY_GUARDS, encodeGuards(marks))
            if (countLaunch) editor.commit() else editor.apply()
            if (nowArmed) rulesArmedDay = dayStart
        }
    }

    fun armManual(started: Long, until: Long) {
        if (started <= 0L || until <= started) return
        synchronized(lock) { arm(manual = true, id = started, until = until) }
    }

    fun armRoutine(id: Long, until: Long) {
        if (id == 0L || until <= 0L) return
        synchronized(lock) { arm(manual = false, id = id, until = until) }
    }

    fun onManualCleared(now: Long) {
        synchronized(lock) { close(manual = true, id = null, now = now) }
    }

    fun onRoutineSkipped(id: Long, now: Long) {
        if (id == 0L) return
        synchronized(lock) { close(manual = false, id = id, now = now) }
    }

    /** Crédite les créneaux dont l’heure de fin est passée. N’anticipe pas. */
    fun settleDue(now: Long) {
        synchronized(lock) {
            val armed = readArmed()
            val due = armed.count { now >= it.until }
            if (due == 0) return
            writeArmed(armed.filter { now < it.until }, due)
        }
    }

    private fun arm(manual: Boolean, id: Long, until: Long) {
        val now = System.currentTimeMillis()
        val armed = readArmed()
        if (armed.any { it.manual == manual && it.id == id && it.until == until }) return
        var extra = 0
        val kept = ArrayList<Armed>(armed.size + 1)
        for (item in armed) {
            val replaced = item.manual == manual && (manual || item.id == id)
            if (!replaced) {
                kept += item
            } else if (now >= item.until) {
                extra += 1
            }
        }
        kept += Armed(manual, id, until)
        writeArmed(kept, extra)
    }

    private fun close(manual: Boolean, id: Long?, now: Long) {
        val armed = readArmed()
        var extra = 0
        val kept = ArrayList<Armed>()
        for (item in armed) {
            val hit = item.manual == manual && (id == null || item.id == id)
            if (!hit) {
                kept += item
            } else if (now + GRACE_MS >= item.until) {
                extra += 1
            }
        }
        writeArmed(kept, extra)
    }

    private fun readArmed(): List<Armed> {
        val raw = sp.getStringSet(KEY_ARMED, emptySet()) ?: emptySet()
        val out = ArrayList<Armed>(raw.size)
        for (item in raw) {
            val parts = item.split('|')
            if (parts.size != 3) continue
            val manual = when (parts[0]) {
                "m" -> true
                "r" -> false
                else -> continue
            }
            val id = parts[1].toLongOrNull() ?: continue
            val until = parts[2].toLongOrNull() ?: continue
            out += Armed(manual, id, until)
        }
        return out
    }

    private fun writeArmed(items: List<Armed>, focusDelta: Int) {
        val encoded = items.map { item ->
            val kind = if (item.manual) "m" else "r"
            "$kind|${item.id}|${item.until}"
        }.toSet()
        val editor = sp.edit().putStringSet(KEY_ARMED, encoded)
        if (focusDelta > 0) {
            editor.putInt(KEY_FOCUS, sp.getInt(KEY_FOCUS, 0) + focusDelta)
        }
        editor.commit()
    }

    private data class Armed(val manual: Boolean, val id: Long, val until: Long)

    companion object {
        private const val DEFAULT_GOAL_MINUTES = AchievementRules.DEFAULT_GOAL_MINUTES
        private val GOAL_MINUTES = AchievementRules.GOAL_MINUTES
        const val DEFAULT_GOAL_MILLIS = DEFAULT_GOAL_MINUTES * 60_000L
        private const val GRACE_MS = 15_000L
        private const val FILE = "temps_ecran_badges"
        private const val KEY_GOAL = "daily_goal_millis"
        private const val KEY_FOCUS = "focus_sessions"
        private const val KEY_UNLOCKS = "unlocked"
        private const val KEY_GUARDS = "guard_days"
        private const val KEY_ARMED = "focus_armed"
        private const val KEY_USAGE = "usage_days"

        @Volatile
        private var instance: AchievementStore? = null

        fun get(context: Context): AchievementStore {
            instance?.let { return it }
            return synchronized(AchievementStore::class.java) {
                instance ?: AchievementStore(context.applicationContext).also { instance = it }
            }
        }

        private fun decodePairs(raw: Set<String>?): Map<String, Long> {
            if (raw.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, Long>()
            for (item in raw) {
                val cut = item.lastIndexOf('|')
                if (cut <= 0) continue
                val key = item.substring(0, cut)
                val at = item.substring(cut + 1).toLongOrNull() ?: continue
                out[key] = at
            }
            return out
        }

        private fun encodePairs(values: Map<String, Long>): Set<String> =
            values.map { (key, at) -> "$key|$at" }.toSet()

        private fun encodeGuards(values: Map<Long, GuardMark>): Set<String> =
            values.map { (day, mark) ->
                "$day|${mark.launches}|${if (mark.armed) 1 else 0}"
            }.toSet()
    }
}
