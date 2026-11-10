package app.unscroll.permissions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

fun Context.openUsageAccessSettings() {
    startSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}

fun Context.openAccessibilitySettings() {
    startSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

fun Context.openOverlaySettings() {
    val withPackage = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"),
    )
    try {
        startActivity(withPackage)
    } catch (_: Exception) {
        startSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
    }
}

fun Context.openBatterySettings() {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:$packageName"),
    )
    try {
        startActivity(direct)
    } catch (_: Exception) {
        startSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun Context.startSettings(intent: Intent) {
    try {
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        val fallback = Intent(Settings.ACTION_SETTINGS)
        if (this !is Activity) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(fallback) }
    }
}
