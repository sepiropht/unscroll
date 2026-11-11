package app.unscroll.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Boucle légère de surveillance des limites. Ce n'est pas un service au premier plan :
 * aucun type Play (specialUse, dataSync, …) n'est déclaré. Android peut l'arrêter en
 * arrière-plan ; [GuardWatchdog] le relance alors par une alarme inexacte et refait
 * un contrôle. Quand l'écran de pause (superposition) est visible, le processus reste
 * en général en vie et le rythme serré reprend.
 */
class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive) {
                    if (!GuardEngine.shouldRun(this@GuardService)) {
                        stopSelf()
                        break
                    }
                    runCatching { GuardRuntime.tickOnce(this@GuardService) }
                    FocusNotifications.refresh(this@GuardService)
                    delay(TICK_MS)
                }
            }
        } else {
            scope.launch {
                runCatching { GuardRuntime.tickOnce(this@GuardService) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 2_000L

        fun ensure(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, GuardService::class.java)
            if (!GuardEngine.shouldRun(app)) {
                runCatching { app.stopService(intent) }
                return
            }
            try {
                app.startService(intent)
            } catch (_: IllegalStateException) {
                // Départ depuis l'arrière-plan refusé : le watchdog fera le contrôle.
            } catch (_: SecurityException) {
            }
        }
    }
}

/** Relance un contrôle même si [GuardService] a été arrêté par le système. */
object GuardWatchdog {
    private const val INTERVAL_MS = 30_000L
    private const val REQUEST = 71

    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(app)
        if (!GuardEngine.shouldRun(app) && !FocusNotifications.isActive(app)) {
            alarm.cancel(pending)
            return
        }
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        runCatching {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        }.onFailure {
            runCatching {
                alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }
        }
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, GuardWatchdogReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    const val ACTION = "com.elimbi.unscroll.action.GUARD_WATCHDOG"
}

class GuardWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != GuardWatchdog.ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                GuardWatchdog.schedule(app)
                runCatching { GuardRuntime.tickOnce(app) }
                FocusNotifications.refresh(app)
                GuardService.ensure(app)
            } finally {
                pending.finish()
            }
        }
    }
}
