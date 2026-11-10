package app.unscroll.sync

import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Lien `unscroll://pair?v=1&u=&t=&k=&m=raw|pbkdf2`.
 * `k` est du base64url sans padding, mis tel quel dans la requête.
 * `m` absent et `k` de 32 octets veut dire raw. Une phrase dont l'UTF-8 fait 32 octets
 * doit porter `m=pbkdf2`, sinon ces octets sont lus comme une clé.
 */
object PairingLink {
    private val WRAP = Regex("[\\t\\n\\r\\u000B\\u000C \\u00A0\\uFEFF]+")

    fun encode(input: PairingMaterial): String {
        if (input.mode != SyncRules.SECRET_RAW && input.mode != "pbkdf2") {
            throw IllegalArgumentException("invalid")
        }
        val baseUrl = SyncRules.normalizeBase(input.baseUrl) ?: throw IllegalArgumentException("invalid")
        if (!SyncRules.validToken(input.token)) throw IllegalArgumentException("invalid")
        val k = when (input.mode) {
            SyncRules.SECRET_RAW -> {
                if (!SyncRules.isRawKey(input.secret)) throw IllegalArgumentException("invalid")
                input.secret
            }
            else -> {
                if (!SyncRules.validPassphrase(input.secret)) throw IllegalArgumentException("invalid")
                val bytes = utf8Bytes(input.secret) ?: throw IllegalArgumentException("invalid")
                SyncRules.base64UrlEncode(bytes)
            }
        }
        return "unscroll://pair?v=1&u=${encodeURIComponent(baseUrl)}&t=${encodeURIComponent(input.token)}&k=$k&m=${input.mode}"
    }

    fun decode(input: String?): PairingMaterial? {
        if (input == null || input.isEmpty() || input.length > SyncRules.PAIRING_LINK_MAX) return null
        val compact = input.trim().replace(WRAP, "")
        if (compact.isEmpty() || compact.length > SyncRules.PAIRING_LINK_MAX) return null
        val uri = try {
            URI(compact)
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals("unscroll", ignoreCase = true)) return null
        val host = uri.host ?: return null
        if (!host.equals("pair", ignoreCase = true)) return null
        val path = uri.rawPath ?: ""
        if (path != "" && path != "/") return null
        if (uri.port != -1 || !uri.rawUserInfo.isNullOrEmpty() || uri.rawFragment != null) return null
        val query = parseQuery(uri.rawQuery) ?: return null
        val version = sole(query, "v")
        val base = sole(query, "u")
        val token = sole(query, "t")
        val k = sole(query, "k")
        val modeValues = query["m"]
        if (modeValues != null && modeValues.size != 1) return null
        if (version != "1" || base == null || token == null || k == null) return null
        val baseUrl = SyncRules.normalizeBase(base) ?: return null
        if (!SyncRules.validToken(token)) return null
        val modeValue = modeValues?.firstOrNull()
        if (modeValue == null || modeValue == SyncRules.SECRET_RAW) {
            if (modeValue == null && !SyncRules.isRawKey(k)) return null
            if (modeValue == SyncRules.SECRET_RAW && !SyncRules.isRawKey(k)) return null
            if (!SyncRules.isRawKey(k)) return null
            return PairingMaterial(baseUrl, token, SyncRules.SECRET_RAW, k)
        }
        if (modeValue == "pbkdf2") {
            val passphrase = passphraseFromK(k) ?: return null
            return PairingMaterial(baseUrl, token, "pbkdf2", passphrase)
        }
        return null
    }

    private fun passphraseFromK(k: String): String? {
        val bytes = SyncRules.base64UrlDecode(k) ?: return null
        val text = utf8(bytes) ?: return null
        if (!SyncRules.validPassphrase(text)) return null
        return text
    }

    private fun sole(query: Map<String, List<String>>, key: String): String? {
        val values = query[key] ?: return null
        if (values.size != 1) return null
        return values[0]
    }

    private fun parseQuery(raw: String?): Map<String, List<String>>? {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val rawKey = if (eq < 0) part else part.substring(0, eq)
            val rawValue = if (eq < 0) "" else part.substring(eq + 1)
            val key = percentDecode(rawKey) ?: return null
            val value = percentDecode(rawValue) ?: return null
            out.getOrPut(key) { mutableListOf() }.add(value)
        }
        return out
    }

    /** Décode les `%HH`. Un `+` reste un plus : ce n'est pas un espace. */
    private fun percentDecode(raw: String): String? {
        val bytes = ArrayList<Byte>(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%') {
                if (i + 2 >= raw.length) return null
                val hex = raw.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16) ?: return null
                bytes.add(value.toByte())
                i += 3
            } else if (c.code in 0x20..0x7E) {
                bytes.add(c.code.toByte())
                i += 1
            } else {
                return null
            }
        }
        return utf8(bytes.toByteArray())
    }

    private fun utf8Bytes(text: String): ByteArray? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val back = utf8(bytes) ?: return null
        if (back != text) return null
        return bytes
    }

    private fun utf8(bytes: ByteArray): String? {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun encodeURIComponent(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%7E", "~")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
    }
}

class PairingMaterial(
    val baseUrl: String,
    val token: String,
    val mode: String,
    val secret: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingMaterial) return false
        return baseUrl == other.baseUrl && token == other.token && mode == other.mode && secret == other.secret
    }

    override fun hashCode(): Int {
        var result = baseUrl.hashCode()
        result = 31 * result + token.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + secret.hashCode()
        return result
    }

    override fun toString(): String = "PairingMaterial(mode=$mode)"
}
