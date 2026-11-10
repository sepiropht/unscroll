package app.unscroll.sync

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Adresse, jeton et secret, tels que le protocole de synchro les borne. */
object SyncRules {
    const val CLIENT = "android"
    const val APP = "temps-ecran-android"
    const val SCHEMA = 1
    const val MAX_REVISION = 1_000_000_000
    const val DEFAULT_ITERATIONS = 150_000
    const val MAX_ITERATIONS = 400_000
    const val MAX_URL_CHARS = 300
    const val MAX_PLAINTEXT_BYTES = 1_500_000
    const val MAX_HTTP_BYTES = 2_000_000
    const val DEFAULT_SYNC_URL = "https://sync.unscroll.elimbi.com"
    const val PAIRING_LINK_MAX = 8192
    const val SECRET_RAW = "raw"
    const val SECRET_PASSPHRASE = "passphrase"
    const val RAW_KEY_BYTES = 32

    private val BLOB_ID = Regex("^[a-z][a-z0-9._-]{0,127}$")
    private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{8,64}$")
    private val STRONG_ETAG = Regex("sha256-[0-9a-f]{64}")
    private val B64_URL = Regex("^[A-Za-z0-9_-]+$")
    private val RAW_KEY = Regex("^[A-Za-z0-9_-]{43}$")
    private val random = SecureRandom()

    fun validPassphrase(value: String): Boolean = value.length in 8..200

    fun validToken(token: String): Boolean {
        if (token.length !in 8..512) return false
        for (char in token) {
            val code = char.code
            if (code < 0x21 || code > 0x7E) return false
        }
        return true
    }

    fun validDeviceId(value: String): Boolean = DEVICE_ID.matches(value)

    /** `device.android.<deviceId>`. */
    fun deviceBlobId(deviceId: String): String? {
        if (!validDeviceId(deviceId)) return null
        val id = "device.$CLIENT.$deviceId"
        return id.takeIf { BLOB_ID.matches(it) }
    }

    /**
     * URL de base http(s), sans identifiant, sans requête, sans fragment, sans slash final.
     * Un suffixe `/v1`, `/v1/blobs` ou l'ancien `/v1/snapshot` est retiré pour ne pas doubler le chemin.
     */
    fun normalizeBase(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_CHARS) return null
        if ('\\' in trimmed || ' ' in trimmed) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!uri.rawUserInfo.isNullOrEmpty()) return null
        if (!uri.rawQuery.isNullOrEmpty() || uri.rawFragment != null) return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        val literal = when {
            host.startsWith("[") && host.endsWith("]") -> host.lowercase()
            ':' in host -> "[${host.lowercase()}]"
            else -> host.lowercase()
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        var path = (uri.rawPath ?: "").trimEnd('/')
        for (suffix in listOf("/v1/snapshot", "/v1/blobs", "/v1/accounts", "/v1")) {
            if (path.endsWith(suffix)) {
                path = path.removeSuffix(suffix)
                break
            }
        }
        path = path.trimEnd('/')
        return "$scheme://$literal$port$path"
    }

    fun blobUrl(base: String, id: String): String? {
        if (!BLOB_ID.matches(id)) return null
        val root = normalizeBase(base) ?: return null
        return "$root/v1/blobs/$id"
    }

    /** `POST {base}/v1/accounts`, sans jeton. */
    fun accountUrl(base: String): String? {
        val root = normalizeBase(base) ?: return null
        return "$root/v1/accounts"
    }

    /** 32 octets aléatoires, base64url sans padding. La clé ne quitte pas l'appareil. */
    fun generateRawKey(): String {
        val bytes = ByteArray(RAW_KEY_BYTES)
        random.nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    fun isRawKey(value: String): Boolean {
        if (!RAW_KEY.matches(value)) return false
        val decoded = base64UrlDecode(value) ?: return false
        return decoded.size == RAW_KEY_BYTES
    }

    fun rawKeyBytes(value: String): ByteArray? {
        if (!isRawKey(value)) return null
        return base64UrlDecode(value)
    }

    fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Base64url strict, sans `=` et sans l'alphabet standard. */
    fun base64UrlDecode(value: String): ByteArray? {
        if (value.isEmpty() || value.length > 1200 || !B64_URL.matches(value)) return null
        val pad = (4 - value.length % 4) % 4
        val padded = value + "=".repeat(pad)
        val decoded = try {
            Base64.getUrlDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.isEmpty() || base64UrlEncode(decoded) != value) return null
        return decoded
    }

    /** ETag fort entre guillemets. `W/` est refusé. */
    fun strongEtag(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val trimmed = header.trim()
        if (trimmed.startsWith("W/") || trimmed.startsWith("w/")) return null
        val inner = if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        return inner.takeIf { STRONG_ETAG.matches(it) }
    }

    fun etagFor(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "sha256-$hex"
    }

    fun quotedEtag(etag: String): String = "\"$etag\""
}
