package app.unscroll.sync

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal class SyncStop(val reason: String) : Exception()

/** Clé brute de 32 octets, ou phrase PBKDF2. Les deux n'ouvrent pas la même enveloppe. */
internal sealed class SyncSecret {
    class Raw(val key: ByteArray) : SyncSecret()
    class Phrase(val passphrase: String) : SyncSecret()
}

/**
 * Enveloppe AES-256-GCM, étiquette collée au chiffré (WebCrypto).
 * `kdf: raw` : la clé AES est les 32 octets, sans sel ni PBKDF2.
 * `kdf: PBKDF2-SHA-256` : la phrase UTF-8, sel 16 octets, 150 000 tours à l'écriture.
 */
internal object SyncCrypto {
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val KEY_BYTES = 32
    private val random = SecureRandom()

    fun seal(plaintext: String, secret: SyncSecret): JSONObject = when (secret) {
        is SyncSecret.Raw -> sealRaw(plaintext, secret.key)
        is SyncSecret.Phrase -> seal(plaintext, secret.passphrase)
    }

    fun open(value: JSONObject, secret: SyncSecret): String = when (secret) {
        is SyncSecret.Raw -> open(value, secret.key)
        is SyncSecret.Phrase -> open(value, secret.passphrase)
    }

    fun seal(plaintext: String, passphrase: String): JSONObject {
        val salt = ByteArray(SALT_BYTES)
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(salt)
        random.nextBytes(iv)
        return seal(plaintext, passphrase, SyncRules.DEFAULT_ITERATIONS, salt, iv)
    }

    fun sealRaw(plaintext: String, key: ByteArray): JSONObject {
        if (key.size != KEY_BYTES) throw SyncStop("invalid")
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        return sealRaw(plaintext, key, iv)
    }

    fun sealRaw(plaintext: String, key: ByteArray, iv: ByteArray): JSONObject {
        if (key.size != KEY_BYTES || iv.size != IV_BYTES) throw SyncStop("invalid")
        val plain = plaintext.toByteArray(Charsets.UTF_8)
        if (plain.size > SyncRules.MAX_PLAINTEXT_BYTES) throw SyncStop("too-large")
        return JSONObject()
            .put("version", 1)
            .put("alg", "AES-GCM")
            .put("kdf", "raw")
            .put("iterations", 0)
            .put("iv", b64(iv))
            .put("ciphertext", b64(aesGcm(key, iv, plain, encrypt = true)))
    }

    fun seal(plaintext: String, passphrase: String, iterations: Int, salt: ByteArray, iv: ByteArray): JSONObject {
        if (!SyncRules.validPassphrase(passphrase)) throw SyncStop("invalid")
        if (iterations !in 1..SyncRules.MAX_ITERATIONS) throw SyncStop("invalid")
        val plain = plaintext.toByteArray(Charsets.UTF_8)
        if (plain.size > SyncRules.MAX_PLAINTEXT_BYTES) throw SyncStop("too-large")
        val key = pbkdf2(passphrase.toByteArray(Charsets.UTF_8), salt, iterations, KEY_BYTES)
        return JSONObject()
            .put("version", 1)
            .put("alg", "AES-GCM")
            .put("kdf", "PBKDF2-SHA-256")
            .put("iterations", iterations)
            .put("salt", b64(salt))
            .put("iv", b64(iv))
            .put("ciphertext", b64(aesGcm(key, iv, plain, encrypt = true)))
    }

    fun open(value: JSONObject, passphrase: String): String {
        if (!SyncRules.validPassphrase(passphrase)) throw SyncStop("decrypt")
        if (exactInt(value.opt("version")) != 1 || value.opt("alg") != "AES-GCM") throw SyncStop("schema")
        return when (value.opt("kdf")) {
            "raw" -> throw SyncStop("decrypt")
            "PBKDF2-SHA-256" -> openPbkdf(value, passphrase)
            else -> throw SyncStop("schema")
        }
    }

    fun open(value: JSONObject, key: ByteArray): String {
        if (key.size != KEY_BYTES) throw SyncStop("decrypt")
        if (exactInt(value.opt("version")) != 1 || value.opt("alg") != "AES-GCM") throw SyncStop("schema")
        return when (value.opt("kdf")) {
            "PBKDF2-SHA-256" -> throw SyncStop("decrypt")
            "raw" -> openRaw(value, key)
            else -> throw SyncStop("schema")
        }
    }

    private fun openRaw(value: JSONObject, key: ByteArray): String {
        if (value.has("iterations") && !value.isNull("iterations")) {
            val iterations = exactInt(value.opt("iterations")) ?: throw SyncStop("schema")
            if (iterations != 0) throw SyncStop("schema")
        }
        if (value.has("salt") && !value.isNull("salt")) {
            val salt = value.opt("salt")
            if (salt != "") throw SyncStop("schema")
        }
        val iv = decode(value.opt("iv") as? String, 64) ?: throw SyncStop("schema")
        val data = decode(value.opt("ciphertext") as? String, SyncRules.MAX_HTTP_BYTES) ?: throw SyncStop("schema")
        if (iv.size != IV_BYTES || data.size <= TAG_BYTES) throw SyncStop("schema")
        return decrypt(key, iv, data)
    }

    private fun openPbkdf(value: JSONObject, passphrase: String): String {
        val iterations = exactInt(value.opt("iterations")) ?: throw SyncStop("schema")
        if (iterations !in 1..SyncRules.MAX_ITERATIONS) throw SyncStop("schema")
        val salt = decode(value.opt("salt") as? String, 128) ?: throw SyncStop("schema")
        val iv = decode(value.opt("iv") as? String, 64) ?: throw SyncStop("schema")
        val data = decode(value.opt("ciphertext") as? String, SyncRules.MAX_HTTP_BYTES) ?: throw SyncStop("schema")
        if (salt.size != SALT_BYTES || iv.size != IV_BYTES || data.size <= TAG_BYTES) throw SyncStop("schema")
        val key = try {
            pbkdf2(passphrase.toByteArray(Charsets.UTF_8), salt, iterations, KEY_BYTES)
        } catch (_: GeneralSecurityException) {
            throw SyncStop("decrypt")
        }
        return decrypt(key, iv, data)
    }

    private fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): String {
        return try {
            val plain = aesGcm(key, iv, data, encrypt = false)
            if (plain.size > SyncRules.MAX_PLAINTEXT_BYTES) throw SyncStop("too-large")
            String(plain, Charsets.UTF_8)
        } catch (stop: SyncStop) {
            throw stop
        } catch (_: GeneralSecurityException) {
            throw SyncStop("decrypt")
        }
    }

    private fun aesGcm(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val mode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
        return cipher.doFinal(data)
    }

    /** Vecteur du protocole, itérations 1 000. Sert aux essais, pas à l'écriture. */
    fun vectorMatches(): Boolean {
        val salt = ByteArray(SALT_BYTES) { 1 }
        val iv = ByteArray(IV_BYTES) { 2 }
        val key = pbkdf2("secret-passphrase".toByteArray(Charsets.UTF_8), salt, 1000, KEY_BYTES)
        val expect = "f40f4c2521c20f9adc0692938f7c66454380a8b81be4d4a9706a48c4e2002006"
        if (key.joinToString("") { "%02x".format(it) } != expect) return false
        val sealed = seal("{\"version\":1,\"revision\":1}", "secret-passphrase", 1000, salt, iv)
        if (sealed.opt("ciphertext") != "9HA/yDguHSi5iTip16kYVpDGU6OcyFzo8c8tU7fIf9AIx/DYs5y+ULpd") return false
        return open(sealed, "secret-passphrase") == "{\"version\":1,\"revision\":1}"
    }

    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val block = mac.macLength
        val count = (length + block - 1) / block
        val out = ByteArray(count * block)
        val index = ByteArray(4)
        val u = ByteArray(block)
        val t = ByteArray(block)
        for (i in 1..count) {
            index[0] = (i ushr 24).toByte()
            index[1] = (i ushr 16).toByte()
            index[2] = (i ushr 8).toByte()
            index[3] = i.toByte()
            mac.update(salt)
            mac.update(index)
            mac.doFinal(u, 0)
            u.copyInto(t)
            for (round in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            t.copyInto(out, (i - 1) * block)
        }
        return out.copyOf(length)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String?, maxChars: Int): ByteArray? {
        if (value == null || value.isEmpty() || value.length > maxChars || value.length % 4 != 0) return null
        if (!value.matches(B64)) return null
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.isEmpty()) return null
        return decoded
    }

    private val B64 = Regex("^[A-Za-z0-9+/]+={0,2}$")
}
