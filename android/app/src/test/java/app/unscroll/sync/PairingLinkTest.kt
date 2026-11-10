package app.unscroll.sync

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkTest {
    @Test
    fun goldenRawKeyIsThirtyTwoBytes() {
        val key = ByteArray(32) { 0x11 }
        assertEquals(RAW_KEY, Base64.getUrlEncoder().withoutPadding().encodeToString(key))
        assertTrue(SyncRules.isRawKey(RAW_KEY))
        assertEquals(32, SyncRules.rawKeyBytes(RAW_KEY)!!.size)
    }

    @Test
    fun encodeWritesRawAndPassphraseUris() {
        assertEquals(RAW_GOLDEN, PairingLink.encode(RAW))
        assertEquals(RAW_GOLDEN, PairingLink.encode(material(RAW.baseUrl + "/", RAW.token, "raw", RAW.secret)))
        assertEquals(RAW, PairingLink.decode(RAW_GOLDEN))
        assertEquals(RAW, PairingLink.decode(RAW_GOLDEN.replace("&m=raw", "")))
        assertEquals(RAW, PairingLink.decode("  \n$RAW_GOLDEN\n"))
        assertEquals(PHRASE_GOLDEN, PairingLink.encode(PHRASE))
        assertEquals(PHRASE, PairingLink.decode(PHRASE_GOLDEN))
        assertNull(PairingLink.decode(PHRASE_GOLDEN.replace("&m=pbkdf2", "")))
        assertEquals(PHRASE, PairingLink.decode(PHRASE_GOLDEN.replace("unscroll://pair?", "UNSCROLL://Pair?")))
        assertEquals(PHRASE, PairingLink.decode(PHRASE_GOLDEN.replace("unscroll://pair?", "unscroll://pair/?")))
        assertEquals(PHRASE, PairingLink.decode("$PHRASE_GOLDEN&future=1"))
        assertFalse(PHRASE_GOLDEN.contains(PHRASE.secret))
        assertTrue(RAW_GOLDEN.contains("m=raw"))
        val phraseK = PHRASE_GOLDEN.substringAfter("k=").substringBefore("&")
        assertTrue(phraseK.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertTrue(PHRASE_GOLDEN.contains("k=$phraseK&m=pbkdf2"))
    }

    @Test
    fun aThirtyTwoBytePassphraseStaysAPassphraseOnlyWithMode() {
        val secret = "a".repeat(32)
        val link = PairingLink.encode(material(PHRASE.baseUrl, PHRASE.token, "pbkdf2", secret))
        assertEquals(material(PHRASE.baseUrl, PHRASE.token, "pbkdf2", secret), PairingLink.decode(link))
        val asRaw = PairingLink.decode(link.replace("&m=pbkdf2", ""))
        assertEquals("raw", asRaw!!.mode)
        assertNotEquals(secret, asRaw.secret)
    }

    @Test
    fun roundTripsFrenchLettersSymbolsAndALongPassphrase() {
        val samples = listOf(
            "clé sécrète été",
            "pass+word/test==",
            "  secret  ",
            "😀😀😀😀",
            "\uFEFFsecret!",
            "é".repeat(200),
        )
        for (secret in samples) {
            val link = PairingLink.encode(material(PHRASE.baseUrl, PHRASE.token, "pbkdf2", secret))
            assertFalse(link.contains(secret))
            val k = link.substringAfter("k=").substringBefore("&")
            assertTrue(k.matches(Regex("^[A-Za-z0-9_-]+$")))
            assertTrue(link.endsWith("k=$k&m=pbkdf2"))
            assertEquals(material(PHRASE.baseUrl, PHRASE.token, "pbkdf2", secret), PairingLink.decode(link))
        }
        val token = "a+b/c=d&e~!*'()"
        val link = PairingLink.encode(material("http://127.0.0.1:9/sync/", token, "pbkdf2", "clé sécrète été"))
        assertEquals(
            material("http://127.0.0.1:9/sync", token, "pbkdf2", "clé sécrète été"),
            PairingLink.decode(link),
        )
    }

    @Test
    fun rejectsABadSchemeVersionHostOrMissingField() {
        assertNull(PairingLink.decode(""))
        assertNull(PairingLink.decode("   "))
        assertNull(PairingLink.decode(RAW_GOLDEN + "x".repeat(SyncRules.PAIRING_LINK_MAX)))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("unscroll://", "https://")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("://pair", "://join")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("://pair", "://pair/extra")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("://pair", "://user:pw@pair")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("://pair", "://pair:80?")))
        assertNull(PairingLink.decode("$RAW_GOLDEN#frag"))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("v=1", "v=2")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("v=1", "v=01")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("v=1&", "")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("&k=$RAW_KEY", "")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("u=https%3A%2F%2Fsync.example%2Fbox&", "")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("t=good-token&", "")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("v=1", "v=1&v=1")))
        assertNull(PairingLink.decode("$RAW_GOLDEN&m=pbkdf2"))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("m=raw", "m=RAW")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("m=raw", "m=")))
        assertNull(
            PairingLink.decode(
                "unscroll:pair?v=1&u=https%3A%2F%2Fsync.example%2Fbox&t=good-token&k=c2VjcmV0LXBhc3NwaHJhc2U&m=pbkdf2",
            ),
        )
    }

    @Test
    fun rejectsABadUrlTokenPaddingStandardBase64AndInvalidUtf8() {
        val encodedUser = encode( "https://user:secret@sync.example/box")
        assertNull(PairingLink.decode(PHRASE_GOLDEN.replace("u=https%3A%2F%2Fsync.example%2Fbox", "u=$encodedUser")))
        val queried = encode("https://sync.example/box?token=1")
        assertNull(PairingLink.decode(PHRASE_GOLDEN.replace("u=https%3A%2F%2Fsync.example%2Fbox", "u=$queried")))
        assertNull(PairingLink.decode(PHRASE_GOLDEN.replace("t=good-token", "t=short")))
        assertNull(PairingLink.decode(PHRASE_GOLDEN.replace("k=c2VjcmV0LXBhc3NwaHJhc2U", "k=c2VjcmV0LXBhc3NwaHJhc2U=")))

        val link = PairingLink.encode(material(PHRASE.baseUrl, PHRASE.token, "pbkdf2", "~~~~aaaa"))
        assertTrue(link.contains("k=fn5-fmFhYWE&m=pbkdf2"))
        assertNull(PairingLink.decode(link.replace("k=fn5-fmFhYWE", "k=fn5%2BfmFhYWE")))
        assertNull(PairingLink.decode(link.replace("k=fn5-fmFhYWE", "k=c3ViamVjdHM%2F")))

        val badK = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
        assertNull(PairingLink.decode(link.replace("k=fn5-fmFhYWE", "k=$badK")))
        val shortK = Base64.getUrlEncoder().withoutPadding().encodeToString("short".toByteArray(Charsets.UTF_8))
        assertNull(PairingLink.decode(link.replace("k=fn5-fmFhYWE", "k=$shortK")))
        assertNull(PairingLink.decode(RAW_GOLDEN.replace("k=$RAW_KEY", "k=c2VjcmV0LXBhc3NwaHJhc2U"))) // gitleaks:allow (test fixture)
    }

    @Test
    fun encodeRefusesIncompleteMaterialWithoutEchoingTheSecret() {
        val attempts = listOf(
            material("notaurl", RAW.token, "raw", RAW.secret),
            material("https://user:secret@sync.example/box", RAW.token, "raw", RAW.secret),
            material(RAW.baseUrl, "short", "raw", RAW.secret),
            material(RAW.baseUrl, RAW.token, "raw", "short"),
            material(PHRASE.baseUrl, PHRASE.token, "pbkdf2", "short"),
            material(PHRASE.baseUrl, PHRASE.token, "pbkdf2", "é".repeat(201)),
        )
        for (input in attempts) {
            try {
                PairingLink.encode(input)
                throw AssertionError("encode should throw")
            } catch (error: IllegalArgumentException) {
                assertEquals("invalid", error.message)
                assertFalse(error.message!!.contains("secret"))
                assertFalse(error.message!!.contains(input.secret))
            }
        }
    }

    @Test
    fun generatedRawKeysDifferAndAreNotPrinted() {
        val first = SyncRules.generateRawKey()
        val second = SyncRules.generateRawKey()
        assertEquals(43, first.length)
        assertEquals(43, second.length)
        assertTrue(SyncRules.isRawKey(first))
        assertTrue(SyncRules.isRawKey(second))
        assertFalse(first == second)
    }

    private fun encode(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%7E", "~")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
    }

    private fun material(baseUrl: String, token: String, mode: String, secret: String) =
        PairingMaterial(baseUrl, token, mode, secret)

    private companion object {
        const val RAW_KEY = "ERERERERERERERERERERERERERERERERERERERERERE"
        val PHRASE = PairingMaterial(
            "https://sync.example/box",
            "good-token",
            "pbkdf2",
            "secret-passphrase",
        )
        val RAW = PairingMaterial(
            "https://sync.example/box",
            "good-token",
            "raw",
            RAW_KEY,
        )
        const val PHRASE_GOLDEN =
            "unscroll://pair?v=1&u=https%3A%2F%2Fsync.example%2Fbox&t=good-token&k=c2VjcmV0LXBhc3NwaHJhc2U&m=pbkdf2"
        const val RAW_GOLDEN =
            "unscroll://pair?v=1&u=https%3A%2F%2Fsync.example%2Fbox&t=good-token&k=ERERERERERERERERERERERERERERERERERERERERERE&m=raw"
    }
}
