package app.unscroll.sync

import app.unscroll.data.db.AppCategory
import app.unscroll.data.db.BlockSchedule
import app.unscroll.data.db.Category
import app.unscroll.data.db.Cooldown
import app.unscroll.data.db.DailyOpen
import app.unscroll.data.db.DailyTotal
import app.unscroll.data.db.DailyUsage
import app.unscroll.data.db.ExtraGrant
import app.unscroll.data.db.InstantBlock
import app.unscroll.data.db.LimitWarning
import app.unscroll.data.db.NotificationCount
import app.unscroll.data.db.ScheduleKinds
import app.unscroll.data.db.Session
import app.unscroll.data.db.UsageLimit
import app.unscroll.data.db.WebDaily
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

sealed class SnapshotDecode {
    data class Ok(val snapshot: AndroidSnapshot) : SnapshotDecode()
    data object Schema : SnapshotDecode()
    data object TooLarge : SnapshotDecode()
}

/** Lecture et écriture du snapshot Android. Une ligne illisible est ignorée. */
object AndroidSnapshotCodec {
    private const val MAX_SPAN_MS = 366L * 24 * 60 * 60 * 1000
    private const val MAX_COUNT = 1_000_000

    fun encode(snapshot: AndroidSnapshot): JSONObject {
        val prefs = JSONObject()
        snapshot.preferences.themeMode?.let { prefs.put("themeMode", it) }
        snapshot.preferences.dynamicColor?.let { prefs.put("dynamicColor", it) }
        snapshot.preferences.excludedPackages?.let { list ->
            val array = JSONArray()
            list.forEach { array.put(it) }
            prefs.put("excludedPackages", array)
        }
        snapshot.preferences.extraMinutesEnabled?.let { prefs.put("extraMinutesEnabled", it) }
        return JSONObject()
            .put("schema", SyncRules.SCHEMA)
            .put("app", SyncRules.APP)
            .put("exportedAt", snapshot.exportedAt)
            .put("deviceId", snapshot.deviceId)
            .put("dailyUsage", jsonArray(snapshot.dailyUsage.map { row ->
                JSONObject()
                    .put("dayStart", row.dayStart)
                    .put("packageName", row.packageName)
                    .put("durationMillis", row.durationMillis)
                    .put("openCount", row.openCount)
                    .put("hourlyMillis", row.hourlyMillis)
            }))
            .put("sessions", jsonArray(snapshot.sessions.map { row ->
                JSONObject()
                    .put("packageName", row.packageName)
                    .put("startMillis", row.startMillis)
                    .put("endMillis", row.endMillis)
                    .put("dayStart", row.dayStart)
            }))
            .put("appCategories", jsonArray(snapshot.appCategories.map { row ->
                JSONObject()
                    .put("packageName", row.packageName)
                    .put("categoryKey", row.categoryKey)
                    .put("updatedAt", row.updatedAt)
            }))
            .put("categories", jsonArray(snapshot.categories.map { row ->
                JSONObject()
                    .put("key", row.key)
                    .put("label", row.label)
                    .put("colorArgb", row.colorArgb)
                    .put("custom", row.custom)
                    .put("position", row.position)
            }))
            .put("dailyTotals", jsonArray(snapshot.dailyTotals.map { row ->
                JSONObject()
                    .put("dayStart", row.dayStart)
                    .put("durationMillis", row.durationMillis)
                    .put("unlockCount", row.unlockCount)
                    .put("hourlyMillis", row.hourlyMillis)
                    .put("updatedAt", row.updatedAt)
            }))
            .put("notificationCounts", jsonArray(snapshot.notificationCounts.map { row ->
                JSONObject()
                    .put("dayStart", row.dayStart)
                    .put("packageName", row.packageName)
                    .put("count", row.count)
            }))
            .put("usageLimits", jsonArray(snapshot.usageLimits.map { row ->
                JSONObject()
                    .put("targetKey", row.targetKey)
                    .put("dailyLimitMillis", row.dailyLimitMillis)
                    .put("weekdaysMask", row.weekdaysMask)
                    .put("sessionLimitMillis", row.sessionLimitMillis)
                    .put("cooldownMillis", row.cooldownMillis)
                    .put("openLimit", row.openLimit)
            }))
            .put("instantBlocks", jsonArray(snapshot.instantBlocks.map { row ->
                JSONObject()
                    .put("targetKey", row.targetKey)
                    .put("untilMillis", row.untilMillis)
                    .put("createdAt", row.createdAt)
            }))
            .put("schedules", jsonArray(snapshot.schedules.map { row ->
                JSONObject()
                    .put("name", row.name)
                    .put("enabled", row.enabled)
                    .put("startMinute", row.startMinute)
                    .put("endMinute", row.endMinute)
                    .put("weekdaysMask", row.weekdaysMask)
                    .put("packagesCsv", row.packagesCsv)
                    .put("domainsCsv", row.domainsCsv)
                    .put("kind", row.kind)
            }))
            .put("webDaily", jsonArray(snapshot.webDaily.map { row ->
                JSONObject()
                    .put("dayStart", row.dayStart)
                    .put("domain", row.domain)
                    .put("durationMillis", row.durationMillis)
                    .put("visitCount", row.visitCount)
            }))
            .put("dailyOpens", jsonArray(snapshot.dailyOpens.map { row ->
                JSONObject()
                    .put("dayStart", row.dayStart)
                    .put("targetKey", row.targetKey)
                    .put("count", row.count)
            }))
            .put("cooldowns", jsonArray(snapshot.cooldowns.map { row ->
                JSONObject()
                    .put("targetKey", row.targetKey)
                    .put("untilMillis", row.untilMillis)
            }))
            .put("extraGrants", jsonArray(snapshot.extraGrants.map { row ->
                JSONObject()
                    .put("dayStart", row.dayStart)
                    .put("targetKey", row.targetKey)
                    .put("reason", row.reason)
                    .put("bonusMillis", row.bonusMillis)
                    .put("sessionCeilingMillis", row.sessionCeilingMillis)
                    .put("bypassUntil", row.bypassUntil)
            }))
            .put("limitWarnings", jsonArray(snapshot.limitWarnings.map { row ->
                JSONObject()
                    .put("dayStart", row.dayStart)
                    .put("targetKey", row.targetKey)
                    .put("minutes", row.minutes)
            }))
            .put("preferences", prefs)
    }

    private fun jsonArray(rows: List<JSONObject>): JSONArray {
        val array = JSONArray()
        for (row in rows) array.put(row)
        return array
    }

    fun decode(value: Any?): SnapshotDecode {
        val obj = value as? JSONObject ?: return SnapshotDecode.Schema
        return try {
            if (exactInt(obj.opt("schema")) != SyncRules.SCHEMA) return SnapshotDecode.Schema
            if (obj.opt("app") != SyncRules.APP) return SnapshotDecode.Schema
            val exportedAt = stamp(obj.opt("exportedAt")) ?: return SnapshotDecode.Schema
            val deviceId = deviceId(obj.opt("deviceId")) ?: return SnapshotDecode.Schema
            SnapshotDecode.Ok(
                AndroidSnapshot(
                    exportedAt = exportedAt,
                    deviceId = deviceId,
                    dailyUsage = rows(array(obj, "dailyUsage", 50_000), ::dailyUsage),
                    sessions = rows(array(obj, "sessions", 80_000), ::session),
                    appCategories = rows(array(obj, "appCategories", 20_000), ::appCategory),
                    categories = rows(array(obj, "categories", 200), ::category),
                    dailyTotals = rows(array(obj, "dailyTotals", 20_000), ::dailyTotal),
                    notificationCounts = rows(array(obj, "notificationCounts", 50_000), ::notification),
                    usageLimits = rows(array(obj, "usageLimits", 5_000), ::limit),
                    instantBlocks = rows(array(obj, "instantBlocks", 5_000), ::block),
                    schedules = rows(array(obj, "schedules", 400), ::schedule),
                    webDaily = rows(array(obj, "webDaily", 50_000), ::webDaily),
                    dailyOpens = rows(array(obj, "dailyOpens", 50_000), ::dailyOpen),
                    cooldowns = rows(array(obj, "cooldowns", 5_000), ::cooldown),
                    extraGrants = rows(array(obj, "extraGrants", 20_000), ::grant),
                    limitWarnings = rows(array(obj, "limitWarnings", 50_000), ::warning),
                    preferences = preferences(obj),
                ),
            )
        } catch (fail: DecodeFail) {
            if (fail.reason == "too-large") SnapshotDecode.TooLarge else SnapshotDecode.Schema
        } catch (_: JSONException) {
            SnapshotDecode.Schema
        }
    }

    private class DecodeFail(val reason: String) : Exception()

    private fun <T> rows(array: JSONArray, parse: (Any?) -> T?): List<T> {
        val out = ArrayList<T>(array.length())
        for (index in 0 until array.length()) {
            val row = parse(array.opt(index)) ?: continue
            out.add(row)
        }
        return out
    }

    private fun array(obj: JSONObject, name: String, cap: Int): JSONArray {
        if (!obj.has(name) || obj.isNull(name)) return JSONArray()
        val value = obj.opt(name) as? JSONArray ?: throw DecodeFail("schema")
        if (value.length() > cap) throw DecodeFail("too-large")
        return value
    }

    private fun dailyUsage(value: Any?): DailyUsage? {
        val obj = value as? JSONObject ?: return null
        val day = nonNegLong(obj, "dayStart") ?: return null
        val pkg = ident(obj, "packageName", 200) ?: return null
        val duration = span(obj, "durationMillis") ?: return null
        val opens = count(obj, "openCount") ?: return null
        val hourly = hourly(obj.opt("hourlyMillis")) ?: return null
        return DailyUsage(day, pkg, duration, opens, hourly)
    }

    private fun session(value: Any?): Session? {
        val obj = value as? JSONObject ?: return null
        val pkg = ident(obj, "packageName", 200) ?: return null
        val start = nonNegLong(obj, "startMillis") ?: return null
        val end = nonNegLong(obj, "endMillis") ?: return null
        val day = nonNegLong(obj, "dayStart") ?: return null
        if (end < start || end - start > MAX_SPAN_MS) return null
        return Session(id = 0, packageName = pkg, startMillis = start, endMillis = end, dayStart = day)
    }

    private fun appCategory(value: Any?): AppCategory? {
        val obj = value as? JSONObject ?: return null
        val pkg = ident(obj, "packageName", 200) ?: return null
        val key = token(obj, "categoryKey", 80) ?: return null
        val updated = nonNegLong(obj, "updatedAt") ?: return null
        return AppCategory(pkg, key, updated)
    }

    private fun category(value: Any?): Category? {
        val obj = value as? JSONObject ?: return null
        val key = token(obj, "key", 80) ?: return null
        val label = phrase(obj, "label", 80, blank = false) ?: return null
        val color = color(obj.opt("colorArgb")) ?: return null
        val custom = bool(obj, "custom") ?: return null
        val position = count(obj, "position") ?: return null
        if (position > 10_000) return null
        return Category(key, label, color, custom, position)
    }

    private fun dailyTotal(value: Any?): DailyTotal? {
        val obj = value as? JSONObject ?: return null
        val day = nonNegLong(obj, "dayStart") ?: return null
        val duration = span(obj, "durationMillis") ?: return null
        val unlocks = count(obj, "unlockCount") ?: return null
        val hourly = hourly(obj.opt("hourlyMillis")) ?: return null
        val updated = nonNegLong(obj, "updatedAt") ?: return null
        return DailyTotal(day, duration, unlocks, hourly, updated)
    }

    private fun notification(value: Any?): NotificationCount? {
        val obj = value as? JSONObject ?: return null
        val day = nonNegLong(obj, "dayStart") ?: return null
        val pkg = ident(obj, "packageName", 200) ?: return null
        val count = count(obj, "count") ?: return null
        return NotificationCount(day, pkg, count)
    }

    private fun limit(value: Any?): UsageLimit? {
        val obj = value as? JSONObject ?: return null
        val key = token(obj, "targetKey", 300) ?: return null
        val daily = span(obj, "dailyLimitMillis") ?: return null
        val days = weekdays(obj) ?: return null
        val session = span(obj, "sessionLimitMillis") ?: return null
        val cool = span(obj, "cooldownMillis") ?: return null
        val opens = count(obj, "openLimit") ?: return null
        return UsageLimit(key, daily, days, session, cool, opens)
    }

    private fun block(value: Any?): InstantBlock? {
        val obj = value as? JSONObject ?: return null
        val key = token(obj, "targetKey", 300) ?: return null
        val until = nonNegLong(obj, "untilMillis") ?: return null
        val created = nonNegLong(obj, "createdAt") ?: return null
        return InstantBlock(key, until, created)
    }

    private fun schedule(value: Any?): BlockSchedule? {
        val obj = value as? JSONObject ?: return null
        val name = phrase(obj, "name", 80, blank = true) ?: return null
        val enabled = bool(obj, "enabled") ?: return null
        val start = minute(obj, "startMinute") ?: return null
        val end = minute(obj, "endMinute") ?: return null
        val days = weekdays(obj) ?: return null
        val packages = phrase(obj, "packagesCsv", 20_000, blank = true) ?: return null
        val domains = phrase(obj, "domainsCsv", 20_000, blank = true) ?: return null
        val kind = token(obj, "kind", 16) ?: return null
        if (kind != ScheduleKinds.WINDOW && kind != ScheduleKinds.SLEEP) return null
        return BlockSchedule(0, name, enabled, start, end, days, packages, domains, kind)
    }

    private fun webDaily(value: Any?): WebDaily? {
        val obj = value as? JSONObject ?: return null
        val day = nonNegLong(obj, "dayStart") ?: return null
        val domain = domain(obj.opt("domain")) ?: return null
        val duration = span(obj, "durationMillis") ?: return null
        val visits = count(obj, "visitCount") ?: return null
        return WebDaily(day, domain, duration, visits)
    }

    private fun dailyOpen(value: Any?): DailyOpen? {
        val obj = value as? JSONObject ?: return null
        val day = nonNegLong(obj, "dayStart") ?: return null
        val key = token(obj, "targetKey", 300) ?: return null
        val count = count(obj, "count") ?: return null
        return DailyOpen(day, key, count)
    }

    private fun cooldown(value: Any?): Cooldown? {
        val obj = value as? JSONObject ?: return null
        val key = token(obj, "targetKey", 300) ?: return null
        val until = nonNegLong(obj, "untilMillis") ?: return null
        return Cooldown(key, until)
    }

    private fun grant(value: Any?): ExtraGrant? {
        val obj = value as? JSONObject ?: return null
        val day = nonNegLong(obj, "dayStart") ?: return null
        val key = token(obj, "targetKey", 300) ?: return null
        val reason = phrase(obj, "reason", 80, blank = true) ?: return null
        val bonus = span(obj, "bonusMillis") ?: return null
        val ceiling = span(obj, "sessionCeilingMillis") ?: return null
        val bypass = nonNegLong(obj, "bypassUntil") ?: return null
        return ExtraGrant(day, key, reason, bonus, ceiling, bypass)
    }

    private fun warning(value: Any?): LimitWarning? {
        val obj = value as? JSONObject ?: return null
        val day = nonNegLong(obj, "dayStart") ?: return null
        val key = token(obj, "targetKey", 300) ?: return null
        val minutes = exactInt(obj.opt("minutes")) ?: return null
        if (minutes !in 1..1440) return null
        return LimitWarning(day, key, minutes)
    }

    private fun preferences(obj: JSONObject): SyncPreferences {
        if (!obj.has("preferences") || obj.isNull("preferences")) {
            return SyncPreferences(null, null, null, null)
        }
        val value = obj.opt("preferences") as? JSONObject ?: throw DecodeFail("schema")
        val theme = (value.opt("themeMode") as? String)?.takeIf {
            it == "system" || it == "light" || it == "dark"
        }
        val dynamic = value.opt("dynamicColor") as? Boolean
        val extra = value.opt("extraMinutesEnabled") as? Boolean
        val excluded = when {
            !value.has("excludedPackages") || value.isNull("excludedPackages") -> null
            value.opt("excludedPackages") !is JSONArray -> throw DecodeFail("schema")
            else -> {
                val array = value.getJSONArray("excludedPackages")
                if (array.length() > 2_000) throw DecodeFail("too-large")
                val items = LinkedHashSet<String>()
                for (index in 0 until array.length()) {
                    val item = array.opt(index) as? String ?: continue
                    if (item.length !in 1..200) continue
                    if (item.any { it.code < 0x20 || it.code == 0x7F }) continue
                    items.add(item)
                }
                items.sorted()
            }
        }
        return SyncPreferences(theme, dynamic, excluded, extra)
    }

    private fun hourly(value: Any?): String? {
        if (value !is String || value.length > 800) return null
        if (value.isEmpty()) return value
        val parts = value.split(',')
        if (parts.size != 24) return null
        for (part in parts) {
            val number = part.toLongOrNull() ?: return null
            if (number < 0 || number > MAX_SPAN_MS) return null
        }
        return value
    }

    private fun domain(value: Any?): String? {
        if (value !is String) return null
        val host = value.lowercase()
        if (host.length !in 1..253 || !host.contains('.')) return null
        if (host.any { it.isWhitespace() || it == '/' || it == ':' || it == '@' }) return null
        return host
    }

    private fun ident(obj: JSONObject, name: String, max: Int): String? {
        val value = obj.opt(name) as? String ?: return null
        if (value.length !in 1..max) return null
        if (value.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return null
        return value
    }

    private fun token(obj: JSONObject, name: String, max: Int): String? = ident(obj, name, max)

    private fun phrase(obj: JSONObject, name: String, max: Int, blank: Boolean): String? {
        val value = obj.opt(name) as? String ?: return null
        if (value.length > max) return null
        if (!blank && value.isBlank()) return null
        if (value.any { it.code < 0x20 || it.code == 0x7F }) return null
        return value
    }

    private fun stamp(value: Any?): String? {
        val text = value as? String ?: return null
        if (text.length !in 20..40) return null
        if (text.any { it.code !in 0x20..0x7E }) return null
        return text
    }

    private fun deviceId(value: Any?): String? {
        val text = value as? String ?: return null
        if (text.length !in 1..80) return null
        if (text.any { it.code < 0x21 || it.code > 0x7E }) return null
        return text
    }

    private fun color(value: Any?): Int? {
        val number = jsonLong(value) ?: return null
        if (number in Int.MIN_VALUE..Int.MAX_VALUE) return number.toInt()
        if (number in 0..0xFFFF_FFFFL) return number.toInt()
        return null
    }

    private fun bool(obj: JSONObject, name: String): Boolean? = obj.opt(name) as? Boolean

    private fun weekdays(obj: JSONObject): Int? {
        val number = exactInt(obj.opt("weekdaysMask")) ?: return null
        return number.takeIf { it in 0..127 }
    }

    private fun minute(obj: JSONObject, name: String): Int? {
        val number = exactInt(obj.opt(name)) ?: return null
        return number.takeIf { it in 0..1439 }
    }

    private fun count(obj: JSONObject, name: String): Int? {
        val number = exactInt(obj.opt(name)) ?: return null
        return number.takeIf { it in 0..MAX_COUNT }
    }

    private fun span(obj: JSONObject, name: String): Long? {
        val number = nonNegLong(obj, name) ?: return null
        return number.takeIf { it <= MAX_SPAN_MS }
    }

    private fun nonNegLong(obj: JSONObject, name: String): Long? {
        val number = jsonLong(obj.opt(name)) ?: return null
        return number.takeIf { it >= 0 }
    }
}

internal fun exactInt(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    is Double ->
        if (!value.isFinite() || value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            null
        } else {
            value.toInt()
        }
    else -> null
}

internal fun jsonLong(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    is Double ->
        if (!value.isFinite() || value % 1.0 != 0.0 || value < Long.MIN_VALUE.toDouble() || value > Long.MAX_VALUE.toDouble()) {
            null
        } else {
            value.toLong()
        }
    else -> null
}
