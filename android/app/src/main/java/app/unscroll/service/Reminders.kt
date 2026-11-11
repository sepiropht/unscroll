package app.unscroll.service

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.unscroll.R
import app.unscroll.data.stats.DaySlice
import app.unscroll.data.stats.UsageStatsEngine
import app.unscroll.data.stats.excluding
import app.unscroll.permissions.PermissionChecks
import app.unscroll.prefs.AppPrefs
import app.unscroll.ui.format.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Compte le temps continu pendant que l’écran est allumé et déverrouillé.
 * Un pas est borné : un réveil après une longue veille ne doit pas vider d’un coup plusieurs heures.
 */
class ReminderClock(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private var lastAt = 0L
    private var summaryCheckAt = 0L
    private var stretchPkg: String? = null
    private var stretchMillis = 0L
    private var stretchNotified = 0
    private var eyeMillis = 0L

    fun onSample(packageName: String?, counting: Boolean, now: Long) {
        val prefs = AppPrefs(app)
        val awake = screenInUse()
        val step = if (!awake || lastAt == 0L) 0L else (now - lastAt).coerceIn(0L, MAX_STEP_MS)
        lastAt = now
        val usageMinutes = prefs.usageReminderMinutes
        if (!awake || !counting || packageName == null || usageMinutes <= 0) {
            stretchPkg = null
            stretchMillis = 0L
            stretchNotified = 0
        } else if (packageName != stretchPkg) {
            stretchPkg = packageName
            stretchMillis = 0L
            stretchNotified = 0
        } else {
            stretchMillis += step
            val bucket = (stretchMillis / (usageMinutes * 60_000L)).toInt()
            if (bucket > stretchNotified && bucket >= 1) {
                val posted = GuardNotifications.usage(app, labelOf(packageName), usageMinutes * bucket)
                if (posted) stretchNotified = bucket
            }
        }
        val eyeMinutes = prefs.eyeBreakMinutes
        if (eyeMinutes <= 0) {
            eyeMillis = 0L
        } else if (awake) {
            eyeMillis += step
            if (eyeMillis >= eyeMinutes * 60_000L && GuardNotifications.eye(app, eyeMinutes)) {
                eyeMillis = 0L
            }
        }
        if (now - summaryCheckAt >= 60_000L) {
            summaryCheckAt = now
            if (DailySummary.maybePost(app)) ReminderScheduler.schedule(app)
        }
    }

    private fun screenInUse(): Boolean {
        if (power?.isInteractive != true) return false
        if (keyguard?.isKeyguardLocked == true) return false
        return true
    }

    private fun labelOf(packageName: String): String {
        return runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrElse { packageName.substringAfterLast('.') }
    }

    private companion object {
        const val MAX_STEP_MS = 30_000L
    }
}

object DailySummary {
    private val gate = Any()

    /** Pose le résumé du jour s’il est l’heure et qu’il n’est pas déjà parti. */
    fun maybePost(context: Context): Boolean {
        val app = context.applicationContext
        synchronized(gate) {
            val prefs = AppPrefs(app)
            val minute = prefs.dailySummaryMinute
            if (minute < 0) return false
            if (!PermissionChecks.hasNotifications(app)) return false
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            if (prefs.dailySummaryPostedDay == dayStart) return false
            val slot = dayStart + minute.coerceIn(0, 24 * 60 - 1) * 60_000L
            if (now < slot) return false
            GuardNotifications.summary(app, compose(app, date, dayStart))
            prefs.dailySummaryPostedDay = dayStart
            return true
        }
    }

    private fun compose(context: Context, today: LocalDate, dayStart: Long): String {
        if (!PermissionChecks.hasUsageAccess(context)) {
            return context.getString(R.string.summary_no_access)
        }
        val raw = UsageStatsEngine(context).compute(today, today.plusDays(1)).values.firstOrNull()
        val day = (raw ?: DaySlice.empty(today, dayStart)).excluding(AppPrefs(context).hiddenFromStats(context))
        if (day.totalMillis <= 0L && day.unlockCount <= 0) {
            return context.getString(R.string.summary_quiet)
        }
        val top = day.packages.maxByOrNull { it.durationMillis }?.takeIf { it.durationMillis > 0L }
        val duration = formatDuration(day.totalMillis, context)
        val resources = context.resources
        return if (top != null) {
            val label = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(top.packageName, 0)).toString()
            }.getOrElse { top.packageName.substringAfterLast('.') }
            resources.getQuantityString(
                R.plurals.summary_with_top,
                day.unlockCount,
                duration,
                day.unlockCount,
                label,
            )
        } else {
            resources.getQuantityString(R.plurals.summary_plain, day.unlockCount, duration, day.unlockCount)
        }
    }
}

object ReminderScheduler {
    const val ACTION_SUMMARY = "com.elimbi.unscroll.action.DAILY_SUMMARY"

    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(app)
        alarm.cancel(pending)
        val prefs = AppPrefs(app)
        val minute = prefs.dailySummaryMinute
        if (minute < 0) return
        val whenMillis = nextTrigger(
            System.currentTimeMillis(),
            minute,
            prefs.dailySummaryPostedDay,
            ZoneId.systemDefault(),
        )
        IdleAlarms.schedule(alarm, whenMillis, pending)
    }

    fun nextTrigger(now: Long, minuteOfDay: Int, postedDay: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val minute = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        val todaySlot = dayStart + minute * 60_000L
        if (postedDay != dayStart && now >= todaySlot) return now + 1_000L
        if (now < todaySlot) return todaySlot
        val tomorrow = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return tomorrow + minute * 60_000L
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_SUMMARY)
        return PendingIntent.getBroadcast(
            context,
            53,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ReminderScheduler.ACTION_SUMMARY) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DailySummary.maybePost(app)
            } finally {
                ReminderScheduler.schedule(app)
                pending.finish()
            }
        }
    }
}
