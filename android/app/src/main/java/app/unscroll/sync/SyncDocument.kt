package app.unscroll.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal enum class SettingsMove { Pull, Push, Conflict, None }

internal data class SettingsDoc(
    val revision: Int,
    val updatedAt: String,
    val body: JSONObject,
)

internal data class UsageDoc(
    val revision: Int,
    val updatedAt: String,
    val deviceId: String,
    val snapshot: JSONObject,
)

/**
 * Deux textes clairs : `settings` (dernier écrit gagne sur `revision`)
 * et `device.android.<id>` (instantané de cet appareil seulement).
 */
internal object SyncDocument {
    fun settingsMove(localApplied: Int, dirty: Boolean, remoteRevision: Int?): SettingsMove {
        if (remoteRevision == null) return SettingsMove.Push
        if (remoteRevision > localApplied && dirty) return SettingsMove.Conflict
        if (remoteRevision > localApplied) return SettingsMove.Pull
        if (dirty) return SettingsMove.Push
        return SettingsMove.None
    }

    fun nextRevision(remote: Int?): Int {
        val next = if (remote == null) 1 else remote + 1
        if (remote != null && remote !in 0..SyncRules.MAX_REVISION) throw SyncStop("schema")
        if (next !in 1..SyncRules.MAX_REVISION) throw SyncStop("schema")
        return next
    }

    fun writeSettings(revision: Int, updatedAt: String, body: JSONObject): JSONObject {
        if (revision !in 0..SyncRules.MAX_REVISION) throw SyncStop("schema")
        return JSONObject()
            .put("version", 1)
            .put("kind", "settings")
            .put("revision", revision)
            .put("updatedAt", updatedAt)
            .put("body", body)
    }

    fun readSettings(root: JSONObject): SettingsDoc {
        if (exactInt(root.opt("version")) != 1) throw SyncStop("schema")
        if (root.opt("kind") != "settings") throw SyncStop("schema")
        val revision = exactInt(root.opt("revision")) ?: throw SyncStop("schema")
        if (revision !in 0..SyncRules.MAX_REVISION) throw SyncStop("schema")
        val updatedAt = root.opt("updatedAt") as? String ?: throw SyncStop("schema")
        val body = root.opt("body") as? JSONObject ?: throw SyncStop("schema")
        return SettingsDoc(revision, updatedAt, JSONObject(body.toString()))
    }

    fun writeUsage(
        revision: Int,
        updatedAt: String,
        deviceId: String,
        snapshot: JSONObject,
    ): JSONObject {
        if (!SyncRules.validDeviceId(deviceId)) throw SyncStop("invalid")
        if (revision !in 0..SyncRules.MAX_REVISION) throw SyncStop("schema")
        return JSONObject()
            .put("version", 1)
            .put("kind", "usage")
            .put("revision", revision)
            .put("updatedAt", updatedAt)
            .put("platform", "android")
            .put("deviceId", deviceId)
            .put("snapshot", snapshot)
    }

    fun readUsage(root: JSONObject, deviceId: String): UsageDoc {
        if (exactInt(root.opt("version")) != 1) throw SyncStop("schema")
        if (root.opt("kind") != "usage") throw SyncStop("schema")
        if (root.opt("platform") != "android") throw SyncStop("schema")
        if (root.opt("deviceId") != deviceId) throw SyncStop("schema")
        val revision = exactInt(root.opt("revision")) ?: throw SyncStop("schema")
        if (revision !in 0..SyncRules.MAX_REVISION) throw SyncStop("schema")
        val updatedAt = root.opt("updatedAt") as? String ?: throw SyncStop("schema")
        val snapshot = root.opt("snapshot") as? JSONObject ?: throw SyncStop("schema")
        return UsageDoc(revision, updatedAt, deviceId, JSONObject(snapshot.toString()))
    }

    /**
     * Corps partagé. Les clés conseillées sont remplies depuis cet appareil.
     * Les clés inconnues déjà présentes sont gardées.
     */
    fun settingsBody(previous: JSONObject?, snapshot: AndroidSnapshot): JSONObject {
        val encoded = AndroidSnapshotCodec.encode(snapshot)
        val body = if (previous == null) JSONObject() else JSONObject(previous.toString())
        mergeObject(body, "preferences", encoded.optJSONObject("preferences") ?: JSONObject())
        body.put("limits", encoded.optJSONArray("usageLimits") ?: JSONArray())
        body.put("categories", encoded.optJSONArray("categories") ?: JSONArray())
        body.put("schedules", encoded.optJSONArray("schedules") ?: JSONArray())
        if (!body.has("blocklist")) body.put("blocklist", JSONArray())
        if (!body.has("whitelist")) body.put("whitelist", JSONArray())
        val android = (body.opt("android") as? JSONObject) ?: JSONObject()
        android.put("appCategories", encoded.optJSONArray("appCategories") ?: JSONArray())
        android.put("instantBlocks", encoded.optJSONArray("instantBlocks") ?: JSONArray())
        body.put("android", android)
        return body
    }

    /** Instantané minimal pour réappliquer seulement les réglages. */
    fun settingsSnapshotJson(body: JSONObject, deviceId: String, updatedAt: String): JSONObject {
        val android = body.opt("android") as? JSONObject
        return JSONObject()
            .put("schema", SyncRules.SCHEMA)
            .put("app", SyncRules.APP)
            .put("exportedAt", updatedAt)
            .put("deviceId", deviceId)
            .put("preferences", body.opt("preferences") ?: JSONObject())
            .put("usageLimits", body.opt("limits") ?: JSONArray())
            .put("categories", body.opt("categories") ?: JSONArray())
            .put("schedules", body.opt("schedules") ?: JSONArray())
            .put("appCategories", android?.opt("appCategories") ?: JSONArray())
            .put("instantBlocks", android?.opt("instantBlocks") ?: JSONArray())
    }

    fun decodeSettingsSnapshot(body: JSONObject, deviceId: String, updatedAt: String): AndroidSnapshot {
        val json = settingsSnapshotJson(body, deviceId, updatedAt)
        return when (val decoded = AndroidSnapshotCodec.decode(json)) {
            is SnapshotDecode.Ok -> decoded.snapshot
            SnapshotDecode.TooLarge -> throw SyncStop("too-large")
            SnapshotDecode.Schema -> throw SyncStop("schema")
        }
    }

    private fun mergeObject(body: JSONObject, key: String, local: JSONObject) {
        val existing = body.opt(key) as? JSONObject
        if (existing == null) {
            body.put(key, local)
            return
        }
        val copy = try {
            JSONObject(existing.toString())
        } catch (_: JSONException) {
            throw SyncStop("schema")
        }
        val names = local.keys()
        while (names.hasNext()) {
            val name = names.next()
            copy.put(name, local.get(name))
        }
        body.put(key, copy)
    }
}
