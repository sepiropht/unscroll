package app.unscroll.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.unscroll.service.KeepAlive
import kotlinx.coroutines.CancellationException

/**
 * Relit le jour et rafraîchit les widgets, même si l’application n’est pas ouverte.
 * Au passage, repose les alarmes : en Doze, le travail périodique part dans une fenêtre de maintenance
 * lorsque l’alarme inexacte a été repoussée.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            KeepAlive.restore(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
