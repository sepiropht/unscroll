package app.unscroll.ui.limits

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.unscroll.data.db.BlockSchedule
import app.unscroll.data.db.ScheduleKinds
import app.unscroll.data.guard.AppChoice
import app.unscroll.data.guard.GuardRepository
import app.unscroll.data.guard.Weekdays
import app.unscroll.data.guard.parseCsv
import app.unscroll.data.guard.toCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScheduleRowUi(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val startMinute: Int,
    val endMinute: Int,
    val weekdaysMask: Int,
    val appCount: Int,
    val siteCount: Int,
)

data class SchedulesUiState(
    val loading: Boolean = true,
    val rows: List<ScheduleRowUi> = emptyList(),
)

class SchedulesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = GuardRepository(app)
    private val _ui = MutableStateFlow(SchedulesUiState())
    val ui: StateFlow<SchedulesUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                repo.schedules()
                    .filter { it.kind == ScheduleKinds.WINDOW }
                    .map { row ->
                        ScheduleRowUi(
                            id = row.id,
                            name = row.name,
                            enabled = row.enabled,
                            startMinute = row.startMinute,
                            endMinute = row.endMinute,
                            weekdaysMask = row.weekdaysMask,
                            appCount = parseCsv(row.packagesCsv).size,
                            siteCount = parseCsv(row.domainsCsv).size,
                        )
                    }
            }
            _ui.value = SchedulesUiState(loading = false, rows = rows)
        }
    }

    fun toggle(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val row = repo.schedule(id) ?: return@withContext
                if (row.kind != ScheduleKinds.WINDOW) return@withContext
                repo.saveSchedule(row.copy(enabled = !row.enabled))
            }
            refresh()
        }
    }
}

enum class ScheduleError { NAME, APPS, SPAN, DAY }

data class ScheduleEditorUi(
    val ready: Boolean = false,
    val missing: Boolean = false,
    val name: String = "",
    val enabled: Boolean = true,
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 12 * 60,
    val weekdaysMask: Int = 0b0011111,
    val packages: Set<String> = emptySet(),
    val domains: Set<String> = emptySet(),
    val apps: List<AppChoice> = emptyList(),
    val error: ScheduleError? = null,
    val persisted: Boolean = false,
)

class ScheduleEditorViewModel(
    app: Application,
    saved: SavedStateHandle,
) : AndroidViewModel(app) {
    private val id: Long = saved.get<Long>("scheduleId") ?: 0L
    private val repo = GuardRepository(app)
    private val _ui = MutableStateFlow(ScheduleEditorUi())
    val ui: StateFlow<ScheduleEditorUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { load() }
            _ui.value = loaded
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

    fun setEnd(minute: Int) {
        _ui.value = _ui.value.copy(endMinute = minute.coerceIn(0, 24 * 60 - 1), error = null)
    }

    fun setDays(mask: Int) {
        _ui.value = _ui.value.copy(weekdaysMask = mask and Weekdays.ALL, error = null)
    }

    fun togglePackage(packageName: String) {
        val next = _ui.value.packages.toMutableSet()
        if (!next.add(packageName)) next.remove(packageName)
        _ui.value = _ui.value.copy(packages = next, error = null)
    }

    fun setDomains(values: Set<String>) {
        _ui.value = _ui.value.copy(domains = values, error = null)
    }

    fun save(onDone: () -> Unit) {
        val draft = _ui.value
        val error = when {
            draft.name.isBlank() -> ScheduleError.NAME
            !Weekdays.any(draft.weekdaysMask) -> ScheduleError.DAY
            draft.startMinute == draft.endMinute -> ScheduleError.SPAN
            draft.packages.isEmpty() && draft.domains.isEmpty() -> ScheduleError.APPS
            else -> null
        }
        if (error != null) {
            _ui.value = draft.copy(error = error)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.saveSchedule(
                    BlockSchedule(
                        id = id,
                        name = draft.name.trim(),
                        enabled = draft.enabled,
                        startMinute = draft.startMinute,
                        endMinute = draft.endMinute,
                        weekdaysMask = draft.weekdaysMask and Weekdays.ALL,
                        packagesCsv = toCsv(draft.packages),
                        domainsCsv = toCsv(draft.domains),
                        kind = ScheduleKinds.WINDOW,
                    ),
                )
            }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        if (id == 0L) {
            onDone()
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteWindow(id) }
            onDone()
        }
    }

    private suspend fun load(): ScheduleEditorUi {
        val apps = repo.launchableApps()
        if (id == 0L) return ScheduleEditorUi(ready = true, apps = apps)
        val row = repo.schedule(id)
        if (row == null || row.kind != ScheduleKinds.WINDOW) {
            return ScheduleEditorUi(ready = true, missing = true, apps = apps)
        }
        return ScheduleEditorUi(
            ready = true,
            name = row.name,
            enabled = row.enabled,
            startMinute = row.startMinute,
            endMinute = row.endMinute,
            weekdaysMask = row.weekdaysMask,
            packages = parseCsv(row.packagesCsv),
            domains = parseCsv(row.domainsCsv),
            apps = apps,
            persisted = true,
        )
    }
}
