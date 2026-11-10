package app.unscroll.ui.history

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.ui.chart.CategoryRing
import app.unscroll.ui.chart.DayBarChart
import app.unscroll.ui.chart.ShareLegend
import app.unscroll.ui.common.UsageAccessGate
import app.unscroll.ui.format.formatDayLong
import app.unscroll.ui.format.formatDayShort
import app.unscroll.ui.format.formatDuration
import app.unscroll.ui.format.formatWeekdayShort
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    vm: HistoryViewModel = viewModel(),
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
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            ui.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !ui.usageAccess -> UsageAccessGate(
                onOpenPermissions = onOpenPermissions,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> HistoryBody(ui, vm::setSpan, Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryBody(
    ui: HistoryUiState,
    onSpan: (HistorySpan) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardColor = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val shape = RoundedCornerShape(22.dp)
    val ringDescription = stringResource(R.string.cd_ring)
    val barsDescription = stringResource(R.string.cd_day_bars)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = ui.span == HistorySpan.DAYS_7,
                        onClick = { onSpan(HistorySpan.DAYS_7) },
                        label = { Text(stringResource(R.string.history_7)) },
                    )
                    FilterChip(
                        selected = ui.span == HistorySpan.DAYS_30,
                        onClick = { onSpan(HistorySpan.DAYS_30) },
                        label = { Text(stringResource(R.string.history_30)) },
                    )
                }
                Text(
                    stringResource(R.string.history_window_caption, ui.days.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Card(colors = cardColor, shape = shape) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        formatDuration(ui.totalMillis),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.history_average, formatDuration(ui.averageMillis)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (ui.maxDate != null) {
                            stringResource(R.string.history_max, formatDayLong(ui.maxDate), formatDuration(ui.maxMillis))
                        } else {
                            stringResource(R.string.history_no_max)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Card(colors = cardColor, shape = shape) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayBarChart(
                        values = ui.days.map { it.millis },
                        highlightIndex = ui.days.indexOfLast { it.date == ui.maxDate }.takeIf { it >= 0 },
                        barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                        highlightColor = MaterialTheme.colorScheme.secondary,
                        averageColor = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.semantics { contentDescription = barsDescription },
                    )
                    DayTicks(ui)
                }
            }
        }
        item {
            Card(colors = cardColor, shape = shape) {
                Column(
                    Modifier
                        .padding(18.dp)
                        .semantics { contentDescription = ringDescription },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.history_ring_title), style = MaterialTheme.typography.titleMedium)
                    if (ui.shares.isEmpty()) {
                        Text(
                            stringResource(R.string.history_ring_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CategoryRing(
                                fractions = ui.shares.map { it.fraction },
                                colors = ui.shares.map { Color(it.colorArgb) },
                                track = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Text(
                                formatDuration(ui.totalMillis),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        ShareLegend(ui.shares)
                    }
                }
            }
        }
        item { Spacer(Modifier.size(4.dp)) }
    }
}

@Composable
private fun DayTicks(ui: HistoryUiState) {
    if (ui.days.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (ui.days.size <= 7) {
            ui.days.forEach { day ->
                Text(
                    formatWeekdayShort(day.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val indexes = listOf(0, ui.days.lastIndex / 2, ui.days.lastIndex)
            indexes.forEach { index ->
                Text(
                    formatDayShort(ui.days[index].date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
