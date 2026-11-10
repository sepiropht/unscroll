package app.unscroll.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHashTest {
    private val salt = "00112233445566778899aabbccddeeff"

    /** Vecteur PBKDF2-HMAC-SHA256, 1 000 tours, calculé hors de cette classe. */
    private val pbkdf2At1000 = "b8ad0f1dacec0beb4f418fadcb2ee5939ef45823391bb89692028dd1b67e9cef"

    /** SHA-256(sel UTF-8 || 0x00 || code UTF-8), même sel. */
    private val legacy = "ae2985d7dff8c72ccf6f341f4073bd4f5cb8c3454709065f0ff5f189734c0c0e"

    @Test
    fun acceptsFourToSixDigitsOnly() {
        assertTrue(PinHash.wellFormed("1234"))
        assertTrue(PinHash.wellFormed("12345"))
        assertTrue(PinHash.wellFormed("123456"))
        assertFalse(PinHash.wellFormed("123"))
        assertFalse(PinHash.wellFormed("1234567"))
        assertFalse(PinHash.wellFormed("12a4"))
        assertFalse(PinHash.wellFormed("12 4"))
        assertFalse(PinHash.wellFormed(""))
    }

    @Test
    fun pbkdf2MatchesIndependentVector() {
        assertEquals(PinHash.KDF_PBKDF2, "pbkdf2-sha256")
        assertEquals(100_000, PinHash.ITERATIONS)
        assertEquals(pbkdf2At1000, PinHash.pbkdf2("1234", salt, 1000))
        assertEquals(PinHash.pbkdf2("1234", salt, PinHash.ITERATIONS), PinHash.pbkdf2("1234", salt))
        assertNotEquals(pbkdf2At1000, PinHash.pbkdf2("9999", salt, 1000))
        val hash = PinHash.pbkdf2("1234", salt, 1000)!!
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(hash.contains("1234"))
    }

    @Test
    fun rejectsWeakOrBrokenSaltParameters() {
        assertNull(PinHash.pbkdf2("1234", salt, 999))
        assertNull(PinHash.pbkdf2("1234", "abc", 1000))
        assertNull(PinHash.pbkdf2("1234", "zz", 1000))
        assertNull(PinHash.pbkdf2("1234", "", 1000))
    }

    @Test
    fun legacySha256StaysDistinctFromPbkdf2() {
        assertEquals(legacy, PinHash.legacySha256("1234", salt))
        assertNotEquals(legacy, PinHash.legacySha256("0000", salt))
        assertNotEquals(legacy, PinHash.pbkdf2("1234", salt, 1000))
    }

    @Test
    fun comparisonIsOnBytesNotOnText() {
        assertTrue(PinHash.same(pbkdf2At1000, pbkdf2At1000))
        assertTrue(PinHash.same("aa", "AA"))
        assertFalse(PinHash.same(pbkdf2At1000, legacy))
        assertFalse(PinHash.same(null, legacy))
        assertFalse(PinHash.same("aa", "ab"))
        assertFalse(PinHash.same("aabb", "aa"))
        assertFalse(PinHash.same("", legacy))
        assertFalse(PinHash.same("zz", "zz"))
    }

    @Test
    fun freshSaltIsSixteenBytesAndUsable() {
        val fresh = PinHash.newSalt()
        assertEquals(32, fresh.length)
        assertTrue(fresh.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(fresh, PinHash.newSalt())
        val hash = PinHash.pbkdf2("1234", fresh, 1000)!!
        assertTrue(PinHash.same(hash, hash))
    }
}
