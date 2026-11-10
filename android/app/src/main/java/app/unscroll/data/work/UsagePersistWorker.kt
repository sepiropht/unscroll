package app.unscroll.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.unscroll.data.repo.UsageRepository
import app.unscroll.widget.HomeWidgets
import kotlinx.coroutines.CancellationException

class UsagePersistWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            UsageRepository(applicationContext).persistRecent()
            HomeWidgets.refresh(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
