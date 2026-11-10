package app.unscroll.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.data.stats.PeriodKind
import app.unscroll.ui.common.PackageIcon
import app.unscroll.ui.common.UsageAccessGate
import app.unscroll.ui.format.formatDuration
import java.text.Collator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenLimits: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenWebsites: () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenApp: (String) -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (ui.loading && ui.apps.isEmpty() && ui.totalMillis == 0L) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.loading))
                }
            }
        } else if (!ui.usageAccess) {
            UsageAccessGate(
                onOpenPermissions = onOpenPermissions,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            DashboardBody(
                ui = ui,
                onPeriod = vm::setPeriod,
                onShift = vm::shift,
                onSort = vm::setSort,
                onOpenHistory = onOpenHistory,
                onOpenCategories = onOpenCategories,
                onOpenReport = onOpenReport,
                onOpenLimits = onOpenLimits,
                onOpenFocus = onOpenFocus,
                onOpenSleep = onOpenSleep,
                onOpenWebsites = onOpenWebsites,
                onOpenAchievements = onOpenAchievements,
                onOpenApp = onOpenApp,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardBody(
    ui: DashboardUiState,
    onPeriod: (PeriodKind) -> Unit,
    onShift: (Int) -> Unit,
    onSort: (AppSort) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenLimits: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenWebsites: () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val collator = remember { Collator.getInstance() }
    val rows = remember(ui.apps, ui.sort) {
        when (ui.sort) {
            AppSort.DURATION -> ui.apps.sortedWith(
                compareByDescending<AppUsageRow> { it.durationMillis }.thenBy { it.label },
            )
            AppSort.OPENS -> ui.apps.sortedWith(
                compareByDescending<AppUsageRow> { it.openCount }.thenByDescending { it.durationMillis },
            )
            AppSort.NAME -> ui.apps.sortedWith { a, b -> collator.compare(a.label, b.label) }
        }
    }
    val periods = listOf(PeriodKind.DAY, PeriodKind.WEEK, PeriodKind.MONTH)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { onShift(-1) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.previous_period),
                    )
                }
                Text(
                    ui.rangeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onShift(1) }, enabled = ui.canGoForward) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.next_period),
                    )
                }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                periods.forEachIndexed { index, kind ->
                    SegmentedButton(
                        selected = ui.period == kind,
                        onClick = { onPeriod(kind) },
                        shape = SegmentedButtonDefaults.itemShape(index, periods.size),
                        icon = {},
                    ) {
                        Text(
                            when (kind) {
                                PeriodKind.DAY -> stringResource(R.string.period_day)
                                PeriodKind.WEEK -> stringResource(R.string.period_week)
                                PeriodKind.MONTH -> stringResource(R.string.period_month)
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        formatDuration(ui.totalMillis),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    ui.versusLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    ui.averageLabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        pluralStringResource(R.plurals.unlock_count, ui.unlockCount, ui.unlockCount),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    ui.notificationCount?.let { count ->
                        Text(
                            pluralStringResource(R.plurals.notif_count, count, count),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.hourly_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.hourly_max, formatDuration(ui.hourly.maxOrNull() ?: 0L)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                    if (ui.hourlyAggregated) {
                        Text(
                            stringResource(R.string.hourly_caption_range),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val peak = formatDuration(ui.hourly.maxOrNull() ?: 0L)
                    val chartDescription = stringResource(R.string.hourly_title) + ". " +
                        stringResource(R.string.hourly_max, peak)
                    HourlyBarChart(
                        values = ui.hourly,
                        highlightHour = ui.highlightHour,
                        barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                        highlightColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.semantics { contentDescription = chartDescription },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("0h", "6h", "12h", "18h", "24h").forEach { label ->
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.apps_heading), style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SortChip(stringResource(R.string.sort_time), ui.sort == AppSort.DURATION) {
                        onSort(AppSort.DURATION)
                    }
                    SortChip(stringResource(R.string.sort_opens), ui.sort == AppSort.OPENS) {
                        onSort(AppSort.OPENS)
                    }
                    SortChip(stringResource(R.string.sort_name), ui.sort == AppSort.NAME) {
                        onSort(AppSort.NAME)
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            item {
                Text(
                    if (ui.totalMillis == 0L) {
                        stringResource(R.string.no_usage)
                    } else {
                        stringResource(R.string.no_apps)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(rows, key = { it.packageName }) { row ->
                AppUsageLine(row, onOpen = { onOpenApp(row.packageName) })
            }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun AppUsageLine(row: AppUsageRow, onOpen: () -> Unit) {
    val duration = formatDuration(row.durationMillis)
    val opens = pluralStringResource(R.plurals.open_count, row.openCount, row.openCount)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "${row.label}, $duration, $opens" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PackageIcon(row.packageName, row.label)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    opens,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(duration, style = MaterialTheme.typography.titleMedium)
        }
        ProportionBar(
            fraction = row.fraction,
            color = MaterialTheme.colorScheme.primary,
            track = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}


