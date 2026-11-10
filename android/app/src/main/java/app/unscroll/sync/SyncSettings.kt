package app.unscroll.sync

import android.content.Context
import java.util.UUID

/**
 * Adresse, jeton et secret. Fichier à part, exclu des sauvegardes Android.
 * Le secret reste en clair local : une clé brute, ou une phrase qui dérive la clé.
 * L'ancienne clé `passphrase` est encore lue.
 */
class SyncSettings(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)

    val baseUrl: String
        get() = sp.getString(KEY_URL, "").orEmpty()

    val token: String
        get() = sp.getString(KEY_TOKEN, "").orEmpty()

    /** `raw`, `passphrase`, ou vide. */
    val secretKind: String
        get() = resolved().kind

    /** Clé base64url, ou phrase. Vide si rien d'utilisable n'est enregistré. */
    val secret: String
        get() = resolved().secret

    /** Phrase seule. Vide en mode clé brute. */
    val passphrase: String
        get() = if (secretKind == SyncRules.SECRET_PASSPHRASE) secret else ""

    fun hasToken(): Boolean = token.isNotEmpty()

    fun hasPassphrase(): Boolean = secretKind == SyncRules.SECRET_PASSPHRASE

    fun hasSecret(): Boolean = secret.isNotEmpty()

    fun deviceId(): String {
        val current = sp.getString(KEY_DEVICE, null)
        if (!current.isNullOrBlank()) return current
        val created = UUID.randomUUID().toString()
        sp.edit().putString(KEY_DEVICE, created).commit()
        return created
    }

    val settingsRevision: Int
        get() = sp.getInt(KEY_SET_REV, 0)

    val settingsEtag: String
        get() = sp.getString(KEY_SET_ETAG, "").orEmpty()

    /** Corps `body` appliqué, sérialisé. Vide tant qu'aucun réglage distant n'a été repris. */
    val settingsBody: String
        get() = sp.getString(KEY_SET_BODY, "").orEmpty()

    val usageRevision: Int
        get() = sp.getInt(KEY_USE_REV, 0)

    val usageEtag: String
        get() = sp.getString(KEY_USE_ETAG, "").orEmpty()

    val lastError: String?
        get() = sp.getString(KEY_LAST_ERROR, null)?.takeIf { it.isNotEmpty() }

    fun save(enabled: Boolean, baseUrl: String, token: String, secretKind: String, secret: String) {
        val previous = SyncRules.normalizeBase(this.baseUrl)
        val next = SyncRules.normalizeBase(baseUrl)
        val editor = sp.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_URL, baseUrl)
            .putString(KEY_TOKEN, token)
            .putString(KEY_KIND, secretKind)
            .putString(KEY_SECRET, secret)
        if (secretKind == SyncRules.SECRET_PASSPHRASE) {
            editor.putString(KEY_PHRASE, secret)
        } else {
            editor.remove(KEY_PHRASE)
        }
        if (previous != next) {
            editor.remove(KEY_SET_REV)
                .remove(KEY_SET_ETAG)
                .remove(KEY_SET_BODY)
                .remove(KEY_USE_REV)
                .remove(KEY_USE_ETAG)
                .remove(KEY_LAST_ERROR)
        }
        editor.commit()
    }

    private fun resolved(): Resolved {
        val kind = sp.getString(KEY_KIND, "").orEmpty()
        val secret = sp.getString(KEY_SECRET, "").orEmpty()
        if (kind == SyncRules.SECRET_RAW && SyncRules.isRawKey(secret)) {
            return Resolved(SyncRules.SECRET_RAW, secret)
        }
        if (kind == SyncRules.SECRET_PASSPHRASE && SyncRules.validPassphrase(secret)) {
            return Resolved(SyncRules.SECRET_PASSPHRASE, secret)
        }
        val legacy = sp.getString(KEY_PHRASE, "").orEmpty()
        if (SyncRules.validPassphrase(legacy)) return Resolved(SyncRules.SECRET_PASSPHRASE, legacy)
        if (SyncRules.isRawKey(secret)) return Resolved(SyncRules.SECRET_RAW, secret)
        return Resolved("", "")
    }

    fun commitSettings(revision: Int, etag: String, body: String) {
        sp.edit()
            .putInt(KEY_SET_REV, revision)
            .putString(KEY_SET_ETAG, etag)
            .putString(KEY_SET_BODY, body)
            .commit()
    }

    fun commitUsage(revision: Int, etag: String) {
        sp.edit()
            .putInt(KEY_USE_REV, revision)
            .putString(KEY_USE_ETAG, etag)
            .commit()
    }

    fun setLastError(reason: String?) {
        if (reason.isNullOrEmpty()) {
            sp.edit().remove(KEY_LAST_ERROR).commit()
        } else {
            sp.edit().putString(KEY_LAST_ERROR, reason).commit()
        }
    }

    fun clear() {
        sp.edit().clear().commit()
    }

    companion object {
        const val FILE = "temps_ecran_sync"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_PHRASE = "passphrase"
        private const val KEY_KIND = "secret_kind"
        private const val KEY_SECRET = "secret"
        private const val KEY_DEVICE = "device_id"

        private data class Resolved(val kind: String, val secret: String)
        private const val KEY_SET_REV = "settings_revision"
        private const val KEY_SET_ETAG = "settings_etag"
        private const val KEY_SET_BODY = "settings_body"
        private const val KEY_USE_REV = "usage_revision"
        private const val KEY_USE_ETAG = "usage_etag"
        private const val KEY_LAST_ERROR = "last_error"
    }
}
