package app.unscroll.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.unscroll.MainActivity
import app.unscroll.R
import app.unscroll.permissions.PermissionChecks

object GuardNotifications {
    const val CHANNEL_WARNINGS = "limit_alerts"
    const val CHANNEL_FOCUS = "focus_timer"
    const val CHANNEL_USAGE = "usage_reminders"
    const val CHANNEL_EYE = "eye_breaks"
    const val CHANNEL_SUMMARY = "daily_summary"
    const val FOCUS_NOTIFICATION_ID = 42
    const val EXTRA_ROUTE = "open_route"
    const val EXTRA_START_FOCUS = "start_focus"
    private const val ID_USAGE = 51
    private const val ID_EYE = 52
    private const val ID_SUMMARY = 53

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val warnings = NotificationChannel(
            CHANNEL_WARNINGS,
            context.getString(R.string.channel_warnings),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_warnings_body)
        }
        val focus = NotificationChannel(
            CHANNEL_FOCUS,
            context.getString(R.string.channel_focus),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_focus_body)
        }
        val usage = NotificationChannel(
            CHANNEL_USAGE,
            context.getString(R.string.channel_usage),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_usage_body)
        }
        val eye = NotificationChannel(
            CHANNEL_EYE,
            context.getString(R.string.channel_eye),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_eye_body)
        }
        val summary = NotificationChannel(
            CHANNEL_SUMMARY,
            context.getString(R.string.channel_summary),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_summary_body)
        }
        manager.createNotificationChannel(warnings)
        manager.createNotificationChannel(focus)
        manager.createNotificationChannel(usage)
        manager.createNotificationChannel(eye)
        manager.createNotificationChannel(summary)
    }

    /** @return false si la notification n’a pas été posée. */
    fun usage(context: Context, label: String, minutes: Int): Boolean {
        if (!PermissionChecks.hasNotifications(context) || minutes <= 0) return false
        ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val text = context.getString(R.string.usage_reminder_body, label, minutes)
        manager.notify(ID_USAGE, reminder(context, CHANNEL_USAGE, context.getString(R.string.usage_reminder_title), text))
        return true
    }

    /** @return false si la notification n’a pas été posée. */
    fun eye(context: Context, minutes: Int): Boolean {
        if (!PermissionChecks.hasNotifications(context) || minutes <= 0) return false
        ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val text = context.getString(R.string.eye_body, minutes)
        manager.notify(ID_EYE, reminder(context, CHANNEL_EYE, context.getString(R.string.eye_title), text))
        return true
    }

    fun summary(context: Context, text: String) {
        if (!PermissionChecks.hasNotifications(context)) return
        ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(ID_SUMMARY, reminder(context, CHANNEL_SUMMARY, context.getString(R.string.summary_title), text))
    }

    private fun reminder(context: Context, channel: String, title: String, text: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_hourglass)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, null))
            .build()

    fun warn(context: Context, targetKey: String, label: String, minutes: Int) {
        ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val text = if (minutes <= 1) {
            context.getString(R.string.warn_one_minute, label)
        } else {
            context.getString(R.string.warn_five_minutes, label)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_stat_hourglass)
            .setContentTitle(context.getString(R.string.warn_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, null))
            .build()
        manager.notify(targetKey.hashCode(), notification)
    }

    fun openApp(context: Context, route: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (route != null) putExtra(EXTRA_ROUTE, route)
        }
        val request = if (route == null) 1 else route.hashCode()
        return PendingIntent.getActivity(
            context,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
