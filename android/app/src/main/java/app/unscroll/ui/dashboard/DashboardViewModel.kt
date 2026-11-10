package app.unscroll.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unscroll.R
import app.unscroll.data.repo.UsageRepository
import app.unscroll.data.stats.PeriodKind
import app.unscroll.data.stats.Periods
import app.unscroll.data.stats.excluding
import app.unscroll.permissions.PermissionChecks
import app.unscroll.prefs.AppPrefs
import app.unscroll.ui.format.formatDuration
import app.unscroll.ui.format.percentDelta
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppSort { DURATION, OPENS, NAME }

data class AppUsageRow(
    val packageName: String,
    val label: String,
    val durationMillis: Long,
    val openCount: Int,
    val fraction: Float,
)

data class DashboardUiState(
    val loading: Boolean = true,
    val usageAccess: Boolean = false,
    val period: PeriodKind = PeriodKind.DAY,
    val anchor: LocalDate = LocalDate.now(),
    val rangeLabel: String = "",
    val canGoForward: Boolean = false,
    val totalMillis: Long = 0L,
    val versusLabel: String? = null,
    val averageLabel: String? = null,
    val unlockCount: Int = 0,
    /** Null lorsque le compteur est masqué (service non activé et aucun chiffre enregistré). */
    val notificationCount: Int? = null,
    val hourly: List<Long> = List(24) { 0L },
    val highlightHour: Int? = null,
    val hourlyAggregated: Boolean = false,
    val sort: AppSort = AppSort.DURATION,
    val apps: List<AppUsageRow> = emptyList(),
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = UsageRepository(app)
    private val _ui = MutableStateFlow(DashboardUiState())
    val ui: StateFlow<DashboardUiState> = _ui.asStateFlow()

    private var generation = 0
    private var loadedOnce = false

    init {
        refresh()
    }

    fun onResume() {
        if (loadedOnce) refresh()
    }

    fun setPeriod(period: PeriodKind) {
        _ui.update { it.copy(period = period) }
        refresh()
    }

    fun shift(by: Int) {
        val today = LocalDate.now()
        _ui.update { state ->
            state.copy(anchor = Periods.shift(state.anchor, state.period, by, today))
        }
        refresh()
    }

    fun setSort(sort: AppSort) {
        _ui.update { it.copy(sort = sort) }
    }

    fun refresh() {
        val gen = ++generation
        val snapshot = _ui.value
        viewModelScope.launch {
            if (!loadedOnce) _ui.update { it.copy(loading = true) }
            val built = withContext(Dispatchers.IO) {
                build(snapshot.period, snapshot.anchor)
            }
            if (gen != generation) return@launch
            loadedOnce = true
            _ui.value = built.copy(sort = _ui.value.sort)
        }
    }

    private suspend fun build(period: PeriodKind, anchor: LocalDate): DashboardUiState {
        val app = getApplication<Application>()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val access = PermissionChecks.hasUsageAccess(app)
        val weekStarts = AppPrefs(app).weekStartDay()
        val (loadFrom, loadTo) = Periods.loadSpan(anchor, period, weekStarts)
        val excluded = repo.statsExclusions()
        val days = if (access) {
            repo.loadDays(loadFrom, loadTo).map { it.excluding(excluded) }
        } else {
            emptyList()
        }
        val (start, end) = Periods.range(anchor, period, weekStarts)
        val shown = days.filter { !it.date.isBefore(start) && it.date.isBefore(end) }
        val total = shown.sumOf { it.totalMillis }
        val unlocks = shown.sumOf { it.unlockCount }
        val hourly = LongArray(24)
        for (day in shown) {
            day.hourly.forEachIndexed { index, value -> hourly[index] += value }
        }
        val merged = linkedMapOf<String, Pair<Long, Int>>()
        for (day in shown) {
            for (pkg in day.packages) {
                val current = merged[pkg.packageName]
                merged[pkg.packageName] = (current?.first ?: 0L) + pkg.durationMillis to
                    (current?.second ?: 0) + pkg.openCount
            }
        }
        val apps = merged.map { (packageName, totals) ->
            AppUsageRow(
                packageName = packageName,
                label = repo.appLabel(packageName),
                durationMillis = totals.first,
                openCount = totals.second,
                fraction = if (total > 0L) totals.first.toFloat() / total.toFloat() else 0f,
            )
        }.filter { it.durationMillis > 0L || it.openCount > 0 }

        val byDate = days.associateBy { it.date }
        val versusYesterday = period == PeriodKind.DAY
        val baseline = when (period) {
            PeriodKind.DAY -> byDate[anchor.minusDays(1)]?.totalMillis ?: 0L
            PeriodKind.WEEK -> {
                val weekStart = Periods.range(anchor, PeriodKind.WEEK, weekStarts).first
                val prev = weekStart.minusWeeks(1)
                days.filter { !it.date.isBefore(prev) && it.date.isBefore(weekStart) }
                    .sumOf { it.totalMillis }
            }
            PeriodKind.MONTH -> {
                val monthStart = Periods.range(anchor, PeriodKind.MONTH, weekStarts).first
                val prev = monthStart.minusMonths(1)
                days.filter { !it.date.isBefore(prev) && it.date.isBefore(monthStart) }
                    .sumOf { it.totalMillis }
            }
        }
        val delta = percentDelta(total, baseline)
        val versus = when {
            delta == null -> app.getString(R.string.no_baseline)
            abs(delta) <= 1 -> app.getString(
                if (versusYesterday) R.string.delta_same else R.string.delta_same_period,
            )
            delta < 0 && versusYesterday -> app.getString(R.string.delta_less, abs(delta))
            delta > 0 && versusYesterday -> app.getString(R.string.delta_more, abs(delta))
            delta < 0 -> app.getString(R.string.delta_less_period, abs(delta))
            else -> app.getString(R.string.delta_more_period, abs(delta))
        }
        val average = if (period == PeriodKind.DAY) {
            val sum = (1..7).sumOf { offset ->
                byDate[anchor.minusDays(offset.toLong())]?.totalMillis ?: 0L
            }
            app.getString(R.string.average_7, formatDuration(sum / 7L, app))
        } else {
            null
        }
        val storedNotifications = if (access) repo.notificationsBetween(start, end, excluded) else 0
        val showNotifications = false
        return DashboardUiState(
            loading = false,
            usageAccess = access,
            period = period,
            anchor = anchor,
            rangeLabel = rangeLabel(app, anchor, period, today, weekStarts),
            canGoForward = Periods.canGoForward(anchor, period, today, weekStarts),
            totalMillis = total,
            versusLabel = versus,
            averageLabel = average,
            unlockCount = unlocks,
            notificationCount = if (showNotifications) storedNotifications else null,
            hourly = hourly.toList(),
            highlightHour = if (period == PeriodKind.DAY && anchor == today) {
                LocalTime.now(zone).hour
            } else {
                null
            },
            hourlyAggregated = period != PeriodKind.DAY,
            sort = AppSort.DURATION,
            apps = apps,
        )
    }

    private fun rangeLabel(
        app: Application,
        anchor: LocalDate,
        period: PeriodKind,
        today: LocalDate,
        weekStarts: DayOfWeek,
    ): String {
        val locale = Locale.getDefault()
        val (start, end) = Periods.range(anchor, period, weekStarts)
        val endInclusive = end.minusDays(1)
        return when (period) {
            PeriodKind.DAY -> when (anchor) {
                today -> app.getString(R.string.today)
                today.minusDays(1) -> app.getString(R.string.yesterday)
                else -> anchor.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))
            }
            PeriodKind.WEEK -> {
                val fmt = DateTimeFormatter.ofPattern("d MMM", locale)
                "${start.format(fmt)} – ${endInclusive.format(fmt)}"
            }
            PeriodKind.MONTH -> {
                val raw = start.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
                raw.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(locale) else char.toString()
                }
            }
        }
    }
}
