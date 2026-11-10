package app.unscroll.ui.categories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unscroll.data.repo.UsageRepository
import app.unscroll.data.stats.CategoryShare
import app.unscroll.data.stats.LimitKeys
import app.unscroll.data.stats.categoryShares
import app.unscroll.data.stats.excluding
import app.unscroll.permissions.PermissionChecks
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CategoryRow(
    val key: String,
    val label: String,
    val colorArgb: Int,
    val custom: Boolean,
    val todayMillis: Long,
    val weekMillis: Long,
    val limitMillis: Long?,
)

data class ExcludedApp(val packageName: String, val label: String)

data class CategoriesUiState(
    val loading: Boolean = true,
    val usageAccess: Boolean = false,
    val shares: List<CategoryShare> = emptyList(),
    val rows: List<CategoryRow> = emptyList(),
    val excluded: List<ExcludedApp> = emptyList(),
)

class CategoriesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = UsageRepository(app)
    private val _ui = MutableStateFlow(CategoriesUiState())
    val ui: StateFlow<CategoriesUiState> = _ui.asStateFlow()

    private var generation = 0
    private var loadedOnce = false

    init {
        refresh()
    }

    fun onResume() {
        if (loadedOnce) refresh()
    }

    fun refresh() {
        val gen = ++generation
        viewModelScope.launch {
            if (!loadedOnce) _ui.value = _ui.value.copy(loading = true)
            val built = withContext(Dispatchers.IO) { build() }
            if (gen != generation) return@launch
            loadedOnce = true
            _ui.value = built
        }
    }

    fun create(label: String, colorArgb: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.addCustomCategory(label, colorArgb) }
            refresh()
        }
    }

    private suspend fun build(): CategoriesUiState {
        val app = getApplication<Application>()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val access = PermissionChecks.hasUsageAccess(app)
        val excluded = repo.excludedPackages()
        val omitted = repo.statsExclusions()
        val raw = if (access) repo.loadDaysEnsured(today.minusDays(6), today.plusDays(1)) else emptyList()
        val visible = raw.map { it.excluding(omitted) }
        val categories = repo.categories()
        val known = categories.associateBy { it.key }
        val assignments = repo.assignments()
        val limits = repo.allLimits().associate { it.targetKey to it.dailyLimitMillis }
        val todayTotals = mutableMapOf<String, Long>()
        val weekTotals = mutableMapOf<String, Long>()
        for (day in visible) {
            val target = if (day.date == today) todayTotals else null
            for (pkg in day.packages) {
                val rawKey = assignments[pkg.packageName] ?: "autre"
                val key = if (rawKey in known) rawKey else "autre"
                weekTotals[key] = (weekTotals[key] ?: 0L) + pkg.durationMillis
                if (target != null) target[key] = (target[key] ?: 0L) + pkg.durationMillis
            }
        }
        val rows = categories.map { category ->
            CategoryRow(
                key = category.key,
                label = category.label,
                colorArgb = category.colorArgb,
                custom = category.custom,
                todayMillis = todayTotals[category.key] ?: 0L,
                weekMillis = weekTotals[category.key] ?: 0L,
                limitMillis = limits[LimitKeys.category(category.key)]?.takeIf { it > 0L },
            )
        }
        val excludedApps = excluded.map { packageName ->
            ExcludedApp(packageName, repo.appLabel(packageName))
        }.sortedBy { it.label.lowercase() }
        return CategoriesUiState(
            loading = false,
            usageAccess = access,
            shares = categoryShares(visible.filter { it.date == today }, assignments, categories),
            rows = rows,
            excluded = excludedApps,
        )
    }
}
