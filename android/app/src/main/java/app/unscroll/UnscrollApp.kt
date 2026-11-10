package app.unscroll

import android.app.Application
import app.unscroll.data.achieve.AchievementStore
import app.unscroll.data.repo.UsageRepository
import app.unscroll.data.work.WorkScheduler
import app.unscroll.service.FocusRoutineScheduler
import app.unscroll.service.GuardNotifications
import app.unscroll.service.ReminderScheduler
import app.unscroll.widget.HomeWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UnscrollApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GuardNotifications.ensure(this)
        WorkScheduler.schedule(this)
        FocusRoutineScheduler.onChanged(this)
        ReminderScheduler.schedule(this)
        runCatching { AchievementStore.get(this).settleDue(System.currentTimeMillis()) }
        HomeWidgets.refreshAsync(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                UsageRepository(this@UnscrollApp).seedCategories()
            } catch (_: Exception) {
                // Réessayé au prochain démarrage.
            }
        }
    }
}
