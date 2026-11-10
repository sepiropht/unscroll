package app.unscroll.ui.categories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.unscroll.data.db.Category
import app.unscroll.data.repo.UsageRepository
import app.unscroll.data.stats.LimitKeys
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

data class CategoryAppRow(
    val packageName: String,
    val label: String,
    val todayMillis: Long,
    val weekMillis: Long,
    val excluded: Boolean,
)

data class CategoryDetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val usageAccess: Boolean = false,
    val key: String = "",
    val label: String = "",
    val colorArgb: Int = 0xFF5C6B68.toInt(),
    val custom: Boolean = false,
    val todayMillis: Long = 0L,
    val weekMillis: Long = 0L,
    val averageMillis: Long = 0L,
    val limitMillis: Long? = null,
    val apps: List<CategoryAppRow> = emptyList(),
    val categories: List<Category> = emptyList(),
)

class CategoryDetailViewModel(
    app: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {
    private val categoryKey: String = savedState.get<String>("categoryKey").orEmpty()
    private val repo = UsageRepository(app)
    private val _ui = MutableStateFlow(CategoryDetailUiState(key = categoryKey))
    val ui: StateFlow<CategoryDetailUiState> = _ui.asStateFlow()

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

    fun assign(packageName: String, newKey: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.assignCategory(packageName, newKey) }
            refresh()
        }
    }

    fun saveLimit(dailyLimitMillis: Long?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.saveLimit(LimitKeys.category(categoryKey), dailyLimitMillis) }
            refresh()
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteCustomCategory(categoryKey) }
            onDone()
        }
    }

    private suspend fun build(): CategoryDetailUiState {
        val app = getApplication<Application>()
        val categories = repo.categories()
        val category = categories.find { it.key == categoryKey }
            ?: return CategoryDetailUiState(loading = false, missing = true, key = categoryKey)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val access = PermissionChecks.hasUsageAccess(app)
        val excluded = repo.excludedPackages()
        val omitted = repo.statsExclusions()
        val raw = if (access) repo.loadDaysEnsured(today.minusDays(6), today.plusDays(1)) else emptyList()
        val known = categories.associateBy { it.key }
        val assignments = repo.assignments()
        fun resolved(packageName: String): String {
            val rawKey = assignments[packageName] ?: "autre"
            return if (rawKey in known) rawKey else "autre"
        }
        val names = linkedSetOf<String>()
        for ((packageName, key) in assignments) {
            if (resolved(packageName) == categoryKey) names += packageName
        }
        for (day in raw) {
            for (pkg in day.packages) {
                if (resolved(pkg.packageName) == categoryKey) names += pkg.packageName
            }
        }
        val todayByPkg = raw.find { it.date == today }?.packages?.associate { it.packageName to it.durationMillis }.orEmpty()
        val weekByPkg = mutableMapOf<String, Long>()
        for (day in raw) {
            for (pkg in day.packages) {
                weekByPkg[pkg.packageName] = (weekByPkg[pkg.packageName] ?: 0L) + pkg.durationMillis
            }
        }
        names.removeAll { it in omitted && it !in excluded }
        val apps = names.map { packageName ->
            CategoryAppRow(
                packageName = packageName,
                label = repo.appLabel(packageName),
                todayMillis = todayByPkg[packageName] ?: 0L,
                weekMillis = weekByPkg[packageName] ?: 0L,
                excluded = packageName in excluded,
            )
        }.sortedWith(compareByDescending<CategoryAppRow> { it.weekMillis }.thenBy { it.label })
        val visible = raw.map { it.excluding(omitted) }
        var todayMillis = 0L
        var weekMillis = 0L
        for (day in visible) {
            val part = day.packages.filter { resolved(it.packageName) == categoryKey }.sumOf { it.durationMillis }
            weekMillis += part
            if (day.date == today) todayMillis += part
        }
        return CategoryDetailUiState(
            loading = false,
            missing = false,
            usageAccess = access,
            key = category.key,
            label = category.label,
            colorArgb = category.colorArgb,
            custom = category.custom,
            todayMillis = todayMillis,
            weekMillis = weekMillis,
            averageMillis = weekMillis / 7L,
            limitMillis = repo.limitFor(LimitKeys.category(category.key)),
            apps = apps,
            categories = categories,
        )
    }
}
