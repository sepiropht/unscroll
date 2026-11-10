package app.unscroll.ui.detail

import app.unscroll.prefs.AppPrefs as AppPrefsFq

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.unscroll.data.db.Category
import app.unscroll.data.guard.Weekdays
import app.unscroll.data.repo.UsageRepository
import app.unscroll.data.stats.LimitKeys
import app.unscroll.data.stats.PeriodKind
import app.unscroll.data.stats.Periods
import app.unscroll.permissions.PermissionChecks
import app.unscroll.ui.format.formatWeekdayShort
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BlockSpan { MINUTES_15, HOUR, UNTIL_TOMORROW, PERMANENT }

data class SessionRow(
    val startMillis: Long,
    val endMillis: Long,
)

data class AppDetailUiState(
    val loading: Boolean = true,
    val usageAccess: Boolean = false,
    val packageName: String = "",
    val label: String = "",
    val categoryKey: String = "autre",
    val categoryLabel: String = "",
    val categoryColor: Int = 0xFF5C6B68.toInt(),
    val categories: List<Category> = emptyList(),
    val todayMillis: Long = 0L,
    val weekMillis: Long = 0L,
    val averageMillis: Long = 0L,
    val opensToday: Int = 0,
    val opensWeek: Int = 0,
    val hasSessionStats: Boolean = false,
    val averageSessionMillis: Long = 0L,
    val longestSessionMillis: Long = 0L,
    val firstUseMillis: Long? = null,
    val lastUseMillis: Long? = null,
    val hourly: List<Long> = List(24) { 0L },
    val highlightHour: Int? = null,
    val daily: List<Long> = List(7) { 0L },
    val dailyLabels: List<String> = emptyList(),
    val sessions: List<SessionRow> = emptyList(),
    val excluded: Boolean = false,
    val limitMillis: Long? = null,
    val limitWeekdays: Int = Weekdays.ALL,
    val blockUntilMillis: Long? = null,
    val notificationCount: Int? = null,
)

class AppDetailViewModel(
    app: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {
    private val packageName: String = savedState.get<String>("packageName").orEmpty()
    private val repo = UsageRepository(app)
    private val _ui = MutableStateFlow(AppDetailUiState(packageName = packageName))
    val ui: StateFlow<AppDetailUiState> = _ui.asStateFlow()

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

    fun setExcluded(excluded: Boolean) {
        repo.setExcluded(packageName, excluded)
        refresh()
    }

    fun assignCategory(categoryKey: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.assignCategory(packageName, categoryKey) }
            refresh()
        }
    }

    fun saveLimit(dailyLimitMillis: Long?, weekdaysMask: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.saveDailyLimit(LimitKeys.app(packageName), dailyLimitMillis, weekdaysMask)
            }
            refresh()
        }
    }

    fun saveBlock(span: BlockSpan) {
        val until = untilMillis(span)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.saveBlock(packageName, until) }
            refresh()
        }
    }

    fun clearBlock() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.clearBlock(packageName) }
            refresh()
        }
    }

    private suspend fun build(): AppDetailUiState {
        val app = getApplication<Application>()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val access = PermissionChecks.hasUsageAccess(app)
        val weekStart = Periods.range(today, PeriodKind.WEEK, AppPrefsFq(app).weekStartDay()).first
        val chartStart = today.minusDays(6)
        val loadFrom = if (weekStart.isBefore(chartStart)) weekStart else chartStart
        val days = if (access) repo.loadDays(loadFrom, today.plusDays(1)) else emptyList()
        if (packageName.isNotBlank()) repo.ensureCategories(listOf(packageName))
        val categories = repo.categories()
        val categoryKey = if (packageName.isBlank()) "autre" else repo.categoryOf(packageName)
        val category = categories.find { it.key == categoryKey }
        val byDate = days.associateBy { it.date }

        fun slice(date: LocalDate) = byDate[date]?.packages?.find { it.packageName == packageName }

        val todayPkg = slice(today)
        var weekMillis = 0L
        var opensWeek = 0
        var cursor = weekStart
        while (!cursor.isAfter(today)) {
            val pkg = slice(cursor)
            weekMillis += pkg?.durationMillis ?: 0L
            opensWeek += pkg?.openCount ?: 0
            cursor = cursor.plusDays(1)
        }
        val chartDates = (0L..6L).map { chartStart.plusDays(it) }
        val daily = chartDates.map { slice(it)?.durationMillis ?: 0L }
        val sessions = (byDate[today]?.sessions.orEmpty())
            .filter { it.packageName == packageName }
            .map { SessionRow(it.startMillis, it.endMillis) }
            .sortedByDescending { it.startMillis }
        val windowSessions = days.flatMap { day ->
            day.sessions.filter { it.packageName == packageName }
        }
        val durations = windowSessions.map { (it.endMillis - it.startMillis).coerceAtLeast(0L) }
        val listener = false
        val notifications = if (packageName.isBlank()) {
            0
        } else {
            repo.notificationsFor(packageName, today, today.plusDays(1))
        }
        val block = if (packageName.isBlank()) null else repo.activeBlock(packageName)
        val limit = if (packageName.isBlank()) null else repo.limitRow(LimitKeys.app(packageName))
        return AppDetailUiState(
            loading = false,
            usageAccess = access,
            packageName = packageName,
            label = if (packageName.isBlank()) "" else repo.appLabel(packageName),
            categoryKey = categoryKey,
            categoryLabel = category?.label ?: categoryKey,
            categoryColor = category?.colorArgb ?: 0xFF5C6B68.toInt(),
            categories = categories,
            todayMillis = todayPkg?.durationMillis ?: 0L,
            weekMillis = weekMillis,
            averageMillis = daily.sum() / 7L,
            opensToday = todayPkg?.openCount ?: 0,
            opensWeek = opensWeek,
            hasSessionStats = durations.isNotEmpty(),
            averageSessionMillis = if (durations.isEmpty()) 0L else durations.sum() / durations.size,
            longestSessionMillis = durations.maxOrNull() ?: 0L,
            firstUseMillis = windowSessions.minOfOrNull { it.startMillis },
            lastUseMillis = windowSessions.maxOfOrNull { it.endMillis },
            hourly = todayPkg?.hourly ?: List(24) { 0L },
            highlightHour = if (access) LocalTime.now(zone).hour else null,
            daily = daily,
            dailyLabels = chartDates.map { formatWeekdayShort(it) },
            sessions = sessions,
            excluded = packageName in repo.excludedPackages(),
            limitMillis = limit?.dailyLimitMillis?.takeIf { it > 0L },
            limitWeekdays = limit?.weekdaysMask ?: Weekdays.ALL,
            blockUntilMillis = block?.untilMillis,
            notificationCount = if (listener || notifications > 0) notifications else null,
        )
    }

    private fun untilMillis(span: BlockSpan): Long {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        return when (span) {
            BlockSpan.MINUTES_15 -> now + 15L * 60L * 1000L
            BlockSpan.HOUR -> now + 60L * 60L * 1000L
            BlockSpan.UNTIL_TOMORROW -> LocalDate.now(zone).plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            BlockSpan.PERMANENT -> LimitKeys.PERMANENT_UNTIL
        }
    }
}
