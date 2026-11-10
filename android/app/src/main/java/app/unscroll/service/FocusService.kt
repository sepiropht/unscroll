package app.unscroll.service

import app.unscroll.MainActivity as MainActivityFq

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Notification persistante pendant une concentration. Le blocage est fait par l’accessibilité. */
class FocusService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private var ticker: Job? = null
    private var currentShown: Shown? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        live = this
    }

    /** Arrête la notification sans réécrire les préférences ni les succès. */
    fun haltForReset() {
        ticker?.cancel()
        running = false
        scope.cancel()
        stopForegroundAndSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (PinGate.isEnabled(this)) {
                    openScreen()
                    return START_STICKY
                }
                scope.launch { endShownAndPublish() }
                return START_STICKY
            }
            ACTION_STOP_MANUAL -> AppPrefs(this).clearFocus()
            ACTION_SKIP_ROUTINE -> {
                val id = intent.getLongExtra(EXTRA_ROUTINE_ID, 0L)
                scope.launch {
                    skipById(id)
                    publish()
                    armTicker()
                }
                return START_STICKY
            }
        }
        if (intent?.action != ACTION_ALIGN && intent?.action != ACTION_STOP_MANUAL) placeholder()
        scope.launch {
            publish()
            armTicker()
        }
        return START_STICKY
    }

    /**
     * Android 15 appelle cette méthode si un type de service au premier plan dépasse son quota.
     * Le type « usage particulier » n’est pas dans ce quota ; on s’arrête quand même si le système le demande,
     * sinon le processus est tué.
     */
    override fun onTimeout(startId: Int) {
        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        if (live == this) live = null
        running = false
        ticker?.cancel()
        if (Build.VERSION.SDK_INT >= 24) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun placeholder() {
        val until = AppPrefs(this).focusUntilMillis
            .takeIf { it > System.currentTimeMillis() }
            ?: (System.currentTimeMillis() + 60_000L)
        enterForeground(until, null)
    }

    private suspend fun endShownAndPublish() {
        val shown = currentShown ?: withContext(Dispatchers.IO) { resolve() }
        when (shown) {
            is Shown.Manual -> AppPrefs(this).clearFocus()
            is Shown.Routine -> AppPrefs(this).skipRoutine(shown.id, shown.until)
            null -> AppPrefs(this).clearFocus()
        }
        publish()
    }

    private suspend fun skipById(id: Long) {
        if (id == 0L) return
        val routine = withContext(Dispatchers.IO) {
            UnscrollDatabase.get(this@FocusService).focusRoutines().find(id)
        } ?: return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val until = if (FocusWindows.isActive(now, zone, routine.startMinute, routine.durationMinutes, routine.weekdaysMask)) {
            FocusWindows.endMillis(now, zone, routine.startMinute, routine.durationMinutes)
        } else {
            now
        }
        AppPrefs(this).skipRoutine(id, until)
    }

    private suspend fun publish() {
        gate.withLock {
            val shown = withContext(Dispatchers.IO) { resolve() }
            currentShown = shown
            noteFocus(shown)
            if (shown == null) {
                stopForegroundAndSelf()
                return
            }
            enterForeground(shown.until, shown.routineName)
            FocusTileService.requestListening(this@FocusService)
        }
    }

    private fun noteFocus(shown: Shown?) {
        val store = AchievementStore.get(this)
        when (shown) {
            is Shown.Manual -> {
                val started = AppPrefs(this).focusStartedMillis
                if (started > 0L) store.armManual(started, shown.until)
            }
            is Shown.Routine -> store.armRoutine(shown.id, shown.until)
            null -> store.settleDue(System.currentTimeMillis())
        }
    }

    private suspend fun resolve(): Shown? {
        val now = System.currentTimeMillis()
        val prefs = AppPrefs(this)
        if (FocusSessions.manualRunning(prefs, now)) return Shown.Manual(prefs.focusUntilMillis)
        val active = FocusSessionKeeper.activeRoutine(this, now) ?: return null
        return Shown.Routine(active.routine.id, active.until, active.routine.name)
    }

    private fun armTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                delay(30_000L)
                publish()
                if (currentShown == null) break
            }
        }
    }

    private fun enterForeground(end: Long, routineName: String?) {
        GuardNotifications.ensure(this)
        val left = (end - System.currentTimeMillis()).coerceAtLeast(0L)
        val leftText = formatLeft(this, left)
        val text = if (routineName.isNullOrBlank()) {
            getString(R.string.focus_notif_remaining, leftText)
        } else {
            getString(R.string.focus_notif_routine, routineName, leftText)
        }
        val action = if (routineName.isNullOrBlank()) {
            getString(R.string.focus_stop)
        } else {
            getString(R.string.focus_skip_short)
        }
        val notification = NotificationCompat.Builder(this, GuardNotifications.CHANNEL_FOCUS)
            .setSmallIcon(R.drawable.ic_stat_hourglass)
            .setContentTitle(getString(R.string.focus_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(end)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(GuardNotifications.openApp(this, AppRoutes.FOCUS))
            .addAction(0, action, stopIntent())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                GuardNotifications.FOCUS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(GuardNotifications.FOCUS_NOTIFICATION_ID, notification)
        }
    }

    private fun stopIntent() = PendingIntent.getService(
        this,
        7,
        Intent(this, FocusService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openScreen() {
        startActivity(
            Intent(this, MainActivityFq::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(GuardNotifications.EXTRA_ROUTE, AppRoutes.FOCUS)
            },
        )
    }

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= 24) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { stopForeground(true) }
        }
        FocusTileService.requestListening(this)
        stopSelf()
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

    companion object {
        const val ACTION_STOP = "app.unscroll.FOCUS_STOP"
        const val ACTION_STOP_MANUAL = "app.unscroll.FOCUS_STOP_MANUAL"
        const val ACTION_SKIP_ROUTINE = "app.unscroll.FOCUS_SKIP_ROUTINE"
        const val ACTION_ALIGN = "app.unscroll.FOCUS_ALIGN"
        const val EXTRA_ROUTINE_ID = "routine_id"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var live: FocusService? = null

        fun haltIfRunning() {
            live?.haltForReset()
        }

        /**
         * Démarre le service au premier plan. Android 12 refuse ce départ depuis l’arrière-plan
         * sans exemption (redémarrage, alarme exacte, superposition, action de la personne).
         * L’échec ne plante pas : le blocage continue via l’accessibilité, et un prochain réveil réessaie.
         * Android 14 exige le type déclaré. Android 15 autorise ce type « usage particulier » au démarrage.
         */
        fun start(context: Context) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, FocusService::class.java))
            } catch (_: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException, ou processus en arrière-plan.
            } catch (_: SecurityException) {
                // Type refusé pour ce contexte.
            }
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
    }
}
