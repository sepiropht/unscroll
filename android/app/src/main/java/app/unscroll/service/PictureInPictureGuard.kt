package app.unscroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Une fenêtre flottante reste au-dessus d’une superposition. Un appui au centre la rouvre
 * en plein écran, là où l’écran de pause peut la couvrir. Aucun autre geste n’est envoyé.
 */
internal object PictureInPictureGuard {
    @Suppress("DEPRECATION")
    fun expand(service: AccessibilityService, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val windows = try {
            service.windows
        } catch (_: Exception) {
            null
        } ?: return false
        for (info in windows) {
            if (!isPip(info)) continue
            val root = try {
                info.root
            } catch (_: Exception) {
                null
            } ?: continue
            val pkg = try {
                root.packageName?.toString()
            } catch (_: Exception) {
                null
            }
            if (pkg != packageName) {
                runCatching { root.recycle() }
                continue
            }
            val clicked = runCatching { root.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            val bounds = Rect()
            info.getBoundsInScreen(bounds)
            runCatching { root.recycle() }
            val tapped = tap(service, bounds)
            return clicked || tapped
        }
        return false
    }

    private fun isPip(info: AccessibilityWindowInfo): Boolean {
        return try {
            info.type == AccessibilityWindowInfo.TYPE_APPLICATION && info.isInPictureInPictureMode
        } catch (_: Exception) {
            false
        }
    }

    private fun tap(service: AccessibilityService, bounds: Rect): Boolean {
        if (bounds.width() < 2 || bounds.height() < 2) return false
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }
}
