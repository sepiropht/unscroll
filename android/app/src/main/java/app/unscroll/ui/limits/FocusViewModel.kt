package app.unscroll.ui.limits

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unscroll.data.guard.AppChoice
import app.unscroll.data.guard.FocusWindows
import app.unscroll.data.guard.GuardRepository
import app.unscroll.data.guard.parseCsv
import app.unscroll.prefs.AppPrefs
import app.unscroll.service.FocusRoutineScheduler
import app.unscroll.service.FocusService
import app.unscroll.service.FocusSessionKeeper
import app.unscroll.service.FocusSessions
import app.unscroll.service.FocusTileService
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FocusRoutineRow(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val startMinute: Int,
    val durationMinutes: Int,
    val weekdaysMask: Int,
    val appCount: Int,
    val activeNow: Boolean,
    val activeUntil: Long,
)

data class FocusUiState(
    val loading: Boolean = true,
    val running: Boolean = false,
    val until: Long = 0L,
    val minutes: Int = 25,
    val selected: Set<String> = emptySet(),
    val apps: List<AppChoice> = emptyList(),
    val pinOn: Boolean = false,
    val needApps: Boolean = false,
    val routines: List<FocusRoutineRow> = emptyList(),
    val activeRoutineId: Long = 0L,
    val activeRoutineName: String = "",
    val activeRoutineUntil: Long = 0L,
)

class FocusViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = GuardRepository(app)
    private val prefs = AppPrefs(app)
    private val _ui = MutableStateFlow(FocusUiState())
    val ui: StateFlow<FocusUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { load() }
            val previous = _ui.value
            val now = System.currentTimeMillis()
            val until = prefs.focusUntilMillis
            val packages = prefs.focusPackages
            val running = until > now && packages.isNotEmpty()
            val first = previous.loading
            _ui.value = loaded.copy(
                loading = false,
                running = running,
                until = if (running) until else 0L,
                minutes = when {
                    running -> previous.minutes
                    first -> prefs.focusDraftMinutes.coerceIn(1, 180)
                    else -> previous.minutes
                },
                selected = when {
                    running -> packages
                    first -> prefs.focusDraftPackages
                    else -> previous.selected
                },
                pinOn = prefs.hasPin(),
                needApps = false,
            )
        }
    }

    fun toggle(packageName: String) {
        if (_ui.value.running) return
        val next = _ui.value.selected.toMutableSet()
        if (!next.add(packageName)) next.remove(packageName)
        prefs.focusDraftPackages = next
        _ui.value = _ui.value.copy(selected = next, needApps = false)
    }

    fun setMinutes(minutes: Int) {
        if (_ui.value.running) return
        val value = minutes.coerceIn(1, 180)
        prefs.focusDraftMinutes = value
        _ui.value = _ui.value.copy(minutes = value)
    }

    fun start() {
        val draft = _ui.value
        if (draft.selected.isEmpty()) {
            _ui.value = draft.copy(needApps = true)
            return
        }
        prefs.focusDraftMinutes = draft.minutes.coerceIn(1, 180)
        prefs.focusDraftPackages = draft.selected
        val app = getApplication<Application>()
        runCatching { FocusSessions.startDraft(app) }
        refresh()
    }

    fun stop() {
        val app = getApplication<Application>()
        prefs.clearFocus()
        runCatching {
            app.startService(Intent(app, FocusService::class.java).setAction(FocusService.ACTION_STOP_MANUAL))
        }
        FocusTileService.requestListening(app)
        refresh()
    }

    fun skipActive() {
        val id = _ui.value.activeRoutineId
        if (id == 0L) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val routine = repo.routine(id) ?: return@withContext
                val now = System.currentTimeMillis()
                val until = FocusWindows.endMillis(now, ZoneId.systemDefault(), routine.startMinute, routine.durationMinutes)
                prefs.skipRoutine(id, until)
            }
            FocusSessionKeeper.refresh(getApplication())
            refresh()
        }
    }

    fun toggleRoutine(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val row = repo.routine(id) ?: return@withContext
                repo.saveRoutine(row.copy(enabled = !row.enabled))
            }
            FocusRoutineScheduler.onChanged(getApplication())
            refresh()
        }
    }

    private suspend fun load(): FocusUiState {
        val apps = repo.launchableApps()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val skips = prefs.routineSkips(now)
        val rows = repo.routines().map { routine ->
            val skipped = (skips[routine.id] ?: 0L) > now
            val active = routine.enabled && !skipped &&
                FocusWindows.isActive(now, zone, routine.startMinute, routine.durationMinutes, routine.weekdaysMask)
            FocusRoutineRow(
                id = routine.id,
                name = routine.name,
                enabled = routine.enabled,
                startMinute = routine.startMinute,
                durationMinutes = routine.durationMinutes,
                weekdaysMask = routine.weekdaysMask,
                appCount = parseCsv(routine.packagesCsv).size,
                activeNow = active,
                activeUntil = if (active) {
                    FocusWindows.endMillis(now, zone, routine.startMinute, routine.durationMinutes)
                } else {
                    0L
                },
            )
        }
        val current = rows.filter { it.activeNow }.minByOrNull { it.activeUntil }
        return FocusUiState(
            apps = apps,
            routines = rows,
            activeRoutineId = current?.id ?: 0L,
            activeRoutineName = current?.name.orEmpty(),
            activeRoutineUntil = current?.activeUntil ?: 0L,
        )
    }
}
