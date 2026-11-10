package app.unscroll.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleWindowsTest {
    private fun noise(packageName: String): Boolean {
        return packageName == "app.unscroll" ||
            packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName == "com.ime"
    }

    @Test
    fun focusedPaneIsForegroundAndOtherPaneStaysExposed() {
        val layout = VisibleWindows.layout(
            listOf(
                AppWindow("com.chat", focused = false, active = false, pictureInPicture = false),
                AppWindow("com.video", focused = true, active = true, pictureInPicture = false),
            ),
            ::noise,
        )
        assertEquals("com.video", layout.foreground)
        assertEquals(listOf("com.chat"), layout.exposed)
        assertTrue(layout.pictureInPicture.isEmpty())
    }

    @Test
    fun pictureInPictureIsNotTheForeground() {
        val layout = VisibleWindows.layout(
            listOf(
                AppWindow("com.home", focused = true, active = true, pictureInPicture = false),
                AppWindow("com.video", focused = false, active = false, pictureInPicture = true),
            ),
            ::noise,
        )
        assertEquals("com.home", layout.foreground)
        assertEquals(listOf("com.video"), layout.exposed)
        assertEquals(setOf("com.video"), layout.pictureInPicture)
    }

    @Test
    fun pictureInPictureComesBeforeTheOtherPane() {
        val layout = VisibleWindows.layout(
            listOf(
                AppWindow("com.notes", focused = false, active = false, pictureInPicture = false),
                AppWindow("com.video", focused = false, active = false, pictureInPicture = true),
                AppWindow("com.home", focused = true, active = true, pictureInPicture = false),
            ),
            ::noise,
        )
        assertEquals(listOf("com.video", "com.notes"), layout.exposed)
    }

    @Test
    fun noiseWindowsAreDropped() {
        val layout = VisibleWindows.layout(
            listOf(
                AppWindow("com.ime", focused = true, active = true, pictureInPicture = false),
                AppWindow("app.unscroll", focused = false, active = true, pictureInPicture = false),
                AppWindow("com.chat", focused = false, active = false, pictureInPicture = false),
            ),
            ::noise,
        )
        assertEquals("com.chat", layout.foreground)
        assertTrue(layout.exposed.isEmpty())
    }

    @Test
    fun onlyAFloatingWindowHasNoForeground() {
        val layout = VisibleWindows.layout(
            listOf(AppWindow("com.video", focused = false, active = true, pictureInPicture = true)),
            ::noise,
        )
        assertNull(layout.foreground)
        assertEquals(listOf("com.video"), layout.exposed)
    }

    @Test
    fun activeWindowWinsWhenNoneIsFocused() {
        val layout = VisibleWindows.layout(
            listOf(
                AppWindow("com.left", focused = false, active = false, pictureInPicture = false),
                AppWindow("com.right", focused = false, active = true, pictureInPicture = false),
            ),
            ::noise,
        )
        assertEquals("com.right", layout.foreground)
        assertEquals(listOf("com.left"), layout.exposed)
    }
}
