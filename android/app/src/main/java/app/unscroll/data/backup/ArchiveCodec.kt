package app.unscroll.data.backup

import app.unscroll.data.db.AppCategory
import app.unscroll.data.db.BlockSchedule
import app.unscroll.data.db.Category
import app.unscroll.data.db.DefaultCategories
import app.unscroll.data.db.FocusRoutine
import app.unscroll.data.db.InstantBlock
import app.unscroll.data.db.ScheduleKinds
import app.unscroll.data.db.UsageLimit
import app.unscroll.data.guard.Weekdays
import app.unscroll.data.guard.parseCsv
import app.unscroll.data.guard.toCsv
import app.unscroll.prefs.AppPrefs
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class HistoryCsvLine(
    val dayStart: Long,
    val packageName: String,
    val label: String,
    val durationMillis: Long,
    val openCount: Int,
)

/** JSON de sauvegarde et CSV d'historique. Pas d'appel Android : les essais JVM couvrent le format. */
internal object ArchiveCodec {

    fun renderHistory(zone: ZoneId, lines: List<HistoryCsvLine>): String {
        val sorted = lines.sortedWith(compareBy({ it.dayStart }, { it.packageName }))
        val out = StringBuilder("\uFEFFdate,app,package,duration_ms,opens\n")
        for (line in sorted) {
            val date = Instant.ofEpochMilli(line.dayStart).atZone(zone).toLocalDate()
            out.append(date.toString())
            out.append(',')
            out.append(csvCell(line.label))
            out.append(',')
            out.append(csvCell(line.packageName))
            out.append(',')
            out.append(line.durationMillis)
            out.append(',')
            out.append(line.openCount)
            out.append('\n')
        }
        return out.toString()
    }

    fun encode(snapshot: Snapshot): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("app", APP_ID)
        root.put("settings", encodeSettings(snapshot.settings))
        root.put("categories", JSONArray().apply {
            snapshot.categories.forEach { row ->
                put(JSONObject()
                    .put("key", row.key)
                    .put("label", row.label)
                    .put("color", row.colorArgb.toLong() and 0xFFFFFFFFL)
                    .put("custom", row.custom)
                    .put("position", row.position))
            }
        })
        root.put("assignments", JSONArray().apply {
            snapshot.assignments.forEach { row ->
                put(JSONObject().put("package", row.packageName).put("category", row.categoryKey))
            }
        })
        root.put("limits", JSONArray().apply {
            snapshot.limits.forEach { row ->
                put(JSONObject()
                    .put("target", row.targetKey)
                    .put("dailyMillis", row.dailyLimitMillis)
                    .put("weekdays", row.weekdaysMask)
                    .put("sessionMillis", row.sessionLimitMillis)
                    .put("cooldownMillis", row.cooldownMillis)
                    .put("opens", row.openLimit))
            }
        })
        root.put("schedules", JSONArray().apply {
            snapshot.schedules.forEach { row ->
                put(JSONObject()
                    .put("id", row.id)
                    .put("name", row.name)
                    .put("enabled", row.enabled)
                    .put("startMinute", row.startMinute)
                    .put("endMinute", row.endMinute)
                    .put("weekdays", row.weekdaysMask)
                    .put("packages", strings(parseCsv(row.packagesCsv)))
                    .put("domains", strings(parseCsv(row.domainsCsv)))
                    .put("kind", row.kind))
            }
        })
        root.put("routines", JSONArray().apply {
            snapshot.routines.forEach { row ->
                put(JSONObject()
                    .put("id", row.id)
                    .put("name", row.name)
                    .put("enabled", row.enabled)
                    .put("startMinute", row.startMinute)
                    .put("durationMinutes", row.durationMinutes)
                    .put("weekdays", row.weekdaysMask)
                    .put("packages", strings(parseCsv(row.packagesCsv))))
            }
        })
        root.put("blocks", JSONArray().apply {
            snapshot.blocks.forEach { row ->
                put(JSONObject()
                    .put("target", row.targetKey)
                    .put("until", row.untilMillis)
                    .put("created", row.createdAt))
            }
        })
        return root.toString(2)
    }

    private fun encodeSettings(blob: SettingsBlob): JSONObject {
        return JSONObject()
            .put("themeMode", blob.themeMode)
            .put("dynamicColor", blob.dynamicColor)
            .put("weekStartsOn", if (blob.weekStartsMonday) "monday" else "sunday")
            .put("hourFormat", blob.hourFormat)
            .put("showSystemApps", blob.showSystemApps)
            .put("excludedPackages", strings(blob.excluded))
            .put("extraMinutes", blob.extraMinutes)
            .put("usageReminderMinutes", blob.usageReminder)
            .put("eyeBreakMinutes", blob.eyeBreak)
            .put("dailySummaryMinute", blob.summaryMinute)
            .put("focusDraftMinutes", blob.draftMinutes)
            .put("focusDraftPackages", strings(blob.draftPackages))
    }

    fun decode(bytes: ByteArray): Snapshot {
        val root = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (_: JSONException) {
            throw ArchiveException(ArchiveException.Kind.FORMAT)
        }
        if (root.optInt("format", -1) != FORMAT || root.optString("app") !in ACCEPTED_APP_IDS) {
            throw ArchiveException(ArchiveException.Kind.FORMAT)
        }
        if (!root.has("settings") || !root.has("limits") || !root.has("schedules") || !root.has("categories")) {
            throw ArchiveException(ArchiveException.Kind.FORMAT)
        }
        return try {
            Snapshot(
                settings = decodeSettings(root.getJSONObject("settings")),
                categories = decodeCategories(root.getJSONArray("categories")),
                assignments = decodeAssignments(root.optJSONArray("assignments") ?: JSONArray()),
                limits = decodeLimits(root.getJSONArray("limits")),
                schedules = decodeSchedules(root.getJSONArray("schedules")),
                routines = decodeRoutines(root.optJSONArray("routines") ?: JSONArray()),
                blocks = decodeBlocks(root.optJSONArray("blocks") ?: JSONArray()),
            )
        } catch (_: JSONException) {
            throw ArchiveException(ArchiveException.Kind.FORMAT)
        }
    }

    private fun decodeSettings(obj: JSONObject): SettingsBlob {
        val week = obj.optString("weekStartsOn", "monday")
        return SettingsBlob(
            themeMode = obj.optString("themeMode", AppPrefs.THEME_SYSTEM),
            dynamicColor = obj.optBoolean("dynamicColor", true),
            weekStartsMonday = week != "sunday",
            hourFormat = obj.optString("hourFormat", AppPrefs.HOUR_SYSTEM),
            showSystemApps = obj.optBoolean("showSystemApps", true),
            excluded = readStrings(obj.optJSONArray("excludedPackages")),
            extraMinutes = obj.optBoolean("extraMinutes", true),
            usageReminder = obj.optInt("usageReminderMinutes", 0),
            eyeBreak = obj.optInt("eyeBreakMinutes", 0),
            summaryMinute = obj.optInt("dailySummaryMinute", -1),
            draftMinutes = obj.optInt("focusDraftMinutes", 25),
            draftPackages = readStrings(obj.optJSONArray("focusDraftPackages")),
        )
    }

    private fun decodeCategories(array: JSONArray): List<Category> {
        val out = ArrayList<Category>()
        val seen = HashSet<String>()
        for (index in 0 until array.length().coerceAtMost(MAX_ROWS)) {
            val obj = array.optJSONObject(index) ?: continue
            val key = obj.optString("key").trim().take(40)
            val label = obj.optString("label").trim().take(40)
            if (key.isEmpty() || label.isEmpty() || !seen.add(key)) continue
            out += Category(
                key = key,
                label = label,
                colorArgb = obj.optLong("color", 0xFF5C6B68).toInt(),
                custom = obj.optBoolean("custom", key !in DEFAULT_KEYS),
                position = obj.optInt("position", index).coerceIn(0, 999),
            )
        }
        return out
    }

    private fun decodeAssignments(array: JSONArray): List<AppCategory> {
        val out = ArrayList<AppCategory>()
        val seen = HashSet<String>()
        for (index in 0 until array.length().coerceAtMost(MAX_ROWS)) {
            val obj = array.optJSONObject(index) ?: continue
            val packageName = obj.optString("package").trim().take(200)
            val category = obj.optString("category").trim().take(40)
            if (packageName.isEmpty() || category.isEmpty() || !seen.add(packageName)) continue
            out += AppCategory(packageName, category, 0L)
        }
        return out
    }

    private fun decodeLimits(array: JSONArray): List<UsageLimit> {
        val out = ArrayList<UsageLimit>()
        val seen = HashSet<String>()
        for (index in 0 until array.length().coerceAtMost(MAX_ROWS)) {
            val obj = array.optJSONObject(index) ?: continue
            val target = obj.optString("target").trim().take(220)
            if (target.isEmpty() || !seen.add(target)) continue
            out += UsageLimit(
                targetKey = target,
                dailyLimitMillis = obj.optLong("dailyMillis", 0L).coerceAtLeast(0L),
                weekdaysMask = obj.optInt("weekdays", Weekdays.ALL) and Weekdays.ALL,
                sessionLimitMillis = obj.optLong("sessionMillis", 0L).coerceAtLeast(0L),
                cooldownMillis = obj.optLong("cooldownMillis", 0L).coerceAtLeast(0L),
                openLimit = obj.optInt("opens", 0).coerceAtLeast(0),
            )
        }
        return out
    }

    private fun decodeSchedules(array: JSONArray): List<BlockSchedule> {
        val out = ArrayList<BlockSchedule>()
        for (index in 0 until array.length().coerceAtMost(MAX_ROWS)) {
            val obj = array.optJSONObject(index) ?: continue
            val kind = obj.optString("kind", ScheduleKinds.WINDOW)
            if (kind != ScheduleKinds.WINDOW && kind != ScheduleKinds.SLEEP) continue
            val name = obj.optString("name").trim().take(40)
            if (kind == ScheduleKinds.WINDOW && name.isEmpty()) continue
            val start = obj.optInt("startMinute", 0).coerceIn(0, MINUTES - 1)
            val end = obj.optInt("endMinute", 0).coerceIn(0, MINUTES - 1)
            if (start == end) continue
            out += BlockSchedule(
                id = obj.optLong("id", 0L).coerceAtLeast(0L),
                name = name.ifBlank { "Sommeil" },
                enabled = obj.optBoolean("enabled", true),
                startMinute = start,
                endMinute = end,
                weekdaysMask = obj.optInt("weekdays", Weekdays.ALL) and Weekdays.ALL,
                packagesCsv = toCsv(readStrings(obj.optJSONArray("packages"))),
                domainsCsv = toCsv(readStrings(obj.optJSONArray("domains"))),
                kind = kind,
            )
        }
        return out
    }

    private fun decodeRoutines(array: JSONArray): List<FocusRoutine> {
        val out = ArrayList<FocusRoutine>()
        for (index in 0 until array.length().coerceAtMost(MAX_ROWS)) {
            val obj = array.optJSONObject(index) ?: continue
            val name = obj.optString("name").trim().take(40)
            val packages = readStrings(obj.optJSONArray("packages"))
            if (name.isEmpty() || packages.isEmpty()) continue
            out += FocusRoutine(
                id = obj.optLong("id", 0L).coerceAtLeast(0L),
                name = name,
                enabled = obj.optBoolean("enabled", true),
                startMinute = obj.optInt("startMinute", 0).coerceIn(0, MINUTES - 1),
                durationMinutes = obj.optInt("durationMinutes", 25).coerceIn(1, 180),
                weekdaysMask = obj.optInt("weekdays", Weekdays.ALL) and Weekdays.ALL,
                packagesCsv = toCsv(packages),
            )
        }
        return out
    }

    private fun decodeBlocks(array: JSONArray): List<InstantBlock> {
        val out = ArrayList<InstantBlock>()
        val seen = HashSet<String>()
        for (index in 0 until array.length().coerceAtMost(MAX_ROWS)) {
            val obj = array.optJSONObject(index) ?: continue
            val target = obj.optString("target").trim().take(220)
            if (target.isEmpty() || !seen.add(target)) continue
            val until = obj.optLong("until", 0L)
            if (until <= 0L) continue
            out += InstantBlock(
                targetKey = target,
                untilMillis = until,
                createdAt = obj.optLong("created", 0L).coerceAtLeast(0L),
            )
        }
        return out
    }

    private fun strings(values: Collection<String>): JSONArray {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array
    }

    private fun readStrings(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (index in 0 until array.length().coerceAtMost(MAX_ROWS)) {
            val value = array.optString(index).trim().take(200)
            if (value.isNotEmpty()) out += value
        }
        return out
    }

    private fun csvCell(value: String): String {
        val guarded = if (value.firstOrNull() in FORMULA) "'$value" else value
        val escaped = guarded.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }

    private const val FORMAT = 1
    private const val APP_ID = "com.elimbi.unscroll"

    /** Backups written under previous application IDs still import. */
    private val ACCEPTED_APP_IDS = setOf(APP_ID, "app.unscroll", "cm.elimbi.screentime")
    private const val MAX_ROWS = 4_000
    private const val MINUTES = 24 * 60
    private val FORMULA = setOf('=', '+', '-', '@')
    private val DEFAULT_KEYS = DefaultCategories.seeds.map { it.key }.toSet()
}
