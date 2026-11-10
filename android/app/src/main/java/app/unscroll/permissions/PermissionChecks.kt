package app.unscroll.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.unscroll.service.NotificationCounterService
import app.unscroll.service.ScreenTimeAccessibilityService
import android.content.ComponentName

data class PermissionSnapshot(
    val usage: Boolean,
    val accessibility: Boolean,
    val overlay: Boolean,
    val notifications: Boolean,
    val notificationsNeedRequest: Boolean,
    val batteryIgnored: Boolean,
    val notificationListener: Boolean,
) {
    companion object {
        fun capture(context: Context) = PermissionSnapshot(
            usage = PermissionChecks.hasUsageAccess(context),
            accessibility = PermissionChecks.isAccessibilityEnabled(context),
            overlay = PermissionChecks.canOverlay(context),
            notifications = PermissionChecks.hasNotifications(context),
            notificationsNeedRequest = Build.VERSION.SDK_INT >= 33,
            batteryIgnored = PermissionChecks.ignoresBatteryOptimizations(context),
            notificationListener = PermissionChecks.hasNotificationListener(context),
        )
    }
}

object PermissionChecks {
    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName,
                )
            }
        } catch (_: SecurityException) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!enabled) return false
        val expected = ComponentName(context, ScreenTimeAccessibilityService::class.java)
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return raw.split(':').any { flat ->
            ComponentName.unflattenFromString(flat) == expected
        }
    }

    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun ignoresBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasNotificationListener(context: Context): Boolean {
        val expected = ComponentName(context, NotificationCounterService::class.java)
        val raw = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return raw.split(':').any { flat ->
            ComponentName.unflattenFromString(flat) == expected
        }
    }
}
