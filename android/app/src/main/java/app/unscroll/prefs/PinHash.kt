package app.unscroll.prefs

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Empreinte du code. Le code lui-même n’est pas gardé.
 * Les nouveaux codes passent par PBKDF2-HMAC-SHA256. Une empreinte SHA-256
 * déjà enregistrée reste vérifiable, puis est remplacée à la prochaine saisie juste.
 */
internal object PinHash {
    const val KDF_PBKDF2 = "pbkdf2-sha256"
    const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun wellFormed(pin: String): Boolean = pin.length in 4..6 && pin.all { it.isDigit() }

    fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return toHex(bytes)
    }

    fun pbkdf2(pin: String, saltHex: String, iterations: Int = ITERATIONS): String? {
        val salt = fromHex(saltHex) ?: return null
        if (iterations < 1_000) return null
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            toHex(factory.generateSecret(spec).encoded)
        } catch (_: Exception) {
            null
        } finally {
            spec.clearPassword()
        }
    }

    /** Ancienne empreinte, conservée seulement pour reconnaître un code déjà posé. */
    fun legacySha256(pin: String, saltHex: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(saltHex.toByteArray(Charsets.UTF_8))
        digest.update(byteArrayOf(0))
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return toHex(digest.digest())
    }

    fun same(actualHex: String?, expectedHex: String): Boolean {
        val actual = actualHex?.let(::fromHex) ?: return false
        val expected = fromHex(expectedHex) ?: return false
        return MessageDigest.isEqual(actual, expected)
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray? {
        if (hex.length < 2 || hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
