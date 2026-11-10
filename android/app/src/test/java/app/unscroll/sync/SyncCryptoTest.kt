package app.unscroll.sync

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {
    @Test
    fun protocolVectorMatches() {
        assertTrue(SyncCrypto.vectorMatches())
        val salt = ByteArray(16) { 1 }
        val iv = ByteArray(12) { 2 }
        val plain = "{\"version\":1,\"revision\":1}"
        val sealed = SyncCrypto.seal(plain, PHRASE, 1000, salt, iv)
        assertEquals("9HA/yDguHSi5iTip16kYVpDGU6OcyFzo8c8tU7fIf9AIx/DYs5y+ULpd", sealed.getString("ciphertext"))
        assertEquals(plain, SyncCrypto.open(sealed, PHRASE))
        assertEquals(
            sealed.getString("ciphertext"),
            SyncCrypto.seal(plain, PHRASE, 1000, salt, iv).getString("ciphertext"),
        )
    }

    @Test
    fun sealHidesTheTextAndRoundTripsAfterParse() {
        val plain = "PLANT-TEXT-9f3c"
        val sealed = SyncCrypto.seal(plain, PHRASE, 1000, ByteArray(16) { 4 }, ByteArray(12) { 5 })
        assertEquals(1, sealed.getInt("version"))
        assertEquals("AES-GCM", sealed.getString("alg"))
        assertEquals("PBKDF2-SHA-256", sealed.getString("kdf"))
        assertEquals(1000, sealed.getInt("iterations"))
        assertEquals(16, Base64.getDecoder().decode(sealed.getString("salt")).size)
        assertEquals(12, Base64.getDecoder().decode(sealed.getString("iv")).size)
        val text = sealed.toString()
        assertFalse(text.contains(plain))
        assertFalse(text.contains(PHRASE))
        assertEquals(plain, SyncCrypto.open(JSONObject(text), PHRASE))
    }

    @Test
    fun defaultIterationsRoundTrip() {
        val plain = "bonjour écran"
        val sealed = SyncCrypto.seal(plain, PHRASE)
        assertEquals(SyncRules.DEFAULT_ITERATIONS, sealed.getInt("iterations"))
        val other = SyncCrypto.seal(plain, PHRASE)
        assertNotEquals(sealed.getString("salt"), other.getString("salt"))
        assertEquals(plain, SyncCrypto.open(sealed, PHRASE))
        assertEquals(plain, SyncCrypto.open(other, PHRASE))
    }

    @Test
    fun wrongPassphraseOrTamperedTagDoesNotOpen() {
        val sealed = SyncCrypto.seal("bonjour", PHRASE, 1000, ByteArray(16) { 7 }, ByteArray(12) { 8 })
        assertStop("decrypt") { SyncCrypto.open(sealed, "another-passphrase-ok") }
        assertStop("decrypt") { SyncCrypto.open(sealed, "short") }
        val raw = Base64.getDecoder().decode(sealed.getString("ciphertext"))
        raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0x01).toByte()
        sealed.put("ciphertext", Base64.getEncoder().encodeToString(raw))
        assertStop("decrypt") { SyncCrypto.open(sealed, PHRASE) }
    }

    @Test
    fun rawKeySealsWithoutSaltAndDoesNotOpenWithAPassphrase() {
        val key = ByteArray(32) { 0x11 }
        val plain = "bonjour écran"
        val sealed = SyncCrypto.sealRaw(plain, key)
        assertEquals(1, sealed.getInt("version"))
        assertEquals("AES-GCM", sealed.getString("alg"))
        assertEquals("raw", sealed.getString("kdf"))
        assertEquals(0, sealed.getInt("iterations"))
        assertFalse(sealed.has("salt"))
        assertEquals(12, Base64.getDecoder().decode(sealed.getString("iv")).size)
        val text = sealed.toString()
        assertFalse(text.contains(plain))
        assertEquals(plain, SyncCrypto.open(JSONObject(text), key))
        val other = SyncCrypto.sealRaw(plain, key)
        assertNotEquals(sealed.getString("iv"), other.getString("iv"))
        assertStop("decrypt") { SyncCrypto.open(sealed, PHRASE) }
        val pbkdf = SyncCrypto.seal(plain, PHRASE, 1000, ByteArray(16) { 1 }, ByteArray(12) { 2 })
        assertStop("decrypt") { SyncCrypto.open(pbkdf, key) }
        assertEquals(plain, SyncCrypto.open(pbkdf, PHRASE))

        val omitted = JSONObject(text)
        omitted.remove("iterations")
        assertEquals(plain, SyncCrypto.open(omitted, key))
        omitted.put("salt", "")
        assertEquals(plain, SyncCrypto.open(omitted, key))
        omitted.put("salt", Base64.getEncoder().encodeToString(ByteArray(16) { 3 }))
        assertStop("schema") { SyncCrypto.open(omitted, key) }

        val badIterations = JSONObject(text)
        badIterations.put("iterations", 1)
        assertStop("schema") { SyncCrypto.open(badIterations, key) }
        val flipped = JSONObject(text)
        val raw = Base64.getDecoder().decode(flipped.getString("ciphertext"))
        raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0x01).toByte()
        flipped.put("ciphertext", Base64.getEncoder().encodeToString(raw))
        assertStop("decrypt") { SyncCrypto.open(flipped, key) }
        assertStop("invalid") { SyncCrypto.sealRaw("x", ByteArray(16)) }
        assertStop("decrypt") { SyncCrypto.open(sealed, ByteArray(16)) }
    }

    @Test
    fun rejectsBadParametersBeforeDerivingAKey() {
        assertStop("invalid") { SyncCrypto.seal("x", "short") }
        assertStop("invalid") { SyncCrypto.seal("x", PHRASE, 0, ByteArray(16), ByteArray(12)) }
        assertStop("invalid") {
            SyncCrypto.seal("x", PHRASE, SyncRules.MAX_ITERATIONS + 1, ByteArray(16), ByteArray(12))
        }
        assertStop("too-large") {
            SyncCrypto.seal("a".repeat(SyncRules.MAX_PLAINTEXT_BYTES + 1), PHRASE, 1000, ByteArray(16), ByteArray(12))
        }
        val sealed = SyncCrypto.seal("ok", PHRASE, 1000, ByteArray(16) { 3 }, ByteArray(12) { 3 })
        sealed.put("iterations", SyncRules.MAX_ITERATIONS + 1)
        assertStop("schema") { SyncCrypto.open(sealed, PHRASE) }
        sealed.put("iterations", 1000)
        sealed.put("version", 2)
        assertStop("schema") { SyncCrypto.open(sealed, PHRASE) }
        sealed.put("version", 1)
        sealed.put("alg", "AES-CBC")
        assertStop("schema") { SyncCrypto.open(sealed, PHRASE) }
        sealed.put("alg", "AES-GCM")
        sealed.put("kdf", "scrypt")
        assertStop("schema") { SyncCrypto.open(sealed, PHRASE) }
        sealed.put("kdf", "PBKDF2-SHA-256")
        sealed.put("iv", Base64.getEncoder().encodeToString(ByteArray(8)))
        assertStop("schema") { SyncCrypto.open(sealed, PHRASE) }
    }

    private fun assertStop(reason: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("attendu $reason")
        } catch (stop: SyncStop) {
            assertEquals(reason, stop.reason)
        }
    }

    private companion object {
        const val PHRASE = "secret-passphrase"
    }
}
