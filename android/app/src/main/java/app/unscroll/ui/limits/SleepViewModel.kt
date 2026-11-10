package app.unscroll.ui.limits

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unscroll.data.guard.AppChoice
import app.unscroll.data.guard.GuardRepository
import app.unscroll.data.guard.ScheduleWindow
import app.unscroll.data.guard.Weekdays
import app.unscroll.data.guard.parseCsv
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SleepError { SPAN, DAY }

data class SleepUi(
    val ready: Boolean = false,
    val enabled: Boolean = false,
    val startMinute: Int = 22 * 60 + 30,
    val endMinute: Int = 7 * 60,
    val weekdaysMask: Int = Weekdays.ALL,
    val packages: Set<String> = emptySet(),
    val apps: List<AppChoice> = emptyList(),
    val error: SleepError? = null,
    val dirty: Boolean = false,
    val activeNow: Boolean = false,
)

class SleepViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = GuardRepository(app)
    private val _ui = MutableStateFlow(SleepUi())
    val ui: StateFlow<SleepUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { load() }
            _ui.value = loaded
        }
    }

    fun setEnabled(value: Boolean) {
        _ui.value = _ui.value.copy(enabled = value, dirty = true, error = null)
    }

    fun setStart(minute: Int) {
        _ui.value = _ui.value.copy(startMinute = minute.coerceIn(0, 24 * 60 - 1), dirty = true, error = null)
    }

    fun setEnd(minute: Int) {
        _ui.value = _ui.value.copy(endMinute = minute.coerceIn(0, 24 * 60 - 1), dirty = true, error = null)
    }

    fun setDays(mask: Int) {
        _ui.value = _ui.value.copy(weekdaysMask = mask and Weekdays.ALL, dirty = true, error = null)
    }

    fun togglePackage(packageName: String) {
        val next = _ui.value.packages.toMutableSet()
        if (!next.add(packageName)) next.remove(packageName)
        _ui.value = _ui.value.copy(packages = next, dirty = true, error = null)
    }

    fun restoreSuggested() {
        _ui.value = _ui.value.copy(packages = repo.defaultSleepPackages(), dirty = true, error = null)
    }

    fun save() {
        val draft = _ui.value
        val error = when {
            !Weekdays.any(draft.weekdaysMask) -> SleepError.DAY
            draft.startMinute == draft.endMinute -> SleepError.SPAN
            else -> null
        }
        if (error != null) {
            _ui.value = draft.copy(error = error)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.saveSleep(
                    enabled = draft.enabled,
                    startMinute = draft.startMinute,
                    endMinute = draft.endMinute,
                    weekdaysMask = draft.weekdaysMask,
                    packages = draft.packages,
                )
            }
            _ui.value = draft.copy(dirty = false, error = null, activeNow = windowActive(draft))
        }
    }

    private suspend fun load(): SleepUi {
        val apps = repo.sleepChoices()
        val row = repo.findSleep()
        val base = if (row == null) {
            SleepUi(
                ready = true,
                packages = repo.defaultSleepPackages(),
                apps = apps,
            )
        } else {
            SleepUi(
                ready = true,
                enabled = row.enabled,
                startMinute = row.startMinute,
                endMinute = row.endMinute,
                weekdaysMask = row.weekdaysMask,
                packages = parseCsv(row.packagesCsv),
                apps = apps,
            )
        }
        return base.copy(activeNow = windowActive(base))
    }

    private fun windowActive(state: SleepUi): Boolean {
        if (!state.enabled || state.startMinute == state.endMinute) return false
        return ScheduleWindow.isActive(
            System.currentTimeMillis(),
            ZoneId.systemDefault(),
            state.startMinute,
            state.endMinute,
            state.weekdaysMask,
        )
    }
}
