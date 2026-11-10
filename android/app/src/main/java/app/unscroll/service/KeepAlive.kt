package app.unscroll.service

import android.content.Context
import app.unscroll.data.achieve.AchievementStore
import app.unscroll.data.work.WorkScheduler
import app.unscroll.widget.HomeWidgets

/** Repose alarmes, service et widgets après un redémarrage, une mise à jour, ou une veille longue. */
internal object KeepAlive {
    suspend fun restore(context: Context) {
        val app = context.applicationContext
        FocusRoutineScheduler.reschedule(app)
        FocusSessionKeeper.align(app)
        ReminderScheduler.schedule(app)
        WorkScheduler.schedule(app)
        runCatching { AchievementStore.get(app).settleDue(System.currentTimeMillis()) }
        runCatching { HomeWidgets.refresh(app) }
    }
}
