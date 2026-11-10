package app.unscroll.ui.report

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unscroll.R
import app.unscroll.data.repo.UsageRepository
import app.unscroll.data.stats.AdviceId
import app.unscroll.data.stats.PeriodKind
import app.unscroll.data.stats.Periods
import app.unscroll.data.stats.excluding
import app.unscroll.data.stats.selectWeeklyAdvice
import app.unscroll.permissions.PermissionChecks
import app.unscroll.prefs.AppPrefs
import app.unscroll.ui.format.formatDayLong
import app.unscroll.ui.format.formatDayMonth
import app.unscroll.ui.format.formatDuration
import app.unscroll.ui.format.percentDelta
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReportApp(
    val packageName: String,
    val label: String,
    val durationMillis: Long,
    val fraction: Float,
)

data class ReportAdvice(val title: String, val body: String)

data class WeeklyReportUiState(
    val loading: Boolean = true,
    val usageAccess: Boolean = false,
    val rangeLabel: String = "",
    val totalMillis: Long = 0L,
    val versusLabel: String? = null,
    val busiestLabel: String? = null,
    val peakLabel: String? = null,
    val unlocksLabel: String = "",
    val apps: List<ReportApp> = emptyList(),
    val advice: List<ReportAdvice> = emptyList(),
)

class WeeklyReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = UsageRepository(app)
    private val _ui = MutableStateFlow(WeeklyReportUiState())
    val ui: StateFlow<WeeklyReportUiState> = _ui.asStateFlow()

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

    private suspend fun build(): WeeklyReportUiState {
        val app = getApplication<Application>()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val access = PermissionChecks.hasUsageAccess(app)
        val (weekStart, weekEnd) = Periods.range(today, PeriodKind.WEEK, AppPrefs(app).weekStartDay())
        val elapsed = (ChronoUnit.DAYS.between(weekStart, today) + 1).toInt().coerceAtLeast(1)
        val prevStart = weekStart.minusWeeks(1)
        val excluded = repo.statsExclusions()
        val raw = if (access) repo.loadDaysEnsured(prevStart, weekEnd) else emptyList()
        val days = raw.map { it.excluding(excluded) }
        val byDate = days.associateBy { it.date }
        val current = (0 until elapsed).map { weekStart.plusDays(it.toLong()) }
        val previous = (0 until elapsed).map { prevStart.plusDays(it.toLong()) }
        val currentDays = current.mapNotNull { byDate[it] }
        val previousDays = previous.mapNotNull { byDate[it] }
        val total = currentDays.sumOf { it.totalMillis }
        val prevTotal = previousDays.sumOf { it.totalMillis }
        val hourly = LongArray(24)
        for (day in currentDays) {
            day.hourly.forEachIndexed { index, value -> hourly[index] += value }
        }
        val peakHour = hourly.withIndex().filter { it.value > 0L }.maxByOrNull { it.value }?.index
        val busiest = currentDays.filter { it.totalMillis > 0L }.maxByOrNull { it.totalMillis }
        val unlocks = currentDays.sumOf { it.unlockCount }
        val averageUnlocks = unlocks.toDouble() / elapsed.toDouble()
        val merged = linkedMapOf<String, Long>()
        for (day in currentDays) {
            for (pkg in day.packages) {
                merged[pkg.packageName] = (merged[pkg.packageName] ?: 0L) + pkg.durationMillis
            }
        }
        val top = merged.entries
            .filter { it.value > 0L }
            .sortedByDescending { it.value }
            .take(5)
            .map { (packageName, duration) ->
                ReportApp(
                    packageName = packageName,
                    label = repo.appLabel(packageName),
                    durationMillis = duration,
                    fraction = if (total > 0L) duration.toFloat() / total.toFloat() else 0f,
                )
            }
        val topFraction = top.firstOrNull()?.fraction ?: 0f
        val averageDaily = total / elapsed.toLong()
        val adviceIds = selectWeeklyAdvice(
            thisWeekMillis = total,
            previousSameSpanMillis = prevTotal,
            topAppFraction = topFraction,
            peakHour = peakHour,
            averageDailyMillis = averageDaily,
            averageUnlocks = averageUnlocks,
            busiestDay = busiest?.date?.dayOfWeek,
        )
        val topName = top.firstOrNull()?.label.orEmpty()
        val delta = percentDelta(total, prevTotal)
        val versus = when {
            delta == null -> app.getString(R.string.no_baseline)
            abs(delta) <= 1 -> app.getString(R.string.delta_same_period)
            delta < 0 -> app.getString(R.string.delta_less_period, abs(delta))
            else -> app.getString(R.string.delta_more_period, abs(delta))
        }
        val unlockText = if (averageUnlocks == averageUnlocks.toInt().toDouble()) {
            averageUnlocks.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", averageUnlocks)
        }
        return WeeklyReportUiState(
            loading = false,
            usageAccess = access,
            rangeLabel = app.getString(
                R.string.report_range,
                formatDayMonth(weekStart),
                formatDayMonth(today),
            ),
            totalMillis = total,
            versusLabel = versus,
            busiestLabel = busiest?.let { formatDayLong(it.date) + " · " + formatDuration(it.totalMillis, app) },
            peakLabel = peakHour?.let { hour ->
                app.getString(R.string.report_peak_value, hour, (hour + 1) % 24)
            },
            unlocksLabel = app.getString(R.string.report_unlocks_value, unlockText),
            apps = top,
            advice = adviceIds.map { id -> adviceText(app, id, topName) },
        )
    }

    private fun adviceText(app: Application, id: AdviceId, topName: String): ReportAdvice {
        val title = when (id) {
            AdviceId.EMPTY -> R.string.advice_empty_title
            AdviceId.CONCENTRATED -> R.string.advice_concentrated_title
            AdviceId.RISING -> R.string.advice_rising_title
            AdviceId.FALLING -> R.string.advice_falling_title
            AdviceId.LATE -> R.string.advice_late_title
            AdviceId.UNLOCKS -> R.string.advice_unlocks_title
            AdviceId.LIGHT -> R.string.advice_light_title
            AdviceId.WEEKEND -> R.string.advice_weekend_title
            AdviceId.STEADY -> R.string.advice_steady_title
        }
        val body = when (id) {
            AdviceId.EMPTY -> app.getString(R.string.advice_empty_body)
            AdviceId.CONCENTRATED -> app.getString(R.string.advice_concentrated_body, topName)
            AdviceId.RISING -> app.getString(R.string.advice_rising_body)
            AdviceId.FALLING -> app.getString(R.string.advice_falling_body)
            AdviceId.LATE -> app.getString(R.string.advice_late_body)
            AdviceId.UNLOCKS -> app.getString(R.string.advice_unlocks_body)
            AdviceId.LIGHT -> app.getString(R.string.advice_light_body)
            AdviceId.WEEKEND -> app.getString(R.string.advice_weekend_body)
            AdviceId.STEADY -> app.getString(R.string.advice_steady_body)
        }
        return ReportAdvice(app.getString(title), body)
    }
}
