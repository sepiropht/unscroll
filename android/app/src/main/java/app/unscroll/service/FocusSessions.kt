package app.unscroll.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.unscroll.data.achieve.AchievementStore
import app.unscroll.data.db.FocusRoutine
import app.unscroll.data.db.UnscrollDatabase
import app.unscroll.data.guard.FocusWindows
import app.unscroll.data.guard.Weekdays
import app.unscroll.prefs.AppPrefs
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class ActiveRoutine(
    val routine: FocusRoutine,
    val until: Long,
)

object FocusSessions {
    fun manualRunning(prefs: AppPrefs, now: Long): Boolean {
        return prefs.focusUntilMillis > now &&
            (prefs.focusPackages.isNotEmpty() || prefs.focusDomains.isNotEmpty())
    }

    /**
     * Relance le dernier créneau manuel. Si un créneau est déjà en cours, réveille seulement
     * la notification. @return false lorsqu’aucune application n’a été retenue.
     */
    fun startDraft(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = AppPrefs(app)
        val now = System.currentTimeMillis()
        if (!manualRunning(prefs, now)) {
            val packages = prefs.focusDraftPackages
            if (packages.isEmpty()) return false
            val minutes = prefs.focusDraftMinutes.coerceIn(1, 180)
            prefs.focusPackages = packages
            prefs.focusDomains = emptySet()
            prefs.focusStartedMillis = now
            prefs.focusUntilMillis = now + minutes * 60_000L
            runCatching {
                AchievementStore.get(app).armManual(prefs.focusStartedMillis, prefs.focusUntilMillis)
            }
        }
        FocusNotifications.start(app)
        FocusTileService.requestListening(app)
        return true
    }
}

object FocusSessionKeeper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun isActive(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        if (FocusSessions.manualRunning(AppPrefs(app), now)) return true
        return activeRoutine(app, now) != null
    }

    suspend fun activeRoutine(context: Context, now: Long = System.currentTimeMillis()): ActiveRoutine? {
        val app = context.applicationContext
        val zone = ZoneId.systemDefault()
        val skips = AppPrefs(app).routineSkips(now)
        return UnscrollDatabase.get(app).focusRoutines().all()
            .mapNotNull { routine ->
                if (!routine.enabled || routine.durationMinutes <= 0) return@mapNotNull null
                val skipUntil = skips[routine.id] ?: 0L
                if (skipUntil > now) return@mapNotNull null
                if (!FocusWindows.isActive(now, zone, routine.startMinute, routine.durationMinutes, routine.weekdaysMask)) {
                    return@mapNotNull null
                }
                ActiveRoutine(
                    routine,
                    FocusWindows.endMillis(now, zone, routine.startMinute, routine.durationMinutes),
                )
            }
            .minByOrNull { it.until }
    }

    /** Démarre la notification si un créneau est ouvert, et rafraîchit la tuile. */
    fun ensure(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (isActive(app)) {
                FocusNotifications.start(app)
            } else {
                FocusTileService.requestListening(app)
            }
        }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { align(app) }
    }

    suspend fun align(context: Context) {
        val app = context.applicationContext
        if (isActive(app)) {
            FocusNotifications.start(app)
        } else {
            FocusNotifications.refresh(app)
        }
        FocusTileService.requestListening(app)
    }
}

object FocusRoutineScheduler {
    const val ACTION_FIRE = "com.elimbi.unscroll.action.FOCUS_ROUTINE_FIRE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onChanged(context: Context) {
        val app = context.applicationContext
        scope.launch {
            reschedule(app)
            FocusSessionKeeper.align(app)
        }
    }

    suspend fun reschedule(context: Context) {
        val app = context.applicationContext
        val routines = UnscrollDatabase.get(app).focusRoutines().all()
        val next = nextStartMillis(routines, System.currentTimeMillis(), ZoneId.systemDefault())
        val alarm = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(app)
        alarm.cancel(pending)
        if (next == null) return
        IdleAlarms.schedule(alarm, next, pending)
    }

    fun nextStartMillis(routines: List<FocusRoutine>, now: Long, zone: ZoneId): Long? {
        val startDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var best: Long? = null
        for (routine in routines) {
            if (!routine.enabled || routine.durationMinutes <= 0 || !Weekdays.any(routine.weekdaysMask)) continue
            val startMinute = routine.startMinute.coerceIn(0, 24 * 60 - 1)
            for (offset in 0..7) {
                val day = startDay.plusDays(offset.toLong())
                if (!Weekdays.contains(routine.weekdaysMask, day.dayOfWeek)) continue
                val start = day.atStartOfDay(zone).plusMinutes(startMinute.toLong()).toInstant().toEpochMilli()
                if (start <= now) continue
                val current = best
                if (current == null || start < current) best = start
            }
        }
        return best
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, FocusRoutineReceiver::class.java).setAction(ACTION_FIRE)
        return PendingIntent.getBroadcast(
            context,
            44,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class FocusRoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != QUICKBOOT &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != FocusRoutineScheduler.ACTION_FIRE
        ) {
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (action == FocusRoutineScheduler.ACTION_FIRE) {
                    FocusRoutineScheduler.reschedule(app)
                    FocusSessionKeeper.align(app)
                    ReminderScheduler.schedule(app)
                } else {
                    KeepAlive.restore(app)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
