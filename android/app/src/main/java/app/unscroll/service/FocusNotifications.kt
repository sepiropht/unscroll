package app.unscroll.service

import app.unscroll.MainActivity as MainActivityFq
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.unscroll.R
import app.unscroll.data.achieve.AchievementStore
import app.unscroll.data.db.UnscrollDatabase
import app.unscroll.data.guard.FocusWindows
import app.unscroll.prefs.AppPrefs
import app.unscroll.prefs.PinGate
import app.unscroll.ui.AppRoutes
import app.unscroll.ui.format.formatHoursAndMinutes
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Affiche une notification ordinaire (pas un service au premier plan) pendant
 * une session de concentration. Le blocage des applications est assuré par
 * [GuardEngine] via UsageStats et la superposition.
 */
object FocusNotifications {
    const val ACTION_STOP = "com.elimbi.unscroll.FOCUS_STOP"
    const val ACTION_STOP_MANUAL = "com.elimbi.unscroll.FOCUS_STOP_MANUAL"
    const val ACTION_SKIP_ROUTINE = "com.elimbi.unscroll.FOCUS_SKIP_ROUTINE"
    const val ACTION_ALIGN = "com.elimbi.unscroll.FOCUS_ALIGN"
    const val ACTION_ENDED = "com.elimbi.unscroll.FOCUS_ENDED"
    const val EXTRA_ROUTINE_ID = "routine_id"

    fun start(context: Context) {
        val app = context.applicationContext
        refresh(app)
        GuardService.ensure(app)
        GuardWatchdog.schedule(app)
        FocusTileService.requestListening(app)
    }

    fun haltIfRunning(context: Context) {
        NotificationManagerCompat.from(context.applicationContext)
            .cancel(GuardNotifications.FOCUS_NOTIFICATION_ID)
    }

    fun isActive(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        if (FocusSessions.manualRunning(AppPrefs(app), now)) return true
        // Cheap path: prefs only. Routine check is done in refresh().
        return false
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            publish(app)
        }
    }

    suspend fun publish(context: Context) {
        val app = context.applicationContext
        val shown = resolve(app)
        noteFocus(app, shown)
        if (shown == null) {
            NotificationManagerCompat.from(app).cancel(GuardNotifications.FOCUS_NOTIFICATION_ID)
            cancelEndAlarm(app)
            FocusTileService.requestListening(app)
            GuardService.ensure(app)
            return
        }
        post(app, shown)
        scheduleEndAlarm(app, shown.until)
        FocusTileService.requestListening(app)
        GuardService.ensure(app)
    }

    private suspend fun resolve(context: Context): Shown? {
        val now = System.currentTimeMillis()
        val prefs = AppPrefs(context)
        if (FocusSessions.manualRunning(prefs, now)) return Shown.Manual(prefs.focusUntilMillis)
        val active = FocusSessionKeeper.activeRoutine(context, now) ?: return null
        return Shown.Routine(active.routine.id, active.until, active.routine.name)
    }

    private fun noteFocus(context: Context, shown: Shown?) {
        val store = AchievementStore.get(context)
        when (shown) {
            is Shown.Manual -> {
                val started = AppPrefs(context).focusStartedMillis
                if (started > 0L) store.armManual(started, shown.until)
            }
            is Shown.Routine -> store.armRoutine(shown.id, shown.until)
            null -> store.settleDue(System.currentTimeMillis())
        }
    }

    private fun post(context: Context, shown: Shown) {
        GuardNotifications.ensure(context)
        val end = shown.until
        val left = (end - System.currentTimeMillis()).coerceAtLeast(0L)
        val leftText = formatLeft(context, left)
        val text = if (shown.routineName.isNullOrBlank()) {
            context.getString(R.string.focus_notif_remaining, leftText)
        } else {
            context.getString(R.string.focus_notif_routine, shown.routineName, leftText)
        }
        val action = if (shown.routineName.isNullOrBlank()) {
            context.getString(R.string.focus_stop)
        } else {
            context.getString(R.string.focus_skip_short)
        }
        val notification = NotificationCompat.Builder(context, GuardNotifications.CHANNEL_FOCUS)
            .setSmallIcon(R.drawable.ic_stat_hourglass)
            .setContentTitle(context.getString(R.string.focus_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(end)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(GuardNotifications.openApp(context, AppRoutes.FOCUS))
            .addAction(0, action, stopIntent(context, shown))
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(GuardNotifications.FOCUS_NOTIFICATION_ID, notification)
        }
    }

    private fun stopIntent(context: Context, shown: Shown): PendingIntent {
        val intent = Intent(context, FocusActionReceiver::class.java).setAction(ACTION_STOP)
        if (shown is Shown.Routine) intent.putExtra(EXTRA_ROUTINE_ID, shown.id)
        return PendingIntent.getBroadcast(
            context,
            7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleEndAlarm(context: Context, until: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = endPending(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pending)
            } else {
                alarm.set(AlarmManager.RTC_WAKEUP, until, pending)
            }
        }
    }

    private fun cancelEndAlarm(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(endPending(context))
    }

    private fun endPending(context: Context): PendingIntent {
        val intent = Intent(context, FocusActionReceiver::class.java).setAction(ACTION_ENDED)
        return PendingIntent.getBroadcast(
            context,
            8,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatLeft(context: Context, millis: Long): String {
        val minutes = ((millis + 59_999L) / 60_000L).coerceAtLeast(1L)
        val hours = minutes / 60L
        val rem = minutes % 60L
        return if (hours <= 0L) {
            context.resources.getQuantityString(
                R.plurals.duration_minutes,
                minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                minutes,
            )
        } else {
            formatHoursAndMinutes(hours, rem, context)
        }
    }

    private sealed interface Shown {
        val until: Long
        val routineName: String?

        data class Manual(override val until: Long) : Shown {
            override val routineName: String? = null
        }

        data class Routine(val id: Long, override val until: Long, val name: String) : Shown {
            override val routineName: String? = name
        }
    }
}

class FocusActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext
        when (action) {
            FocusNotifications.ACTION_STOP -> {
                if (PinGate.isEnabled(app)) {
                    app.startActivity(
                        Intent(app, MainActivityFq::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            )
                            putExtra(GuardNotifications.EXTRA_ROUTE, AppRoutes.FOCUS)
                        },
                    )
                    return
                }
            }
            FocusNotifications.ACTION_STOP_MANUAL -> {
                AppPrefs(app).clearFocus()
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { FocusNotifications.publish(app) } finally { pending.finish() }
                }
                return
            }
            FocusNotifications.ACTION_SKIP_ROUTINE, FocusNotifications.ACTION_STOP -> {
                // handled below
            }
            FocusNotifications.ACTION_ENDED, FocusNotifications.ACTION_ALIGN -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { FocusNotifications.publish(app) } finally { pending.finish() }
                }
                return
            }
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    FocusNotifications.ACTION_STOP -> {
                        val prefs = AppPrefs(app)
                        if (FocusSessions.manualRunning(prefs, System.currentTimeMillis())) {
                            prefs.clearFocus()
                        } else {
                            val id = intent.getLongExtra(FocusNotifications.EXTRA_ROUTINE_ID, 0L)
                            skipById(app, id)
                        }
                    }
                    FocusNotifications.ACTION_SKIP_ROUTINE -> {
                        skipById(app, intent.getLongExtra(FocusNotifications.EXTRA_ROUTINE_ID, 0L))
                    }
                }
                FocusNotifications.publish(app)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun skipById(context: Context, id: Long) {
        if (id == 0L) return
        val routine = withContext(Dispatchers.IO) {
            UnscrollDatabase.get(context).focusRoutines().find(id)
        } ?: return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val until = if (FocusWindows.isActive(now, zone, routine.startMinute, routine.durationMinutes, routine.weekdaysMask)) {
            FocusWindows.endMillis(now, zone, routine.startMinute, routine.durationMinutes)
        } else {
            now
        }
        AppPrefs(context).skipRoutine(id, until)
    }
}
