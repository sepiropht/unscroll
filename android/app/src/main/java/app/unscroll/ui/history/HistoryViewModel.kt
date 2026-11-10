package app.unscroll.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unscroll.data.repo.UsageRepository
import app.unscroll.data.stats.categoryShares
import app.unscroll.data.stats.CategoryShare
import app.unscroll.data.stats.excluding
import app.unscroll.permissions.PermissionChecks
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HistorySpan { DAYS_7, DAYS_30 }

data class HistoryDay(val date: LocalDate, val millis: Long)

data class HistoryUiState(
    val loading: Boolean = true,
    val usageAccess: Boolean = false,
    val span: HistorySpan = HistorySpan.DAYS_7,
    val days: List<HistoryDay> = emptyList(),
    val averageMillis: Long = 0L,
    val maxDate: LocalDate? = null,
    val maxMillis: Long = 0L,
    val totalMillis: Long = 0L,
    val shares: List<CategoryShare> = emptyList(),
)

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = UsageRepository(app)
    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    private var generation = 0
    private var loadedOnce = false

    init {
        refresh()
    }

    fun onResume() {
        if (loadedOnce) refresh()
    }

    fun setSpan(span: HistorySpan) {
        _ui.update { it.copy(span = span) }
        refresh()
    }

    fun refresh() {
        val gen = ++generation
        val span = _ui.value.span
        viewModelScope.launch {
            if (!loadedOnce) _ui.update { it.copy(loading = true) }
            val built = withContext(Dispatchers.IO) { build(span) }
            if (gen != generation) return@launch
            loadedOnce = true
            _ui.value = built
        }
    }

    private suspend fun build(span: HistorySpan): HistoryUiState {
        val app = getApplication<Application>()
        val today = LocalDate.now(ZoneId.systemDefault())
        val access = PermissionChecks.hasUsageAccess(app)
        val length = if (span == HistorySpan.DAYS_7) 7L else 30L
        val start = today.minusDays(length - 1)
        val excluded = repo.statsExclusions()
        val raw = if (access) repo.loadDaysEnsured(start, today.plusDays(1)) else emptyList()
        val days = raw.map { it.excluding(excluded) }
        val bars = days.map { HistoryDay(it.date, it.totalMillis) }
        val total = bars.sumOf { it.millis }
        val max = bars.filter { it.millis > 0L }.maxWithOrNull(compareBy({ it.millis }, { it.date.toEpochDay() }))
        val shares = categoryShares(days, repo.assignments(), repo.categories())
        return HistoryUiState(
            loading = false,
            usageAccess = access,
            span = span,
            days = bars,
            averageMillis = if (bars.isEmpty()) 0L else total / bars.size,
            maxDate = max?.date,
            maxMillis = max?.millis ?: 0L,
            totalMillis = total,
            shares = shares,
        )
    }
}
