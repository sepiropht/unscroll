package app.unscroll.data.backup

import app.unscroll.data.db.AppCategory
import app.unscroll.data.db.BlockSchedule
import app.unscroll.data.db.Category
import app.unscroll.data.db.FocusRoutine
import app.unscroll.data.db.InstantBlock
import app.unscroll.data.db.ScheduleKinds
import app.unscroll.data.db.UsageLimit
import app.unscroll.data.guard.Weekdays
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArchiveCodecTest {
    @Test
    fun jsonRoundTripKeepsLimitsSchedulesAndSettings() {
        val color = 0xFF0F766E.toInt()
        val snapshot = Snapshot(
            settings = SettingsBlob(
                themeMode = "dark",
                dynamicColor = false,
                weekStartsMonday = false,
                hourFormat = "h24",
                showSystemApps = false,
                excluded = setOf("com.android.launcher"),
                extraMinutes = false,
                usageReminder = 30,
                eyeBreak = 20,
                summaryMinute = 21 * 60,
                draftMinutes = 50,
                draftPackages = setOf("com.example.notes"),
            ),
            categories = listOf(
                Category("atelier", "Atelier", color, true, 3),
            ),
            assignments = listOf(AppCategory("com.example.notes", "atelier", 0L)),
            limits = listOf(
                UsageLimit(
                    targetKey = "app:com.example.notes",
                    dailyLimitMillis = 30 * 60_000L,
                    weekdaysMask = Weekdays.bit(java.time.DayOfWeek.MONDAY),
                    sessionLimitMillis = 10 * 60_000L,
                    cooldownMillis = 5 * 60_000L,
                    openLimit = 8,
                ),
            ),
            schedules = listOf(
                BlockSchedule(
                    id = 4,
                    name = "Soir",
                    enabled = false,
                    startMinute = 22 * 60,
                    endMinute = 7 * 60,
                    weekdaysMask = Weekdays.ALL,
                    packagesCsv = "com.example.notes,com.example.video",
                    domainsCsv = "example.com",
                    kind = ScheduleKinds.WINDOW,
                ),
                BlockSchedule(
                    id = 5,
                    name = "Nuit",
                    enabled = true,
                    startMinute = 23 * 60,
                    endMinute = 6 * 60,
                    weekdaysMask = Weekdays.ALL,
                    packagesCsv = "com.android.dialer",
                    domainsCsv = "",
                    kind = ScheduleKinds.SLEEP,
                ),
            ),
            routines = listOf(
                FocusRoutine(
                    id = 2,
                    name = "Matin",
                    enabled = true,
                    startMinute = 8 * 60,
                    durationMinutes = 25,
                    weekdaysMask = Weekdays.bit(java.time.DayOfWeek.MONDAY),
                    packagesCsv = "com.example.notes",
                ),
            ),
            blocks = listOf(InstantBlock("com.example.notes", Long.MAX_VALUE, 50L)),
        )
        val json = ArchiveCodec.encode(snapshot)
        val root = JSONObject(json)
        assertEquals(1, root.getInt("format"))
        assertEquals("app.unscroll", root.getString("app"))
        val keys = root.keys().asSequence().toSet()
        assertEquals(
            setOf(
                "format",
                "app",
                "settings",
                "categories",
                "assignments",
                "limits",
                "schedules",
                "routines",
                "blocks",
            ),
            keys,
        )
        val settings = root.getJSONObject("settings").keys().asSequence().toSet()
        assertTrue(settings.none { it.contains("pin") || it.contains("token") || it.contains("passphrase") })
        assertFalse(json.contains("\"pin\""))
        val decoded = ArchiveCodec.decode(json.toByteArray())
        assertEquals(snapshot.settings, decoded.settings)
        assertEquals(snapshot.categories, decoded.categories)
        assertEquals(color, decoded.categories.single().colorArgb)
        assertEquals(snapshot.assignments, decoded.assignments)
        assertEquals(snapshot.limits, decoded.limits)
        assertEquals(snapshot.schedules, decoded.schedules)
        assertEquals(snapshot.routines, decoded.routines)
        assertEquals(snapshot.blocks, decoded.blocks)
    }

    @Test
    fun minimalFileUsesDefaultsAndDropsBrokenRows() {
        val root = base()
        root.put(
            "limits",
            JSONArray()
                .put(
                    JSONObject()
                        .put("target", "app:com.example")
                        .put("dailyMillis", -5)
                        .put("weekdays", 0xFFFF)
                        .put("sessionMillis", 1000)
                        .put("cooldownMillis", 2000)
                        .put("opens", 4),
                )
                .put(JSONObject().put("target", "app:com.example").put("dailyMillis", 9))
                .put(JSONObject().put("target", "  ").put("dailyMillis", 9)),
        )
        root.put(
            "schedules",
            JSONArray()
                .put(
                    JSONObject()
                        .put("name", "")
                        .put("kind", "window")
                        .put("startMinute", 60)
                        .put("endMinute", 120),
                )
                .put(
                    JSONObject()
                        .put("name", "Midi")
                        .put("kind", "window")
                        .put("startMinute", 100)
                        .put("endMinute", 100),
                )
                .put(
                    JSONObject()
                        .put("name", "")
                        .put("kind", "sleep")
                        .put("startMinute", 22 * 60)
                        .put("endMinute", 7 * 60),
                )
                .put(JSONObject().put("name", "Bizarre").put("kind", "other").put("startMinute", 1).put("endMinute", 2)),
        )
        val decoded = ArchiveCodec.decode(root.toString().toByteArray())
        assertEquals("system", decoded.settings.themeMode)
        assertTrue(decoded.settings.dynamicColor)
        assertTrue(decoded.settings.weekStartsMonday)
        assertEquals("system", decoded.settings.hourFormat)
        assertEquals(25, decoded.settings.draftMinutes)
        assertEquals(-1, decoded.settings.summaryMinute)
        val limit = decoded.limits.single()
        assertEquals(0L, limit.dailyLimitMillis)
        assertEquals(Weekdays.ALL, limit.weekdaysMask)
        assertEquals(1000L, limit.sessionLimitMillis)
        assertEquals(2000L, limit.cooldownMillis)
        assertEquals(4, limit.openLimit)
        val sleep = decoded.schedules.single()
        assertEquals(ScheduleKinds.SLEEP, sleep.kind)
        assertEquals("Sommeil", sleep.name)
        assertEquals(22 * 60, sleep.startMinute)
        assertEquals(7 * 60, sleep.endMinute)
    }

    @Test
    fun sundayStartAndRejectsAForeignFile() {
        val root = base()
        root.put("settings", JSONObject().put("weekStartsOn", "sunday"))
        val decoded = ArchiveCodec.decode(root.toString().toByteArray())
        assertFalse(decoded.settings.weekStartsMonday)
        assertFormat("nope")
        assertFormat("{")
        val wrongFormat = base().put("format", 2)
        assertFormat(wrongFormat.toString())
        val wrongApp = base().put("app", "other.app")
        assertFormat(wrongApp.toString())
        val missing = base()
        missing.remove("settings")
        assertFormat(missing.toString())
        val arraySettings = base().put("settings", JSONArray())
        assertFormat(arraySettings.toString())
    }

    @Test
    fun historyCsvSortsQuotesAndUsesTheGivenZone() {
        val early = LocalDate.of(2026, 1, 6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val late = LocalDate.of(2026, 1, 5).atTime(23, 30).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        val lines = listOf(
            HistoryCsvLine(early, "com.b", "B", 10L, 1),
            HistoryCsvLine(early, "com.a", "A, \"quote\"", 20L, 2),
            HistoryCsvLine(late, "=cmd", "+pkg", 3L, 4),
        )
        val csv = ArchiveCodec.renderHistory(ZoneOffset.UTC, lines)
        assertTrue(csv.startsWith("\uFEFFdate,app,package,duration_ms,opens\n"))
        val rows = csv.removePrefix("\uFEFF").lines().filter { it.isNotEmpty() }
        assertEquals("date,app,package,duration_ms,opens", rows[0])
        assertEquals("2026-01-05,'+pkg,'=cmd,3,4", rows[1])
        assertEquals("2026-01-06,\"A, \"\"quote\"\"\",com.a,20,2", rows[2])
        assertEquals("2026-01-06,B,com.b,10,1", rows[3])
        val shifted = ArchiveCodec.renderHistory(ZoneOffset.ofHours(1), listOf(lines[2]))
        assertTrue(shifted.contains("2026-01-06,'+pkg,'=cmd,3,4"))
    }

    @Test
    fun historyCsvEscapesBreaksAndAtSigns() {
        val day = LocalDate.of(2026, 1, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val csv = ArchiveCodec.renderHistory(
            ZoneOffset.UTC,
            listOf(HistoryCsvLine(day, "@app", "ligne\ndeux", 1L, 0)),
        )
        assertTrue(csv.contains("\"ligne\ndeux\",'@app,1,0"))
        val headerOnly = ArchiveCodec.renderHistory(ZoneOffset.UTC, emptyList())
        assertEquals("\uFEFFdate,app,package,duration_ms,opens\n", headerOnly)
    }

    private fun base(): JSONObject = JSONObject()
        .put("format", 1)
        .put("app", "app.unscroll")
        .put("settings", JSONObject())
        .put("categories", JSONArray())
        .put("limits", JSONArray())
        .put("schedules", JSONArray())

    private fun assertFormat(raw: String) {
        try {
            ArchiveCodec.decode(raw.toByteArray())
            fail("fichier accepté")
        } catch (error: ArchiveException) {
            assertEquals(ArchiveException.Kind.FORMAT, error.kind)
        }
    }
}
