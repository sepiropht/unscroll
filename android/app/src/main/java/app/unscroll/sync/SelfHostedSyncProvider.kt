package app.unscroll.sync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Synchro vers le serveur choisi par la personne. Éteinte tant que l'interrupteur l'est :
 * aucune socket n'est ouverte. Le serveur ne reçoit que l'enveloppe chiffrée et le jeton.
 * La clé brute ou la phrase reste sur l'appareil. Deux blobs : `settings` et `device.android.<id>`.
 */
class SelfHostedSyncProvider(context: Context) : SyncProvider {
    private val app = context.applicationContext
    private val settings = SyncSettings(app)
    private val http = SyncHttp()

    override suspend fun push(snapshot: AndroidSnapshot): SyncTransferResult = runSync {
        val target = gate()
        val outcome = exchangeSettings(target, snapshot, upload = true)
        putUsage(target, snapshot)
        if (outcome == SettingsMove.Conflict) throw SyncStop("conflict")
        settings.setLastError(null)
        SyncTransferResult(ok = true, at = snapshot.exportedAt)
    }

    override suspend fun pull(): SyncPullResult = runPull {
        val target = gate()
        val local = AndroidSnapshotStore(app).capture()
        val pulled = exchangeSettings(target, local, upload = false)
        val usage = getUsage(target)
        if (pulled == SettingsMove.Conflict) {
            settings.setLastError("conflict")
            return@runPull SyncPullResult(ok = false, snapshot = usage, reason = "conflict")
        }
        val commit = pendingSettings
        pendingSettings = null
        settings.setLastError(null)
        SyncPullResult(
            ok = true,
            snapshot = usage,
            settings = commit?.snapshot,
            settingsRevision = commit?.revision,
            settingsEtag = commit?.etag,
            settingsBody = commit?.body,
        )
    }

    override fun status(): SyncStatus {
        val configured = SyncRules.normalizeBase(settings.baseUrl) != null &&
            SyncRules.validToken(settings.token) &&
            settings.hasSecret()
        return SyncStatus(enabled = settings.enabled, configured = configured)
    }

    private var pendingSettings: PendingSettings? = null

    private fun gate(): Target {
        if (!settings.enabled) throw SyncStop("disabled")
        val base = SyncRules.normalizeBase(settings.baseUrl) ?: throw SyncStop("invalid")
        val token = settings.token
        if (!SyncRules.validToken(token)) throw SyncStop("invalid")
        val secret = when (settings.secretKind) {
            SyncRules.SECRET_RAW -> {
                val key = SyncRules.rawKeyBytes(settings.secret) ?: throw SyncStop("invalid")
                SyncSecret.Raw(key)
            }
            SyncRules.SECRET_PASSPHRASE -> {
                val phrase = settings.secret
                if (!SyncRules.validPassphrase(phrase)) throw SyncStop("invalid")
                SyncSecret.Phrase(phrase)
            }
            else -> throw SyncStop("invalid")
        }
        val deviceId = settings.deviceId()
        if (SyncRules.deviceBlobId(deviceId) == null) throw SyncStop("invalid")
        return Target(base, token, secret, deviceId)
    }

    private fun exchangeSettings(target: Target, snapshot: AndroidSnapshot, upload: Boolean): SettingsMove {
        pendingSettings = null
        val url = SyncRules.blobUrl(target.base, "settings") ?: throw SyncStop("invalid")
        val current = http.exchange("GET", url, target.token, body = null, readBody = true)
        val remote = when (current.status) {
            404 -> null
            in 200..299 -> {
                verifyEtag(current.raw, current.etag)
                readSettings(current.body, target.secret)
            }
            401, 403 -> throw SyncStop("http-${current.status}")
            else -> throw SyncStop("http-${current.status}")
        }
        val previous = remote?.body
        val stored = settings.settingsBody.takeIf { it.isNotEmpty() }?.let { JSONObject(it) }
        val baseBody = previous ?: stored
        val localBody = SyncDocument.settingsBody(baseBody, snapshot)
        val dirty = settings.settingsBody.isNotEmpty() && localBody.toString() != settings.settingsBody
        val move = SyncDocument.settingsMove(settings.settingsRevision, dirty, remote?.revision)
        when (move) {
            SettingsMove.Conflict -> return move
            SettingsMove.Pull -> {
                val doc = remote ?: throw SyncStop("schema")
                val applied = SyncDocument.decodeSettingsSnapshot(doc.body, target.deviceId, doc.updatedAt)
                pendingSettings = PendingSettings(applied, doc.revision, current.etag.orEmpty(), doc.body.toString())
            }
            SettingsMove.Push -> {
                if (!upload) return move
                val revision = SyncDocument.nextRevision(remote?.revision)
                val plain = SyncDocument.writeSettings(revision, snapshot.exportedAt, localBody)
                val etag = try {
                    putBlob(
                        url = url,
                        target = target,
                        plain = plain,
                        existingEtag = if (remote == null) null else current.etag,
                    )
                } catch (stop: SyncStop) {
                    if (stop.reason == "http-412") throw SyncStop("conflict")
                    throw stop
                }
                settings.commitSettings(revision, etag, localBody.toString())
            }
            SettingsMove.None -> Unit
        }
        return move
    }

    private fun putUsage(target: Target, snapshot: AndroidSnapshot) {
        val id = SyncRules.deviceBlobId(target.deviceId) ?: throw SyncStop("invalid")
        val url = SyncRules.blobUrl(target.base, id) ?: throw SyncStop("invalid")
        val current = http.exchange("GET", url, target.token, body = null, readBody = true)
        val remoteRevision = when (current.status) {
            404 -> null
            in 200..299 -> {
                verifyEtag(current.raw, current.etag)
                SyncDocument.readUsage(JSONObject(openPlain(current.body, target.secret)), target.deviceId).revision
            }
            401, 403 -> throw SyncStop("http-${current.status}")
            else -> throw SyncStop("http-${current.status}")
        }
        val encoded = AndroidSnapshotCodec.encode(snapshot)
        var revision = SyncDocument.nextRevision(remoteRevision)
        val plain = SyncDocument.writeUsage(revision, snapshot.exportedAt, target.deviceId, encoded)
        val etag = try {
            putBlob(url, target, plain, if (remoteRevision == null) null else current.etag)
        } catch (stop: SyncStop) {
            if (stop.reason != "http-412" || remoteRevision == null) throw stop
            val again = http.exchange("GET", url, target.token, body = null, readBody = true)
            if (again.status !in 200..299) throw SyncStop("http-${again.status}")
            verifyEtag(again.raw, again.etag)
            val seen = SyncDocument.readUsage(JSONObject(openPlain(again.body, target.secret)), target.deviceId)
            revision = SyncDocument.nextRevision(seen.revision)
            val retry = SyncDocument.writeUsage(revision, snapshot.exportedAt, target.deviceId, encoded)
            putBlob(url, target, retry, again.etag)
        }
        settings.commitUsage(revision, etag)
    }

    private fun getUsage(target: Target): AndroidSnapshot? {
        val id = SyncRules.deviceBlobId(target.deviceId) ?: throw SyncStop("invalid")
        val url = SyncRules.blobUrl(target.base, id) ?: throw SyncStop("invalid")
        val current = http.exchange("GET", url, target.token, body = null, readBody = true)
        return when (current.status) {
            404 -> null
            in 200..299 -> {
                verifyEtag(current.raw, current.etag)
                val doc = SyncDocument.readUsage(JSONObject(openPlain(current.body, target.secret)), target.deviceId)
                when (val decoded = AndroidSnapshotCodec.decode(doc.snapshot)) {
                    is SnapshotDecode.Ok -> decoded.snapshot
                    SnapshotDecode.Schema -> throw SyncStop("schema")
                    SnapshotDecode.TooLarge -> throw SyncStop("too-large")
                }
            }
            401, 403 -> throw SyncStop("http-${current.status}")
            else -> throw SyncStop("http-${current.status}")
        }
    }

    private fun putBlob(url: String, target: Target, plain: JSONObject, existingEtag: String?): String {
        val text = plain.toString()
        val revealed = when (val secret = target.secret) {
            is SyncSecret.Raw -> SyncRules.base64UrlEncode(secret.key)
            is SyncSecret.Phrase -> secret.passphrase
        }
        if (text.contains(target.token) || (revealed.isNotEmpty() && text.contains(revealed))) {
            throw SyncStop("invalid")
        }
        val bytes = SyncCrypto.seal(text, target.secret).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > SyncRules.MAX_HTTP_BYTES) throw SyncStop("too-large")
        val create = existingEtag == null
        val put = http.exchange(
            method = "PUT",
            url = url,
            token = target.token,
            body = bytes,
            readBody = false,
            ifMatch = if (create) null else existingEtag,
            ifNoneMatch = if (create) "*" else null,
        )
        if (put.status == 412) throw SyncStop("http-412")
        if (put.status !in 200..299) throw SyncStop("http-${put.status}")
        val etag = put.etag ?: throw SyncStop("schema")
        if (etag != SyncRules.etagFor(bytes)) throw SyncStop("schema")
        return etag
    }

    private fun readSettings(body: String, secret: SyncSecret): SettingsDoc {
        return SyncDocument.readSettings(JSONObject(openPlain(body, secret)))
    }

    private fun verifyEtag(raw: ByteArray, header: String?) {
        val tag = header ?: throw SyncStop("schema")
        if (tag != SyncRules.etagFor(raw)) throw SyncStop("schema")
    }

    private fun openPlain(body: String, secret: SyncSecret): String {
        if (body.length > SyncRules.MAX_HTTP_BYTES) throw SyncStop("too-large")
        val envelope = try {
            JSONObject(body)
        } catch (_: JSONException) {
            throw SyncStop("schema")
        }
        return SyncCrypto.open(envelope, secret)
    }

    private suspend fun runSync(block: suspend () -> SyncTransferResult): SyncTransferResult =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stop: SyncStop) {
                settings.setLastError(stop.reason)
                SyncTransferResult(ok = false, reason = stop.reason)
            } catch (_: JSONException) {
                settings.setLastError("schema")
                SyncTransferResult(ok = false, reason = "schema")
            } catch (_: Exception) {
                settings.setLastError("network")
                SyncTransferResult(ok = false, reason = "network")
            }
        }

    private suspend fun runPull(block: suspend () -> SyncPullResult): SyncPullResult =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (stop: SyncStop) {
                settings.setLastError(stop.reason)
                SyncPullResult(ok = false, reason = stop.reason)
            } catch (_: JSONException) {
                settings.setLastError("schema")
                SyncPullResult(ok = false, reason = "schema")
            } catch (_: Exception) {
                settings.setLastError("network")
                SyncPullResult(ok = false, reason = "network")
            }
        }

    private data class Target(
        val base: String,
        val token: String,
        val secret: SyncSecret,
        val deviceId: String,
    )

    private data class PendingSettings(
        val snapshot: AndroidSnapshot,
        val revision: Int,
        val etag: String,
        val body: String,
    )
}
