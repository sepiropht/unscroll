package app.unscroll.ui.websites

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.unscroll.data.guard.Domains
import app.unscroll.data.guard.GuardRepository
import app.unscroll.data.guard.Weekdays
import app.unscroll.data.stats.LimitKeys
import app.unscroll.permissions.PermissionChecks
import app.unscroll.ui.detail.BlockSpan
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WebsiteRow(
    val domain: String,
    val todayMillis: Long,
    val todayVisits: Int,
    val weekMillis: Long,
    val limitMillis: Long?,
    val coveredBy: String?,
    val blockUntil: Long?,
    val fraction: Float,
)

data class WebsitesUi(
    val loading: Boolean = true,
    val accessibility: Boolean = false,
    val rows: List<WebsiteRow> = emptyList(),
)

class WebsitesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = GuardRepository(app)
    private val _ui = MutableStateFlow(WebsitesUi())
    val ui: StateFlow<WebsitesUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { loadRows() }
            _ui.value = WebsitesUi(
                loading = false,
                accessibility = PermissionChecks.isAccessibilityEnabled(getApplication()),
                rows = rows,
            )
        }
    }

    fun ensure(domain: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.ensureWeb(domain) }
            refresh()
        }
    }

    private suspend fun loadRows(): List<WebsiteRow> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val stored = repo.webBetween(weekStart, tomorrow)
        val limits = repo.limits().filter { LimitKeys.isWeb(it.targetKey) && it.dailyLimitMillis > 0L }
        val blocks = repo.blocks().filter { row ->
            LimitKeys.isWeb(row.targetKey) &&
                (row.untilMillis == LimitKeys.PERMANENT_UNTIL || row.untilMillis > now)
        }
        val domains = buildSet {
            stored.forEach { add(it.domain) }
            limits.forEach { add(LimitKeys.payload(it.targetKey)) }
            blocks.forEach { add(LimitKeys.payload(it.targetKey)) }
        }
        val limitByHost = limits.associate { LimitKeys.payload(it.targetKey) to it.dailyLimitMillis }
        val rules = limitByHost.keys
        val todayByHost = stored.filter { it.dayStart == todayStart }.associateBy { it.domain }
        val weekByHost = stored.groupBy { it.domain }.mapValues { (_, rows) -> rows.sumOf { it.durationMillis } }
        val blockByHost = blocks.associate { LimitKeys.payload(it.targetKey) to it.untilMillis }
        val peak = domains.maxOfOrNull { todayByHost[it]?.durationMillis ?: 0L } ?: 0L
        return domains.map { domain ->
            val todayRow = todayByHost[domain]
            val todayMillis = todayRow?.durationMillis ?: 0L
            WebsiteRow(
                domain = domain,
                todayMillis = todayMillis,
                todayVisits = todayRow?.visitCount ?: 0,
                weekMillis = weekByHost[domain] ?: 0L,
                limitMillis = limitByHost[domain],
                coveredBy = rules
                    .filter { it != domain && Domains.covers(domain, it) }
                    .maxByOrNull { it.length },
                blockUntil = blockByHost[domain],
                fraction = if (peak <= 0L) 0f else todayMillis.toFloat() / peak.toFloat(),
            )
        }.sortedWith(compareByDescending<WebsiteRow> { it.todayMillis }.thenByDescending { it.weekMillis }.thenBy { it.domain })
    }
}

data class WebsiteDetailUi(
    val loading: Boolean = true,
    val domain: String = "",
    val invalid: Boolean = false,
    val todayMillis: Long = 0L,
    val todayVisits: Int = 0,
    val weekMillis: Long = 0L,
    val includesSubdomains: Boolean = false,
    val limitMillis: Long? = null,
    val weekdaysMask: Int = Weekdays.ALL,
    val blockUntil: Long? = null,
    val coveredBy: String? = null,
)

class WebsiteDetailViewModel(
    app: Application,
    saved: SavedStateHandle,
) : AndroidViewModel(app) {
    private val domain: String? = Domains.normalize(saved.get<String>("domain"))
    private val repo = GuardRepository(app)
    private val _ui = MutableStateFlow(WebsiteDetailUi(domain = domain.orEmpty(), invalid = domain == null))
    val ui: StateFlow<WebsiteDetailUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val host = domain ?: return
        viewModelScope.launch {
            val built = withContext(Dispatchers.IO) {
                repo.ensureWeb(host)
                load(host)
            }
            _ui.value = built
        }
    }

    fun saveLimit(dailyLimitMillis: Long?, weekdaysMask: Int) {
        val host = domain ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.saveDailyLimit(LimitKeys.web(host), dailyLimitMillis, weekdaysMask)
            }
            refresh()
        }
    }

    fun saveBlock(span: BlockSpan) {
        val host = domain ?: return
        val until = untilMillis(span)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.saveBlock(LimitKeys.web(host), until) }
            refresh()
        }
    }

    fun clearBlock() {
        val host = domain ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.clearBlock(LimitKeys.web(host)) }
            refresh()
        }
    }

    private suspend fun load(host: String): WebsiteDetailUi {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val coveredRows = repo.webBetween(weekStart, tomorrow).filter { Domains.covers(it.domain, host) }
        val exact = coveredRows.filter { it.domain == host }
        val todayExact = exact.filter { it.dayStart == todayStart }.sumOf { it.durationMillis }
        val weekExact = exact.sumOf { it.durationMillis }
        val todayAll = coveredRows.filter { it.dayStart == todayStart }.sumOf { it.durationMillis }
        val weekAll = coveredRows.sumOf { it.durationMillis }
        val limit = repo.limits().find { it.targetKey == LimitKeys.web(host) }
        val block = repo.activeBlock(LimitKeys.web(host))
        val covered = repo.limits()
            .filter { LimitKeys.isWeb(it.targetKey) && it.dailyLimitMillis > 0L }
            .map { LimitKeys.payload(it.targetKey) }
            .filter { it != host && Domains.covers(host, it) }
            .maxByOrNull { it.length }
        return WebsiteDetailUi(
            loading = false,
            domain = host,
            todayMillis = todayAll,
            todayVisits = coveredRows.filter { it.dayStart == todayStart }.sumOf { it.visitCount },
            weekMillis = weekAll,
            includesSubdomains = todayAll > todayExact || weekAll > weekExact,
            limitMillis = limit?.dailyLimitMillis?.takeIf { it > 0L },
            weekdaysMask = limit?.weekdaysMask ?: Weekdays.ALL,
            blockUntil = block?.untilMillis?.takeIf { it == LimitKeys.PERMANENT_UNTIL || it > now },
            coveredBy = covered,
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
