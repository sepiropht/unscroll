package app.unscroll.sync

import app.unscroll.data.db.DailyUsage as DailyUsageFq
import app.unscroll.data.db.UsageLimit as UsageLimitFq

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDocumentTest {
    @Test
    fun settingsRoundTripKeepsUnknownKeys() {
        val previous = JSONObject()
            .put("whitelist", JSONArray().put("keep.example"))
            .put("note", "desktop")
        val body = SyncDocument.settingsBody(previous, sample())
        assertEquals("desktop", body.getString("note"))
        assertEquals("keep.example", body.getJSONArray("whitelist").getString(0))
        assertTrue(body.has("preferences"))
        assertTrue(body.has("limits"))
        assertTrue(body.has("categories"))
        assertTrue(body.has("schedules"))
        assertTrue(body.has("blocklist"))

        val written = SyncDocument.writeSettings(12, AT, body)
        val read = SyncDocument.readSettings(written)
        assertEquals(12, read.revision)
        assertEquals(AT, read.updatedAt)
        assertEquals("desktop", read.body.getString("note"))

        val snapshot = SyncDocument.decodeSettingsSnapshot(read.body, DEVICE, AT)
        assertEquals(DEVICE, snapshot.deviceId)
        assertEquals("dark", snapshot.preferences.themeMode)
        assertEquals(1, snapshot.usageLimits.size)
        assertEquals(30_000L, snapshot.usageLimits[0].dailyLimitMillis)
    }

    @Test
    fun usageRoundTripIsThisDeviceSnapshot() {
        val encoded = AndroidSnapshotCodec.encode(sample())
        val written = SyncDocument.writeUsage(3, AT, DEVICE, encoded)
        assertEquals(1, written.getInt("version"))
        assertEquals("usage", written.getString("kind"))
        assertEquals("android", written.getString("platform"))
        assertEquals(DEVICE, written.getString("deviceId"))
        val read = SyncDocument.readUsage(written, DEVICE)
        assertEquals(3, read.revision)
        val decoded = AndroidSnapshotCodec.decode(read.snapshot)
        assertTrue(decoded is SnapshotDecode.Ok)
        assertEquals(DEVICE, (decoded as SnapshotDecode.Ok).snapshot.deviceId)
        assertEquals(1, decoded.snapshot.dailyUsage.size)
    }

    @Test
    fun settingsMoveConflictsWhenDirtyAndRemoteAhead() {
        assertEquals(SettingsMove.Push, SyncDocument.settingsMove(0, false, null))
        assertEquals(SettingsMove.Pull, SyncDocument.settingsMove(1, false, 4))
        assertEquals(SettingsMove.Push, SyncDocument.settingsMove(4, true, 4))
        assertEquals(SettingsMove.Conflict, SyncDocument.settingsMove(2, true, 5))
        assertEquals(SettingsMove.None, SyncDocument.settingsMove(5, false, 5))
        assertEquals(1, SyncDocument.nextRevision(null))
        assertEquals(6, SyncDocument.nextRevision(5))
    }

    @Test
    fun baseAndEtag() {
        assertEquals("https://box.local/sync", SyncRules.normalizeBase("https://box.local/sync/"))
        assertEquals("https://box.local/sync", SyncRules.normalizeBase("https://box.local/sync/v1/snapshot"))
        assertEquals(
            "https://box.local/sync/v1/blobs/settings",
            SyncRules.blobUrl("https://box.local/sync/", "settings"),
        )
        assertEquals(
            "https://sync.unscroll.elimbi.com/v1/accounts",
            SyncRules.accountUrl("https://sync.unscroll.elimbi.com"),
        )
        assertEquals(
            "https://box.local/sync/v1/accounts",
            SyncRules.accountUrl("https://box.local/sync/v1/accounts/"),
        )
        assertEquals(SyncRules.DEFAULT_SYNC_URL, SyncRules.normalizeBase(SyncRules.DEFAULT_SYNC_URL + "/"))
        assertEquals(
            "https://box.local/sync/v1/blobs/device.android.$DEVICE",
            SyncRules.blobUrl("https://box.local/sync", "device.android.$DEVICE"),
        )
        val bytes = "abc".toByteArray()
        val tag = SyncRules.etagFor(bytes)
        assertEquals(tag, SyncRules.strongEtag("\"$tag\""))
        assertEquals(null, SyncRules.strongEtag("W/\"$tag\""))
        assertEquals(tag, SyncRules.etagFor(bytes))
    }

    private fun sample(): AndroidSnapshot = AndroidSnapshot(
        exportedAt = AT,
        deviceId = DEVICE,
        dailyUsage = listOf(
            DailyUsageFq(
                dayStart = 0,
                packageName = "app.one",
                durationMillis = 1000,
                openCount = 1,
                hourlyMillis = List(24) { "0" }.joinToString(","),
            ),
        ),
        sessions = emptyList(),
        appCategories = emptyList(),
        categories = emptyList(),
        dailyTotals = emptyList(),
        notificationCounts = emptyList(),
        usageLimits = listOf(
            UsageLimitFq(
                targetKey = "app:app.one",
                dailyLimitMillis = 30_000,
                weekdaysMask = 127,
                sessionLimitMillis = 0,
                cooldownMillis = 0,
                openLimit = 0,
            ),
        ),
        instantBlocks = emptyList(),
        schedules = emptyList(),
        webDaily = emptyList(),
        dailyOpens = emptyList(),
        cooldowns = emptyList(),
        extraGrants = emptyList(),
        limitWarnings = emptyList(),
        preferences = SyncPreferences(
            themeMode = "dark",
            dynamicColor = false,
            excludedPackages = emptyList(),
            extraMinutesEnabled = true,
        ),
    )

    private companion object {
        const val AT = "2026-11-05T12:00:00.000Z"
        const val DEVICE = "device-android-1"
    }
}
