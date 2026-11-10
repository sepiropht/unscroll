package app.unscroll.ui.limits

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unscroll.data.guard.AppChoice
import app.unscroll.data.guard.GuardRepository
import app.unscroll.data.guard.Weekdays
import app.unscroll.data.stats.LimitKeys
import app.unscroll.permissions.PermissionChecks
import app.unscroll.service.GuardService
import app.unscroll.service.GuardWatchdog
import app.unscroll.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LimitRowUi(
    val packageName: String,
    val label: String,
    val dailyMillis: Long,
    val weekdaysMask: Int,
)

data class LimitsUiState(
    val loading: Boolean = true,
    val rows: List<LimitRowUi> = emptyList(),
    val apps: List<AppChoice> = emptyList(),
    val pauseUntil: Long = 0L,
    val extraEnabled: Boolean = true,
    val pinOn: Boolean = false,
    val overlay: Boolean = false,
    val notifications: Boolean = true,
)

class LimitsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = GuardRepository(app)
    private val prefs = AppPrefs(app)
    private val _ui = MutableStateFlow(LimitsUiState())
    val ui: StateFlow<LimitsUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val built = withContext(Dispatchers.IO) { loadRows() }
            _ui.value = built
        }
    }

    fun saveDaily(packageName: String, millis: Long?, mask: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.saveDailyLimit(LimitKeys.app(packageName), millis, mask)
            }
            refresh()
        }
        GuardService.ensure(getApplication())
        GuardWatchdog.schedule(getApplication())

    }

    fun setExtra(enabled: Boolean) {
        prefs.extraMinutesEnabled = enabled
        _ui.value = _ui.value.copy(extraEnabled = enabled)
    }

    fun pauseFive() {
        prefs.pauseUntilMillis = System.currentTimeMillis() + LimitKeys.EXTRA_MILLIS
        refresh()
    }

    fun resume() {
        prefs.pauseUntilMillis = 0L
        refresh()
    }

    fun setPin(pin: String) {
        if (prefs.setPin(pin)) refresh()
    }

    fun clearPin() {
        prefs.clearPin()
        refresh()
    }

    private suspend fun loadRows(): LimitsUiState {
        val app = getApplication<Application>()
        val rows = repo.limits()
            .filter { it.targetKey.startsWith("app:") && it.dailyLimitMillis > 0L }
            .map { row ->
                val pkg = LimitKeys.payload(row.targetKey)
                LimitRowUi(
                    packageName = pkg,
                    label = repo.labelOf(pkg),
                    dailyMillis = row.dailyLimitMillis,
                    weekdaysMask = row.weekdaysMask and Weekdays.ALL,
                )
            }
            .sortedBy { it.label.lowercase() }
        return LimitsUiState(
            loading = false,
            rows = rows,
            apps = repo.launchableApps(),
            pauseUntil = prefs.pauseUntilMillis,
            extraEnabled = prefs.extraMinutesEnabled,
            pinOn = prefs.hasPin(),
            overlay = PermissionChecks.canOverlay(app),
            notifications = PermissionChecks.hasNotifications(app),
        )
    }
}
