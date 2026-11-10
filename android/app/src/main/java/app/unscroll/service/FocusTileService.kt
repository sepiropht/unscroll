package app.unscroll.service

import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.unscroll.MainActivity
import app.unscroll.R
import app.unscroll.prefs.AppPrefs
import app.unscroll.prefs.PinGate
import app.unscroll.ui.AppRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tuile des réglages rapides : relance le dernier créneau, ou l’arrête. */
class FocusTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listening = false

    @Volatile private var cachedRoutineId = 0L
    @Volatile private var cachedRoutineUntil = 0L

    override fun onStartListening() {
        listening = true
        refresh()
    }

    override fun onStopListening() {
        listening = false
    }

    override fun onDestroy() {
        listening = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        val act = { toggle() }
        if (isLocked) unlockAndRun(act) else act()
    }

    private fun refresh() {
        scope.launch {
            val now = System.currentTimeMillis()
            val routine = FocusSessionKeeper.activeRoutine(this@FocusTileService, now)
            cachedRoutineId = routine?.routine?.id ?: 0L
            cachedRoutineUntil = routine?.until ?: 0L
            val active = FocusSessions.manualRunning(AppPrefs(this@FocusTileService), now) || routine != null
            if (!listening) return@launch
            withContext(Dispatchers.Main) { paint(active) }
        }
    }

    private fun paint(active: Boolean) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_hourglass)
        tile.label = getString(R.string.tile_focus)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (active) R.string.tile_focus_on else R.string.tile_focus_off)
        }
        tile.updateTile()
    }

    private fun toggle() {
        val prefs = AppPrefs(this)
        val now = System.currentTimeMillis()
        val manual = FocusSessions.manualRunning(prefs, now)
        val routineActive = cachedRoutineId != 0L && cachedRoutineUntil > now
        if (manual || routineActive) {
            if (PinGate.isEnabled(this)) {
                openFocus(startDraft = false)
                return
            }
            if (manual) {
                prefs.clearFocus()
            } else {
                prefs.skipRoutine(cachedRoutineId, cachedRoutineUntil)
            }
            FocusSessionKeeper.refresh(this)
            refresh()
            return
        }
        val started = runCatching { FocusSessions.startDraft(this) }.getOrDefault(false)
        if (!started) openFocus(startDraft = true) else refresh()
    }

    private fun openFocus(startDraft: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(GuardNotifications.EXTRA_ROUTE, AppRoutes.FOCUS)
            if (startDraft) putExtra(GuardNotifications.EXTRA_START_FOCUS, true)
        }
        val pending = PendingIntent.getActivity(
            this,
            if (startDraft) 91 else 90,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestListening(context: Context) {
            val app = context.applicationContext
            runCatching {
                requestListeningState(app, ComponentName(app, FocusTileService::class.java))
            }
        }

        fun requestAdd(context: Context, onResult: (AddResult) -> Unit) {
            if (Build.VERSION.SDK_INT < 33) {
                onResult(AddResult.MANUAL)
                return
            }
            val activity = context.findActivity()
            if (activity == null) {
                onResult(AddResult.MANUAL)
                return
            }
            val manager = activity.getSystemService(StatusBarManager::class.java)
            if (manager == null) {
                onResult(AddResult.MANUAL)
                return
            }
            manager.requestAddTileService(
                ComponentName(activity, FocusTileService::class.java),
                activity.getString(R.string.tile_focus),
                Icon.createWithResource(activity, R.drawable.ic_stat_hourglass),
                activity.mainExecutor,
            ) { code ->
                val added = code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                onResult(if (added) AddResult.ADDED else AddResult.DECLINED)
            }
        }
    }

    enum class AddResult { ADDED, DECLINED, MANUAL }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
