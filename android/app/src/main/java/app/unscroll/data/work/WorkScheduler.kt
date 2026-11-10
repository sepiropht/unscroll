package app.unscroll.data.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.unscroll.widget.WidgetRefreshWorker
import java.util.concurrent.TimeUnit

object WorkScheduler {
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<UsagePersistWorker>(3, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
        val soon = OneTimeWorkRequestBuilder<UsagePersistWorker>()
            .setInitialDelay(20, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, soon)
        val widgets = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(
            WIDGETS,
            ExistingPeriodicWorkPolicy.KEEP,
            widgets,
        )
    }

    private const val PERIODIC = "usage-snapshot-periodic"
    private const val ONCE = "usage-snapshot-once"
    private const val WIDGETS = "home-widgets"
}
