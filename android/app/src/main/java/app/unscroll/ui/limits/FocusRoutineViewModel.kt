package app.unscroll.ui.limits

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.unscroll.data.db.FocusRoutine
import app.unscroll.data.guard.AppChoice
import app.unscroll.data.guard.FocusDurations
import app.unscroll.data.guard.GuardRepository
import app.unscroll.data.guard.Weekdays
import app.unscroll.data.guard.parseCsv
import app.unscroll.data.guard.toCsv
import app.unscroll.service.FocusRoutineScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RoutineError { NAME, APPS, DAY, DURATION }

data class RoutineEditorUi(
    val ready: Boolean = false,
    val missing: Boolean = false,
    val name: String = "",
    val enabled: Boolean = true,
    val startMinute: Int = 9 * 60,
    val durationMinutes: Int = 50,
    val weekdaysMask: Int = 0b0011111,
    val packages: Set<String> = emptySet(),
    val apps: List<AppChoice> = emptyList(),
    val error: RoutineError? = null,
    val persisted: Boolean = false,
)

class FocusRoutineViewModel(
    app: Application,
    saved: SavedStateHandle,
) : AndroidViewModel(app) {
    private val id: Long = saved.get<Long>("routineId") ?: 0L
    private val repo = GuardRepository(app)
    private val _ui = MutableStateFlow(RoutineEditorUi())
    val ui: StateFlow<RoutineEditorUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            _ui.value = withContext(Dispatchers.IO) { load() }
        }
    }

    fun setName(value: String) {
        _ui.value = _ui.value.copy(name = value.take(40), error = null)
    }

    fun setEnabled(value: Boolean) {
        _ui.value = _ui.value.copy(enabled = value)
    }

    fun setStart(minute: Int) {
        _ui.value = _ui.value.copy(startMinute = minute.coerceIn(0, 24 * 60 - 1), error = null)
    }

    fun setDuration(minutes: Int) {
        _ui.value = _ui.value.copy(durationMinutes = minutes.coerceIn(1, FocusDurations.MAX), error = null)
    }

    fun setDays(mask: Int) {
        _ui.value = _ui.value.copy(weekdaysMask = mask and Weekdays.ALL, error = null)
    }

    fun togglePackage(packageName: String) {
        val next = _ui.value.packages.toMutableSet()
        if (!next.add(packageName)) next.remove(packageName)
        _ui.value = _ui.value.copy(packages = next, error = null)
    }

    fun save(onDone: () -> Unit) {
        val draft = _ui.value
        val error = when {
            draft.name.isBlank() -> RoutineError.NAME
            !Weekdays.any(draft.weekdaysMask) -> RoutineError.DAY
            draft.durationMinutes !in 1..FocusDurations.MAX -> RoutineError.DURATION
            draft.packages.isEmpty() -> RoutineError.APPS
            else -> null
        }
        if (error != null) {
            _ui.value = draft.copy(error = error)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.saveRoutine(
                    FocusRoutine(
                        id = id,
                        name = draft.name.trim(),
                        enabled = draft.enabled,
                        startMinute = draft.startMinute,
                        durationMinutes = draft.durationMinutes,
                        weekdaysMask = draft.weekdaysMask and Weekdays.ALL,
                        packagesCsv = toCsv(draft.packages),
                    ),
                )
            }
            FocusRoutineScheduler.onChanged(getApplication())
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        if (id == 0L) {
            onDone()
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteRoutine(id) }
            FocusRoutineScheduler.onChanged(getApplication())
            onDone()
        }
    }

    private suspend fun load(): RoutineEditorUi {
        val apps = repo.launchableApps()
        if (id == 0L) return RoutineEditorUi(ready = true, apps = apps)
        val row = repo.routine(id) ?: return RoutineEditorUi(ready = true, missing = true, apps = apps)
        return RoutineEditorUi(
            ready = true,
            name = row.name,
            enabled = row.enabled,
            startMinute = row.startMinute,
            durationMinutes = row.durationMinutes.coerceIn(1, FocusDurations.MAX),
            weekdaysMask = row.weekdaysMask,
            packages = parseCsv(row.packagesCsv),
            apps = apps,
            persisted = true,
        )
    }
}
