package app.unscroll.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.ui.permissions.rememberJitPermissionGate
import app.unscroll.ui.permissions.requireOverlay
import app.unscroll.ui.permissions.requireNotifications
import app.unscroll.data.stats.LimitKeys
import app.unscroll.ui.common.PackageIcon
import app.unscroll.ui.dashboard.HourlyBarChart
import app.unscroll.ui.chart.DayBarChart
import app.unscroll.ui.format.formatClock
import app.unscroll.ui.format.formatDuration
import app.unscroll.ui.format.formatWhen
import app.unscroll.ui.guard.DailyLimitEditorDialog
import app.unscroll.ui.guard.EditGuardDialog
import app.unscroll.ui.guard.rememberEditGuard
import app.unscroll.ui.guard.weekdayCaption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    vm: AppDetailViewModel = viewModel(),
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
    var showLimit by rememberSaveable { mutableStateOf(false) }
    var showBlock by rememberSaveable { mutableStateOf(false) }
    var showCategory by rememberSaveable { mutableStateOf(false) }
    val guard = rememberEditGuard()
    val jit = rememberJitPermissionGate()
    EditGuardDialog(guard)
    if (showLimit) {
        DailyLimitEditorDialog(
            title = stringResource(R.string.limit_title),
            currentMillis = ui.limitMillis,
            weekdaysMask = ui.limitWeekdays,
            onDismiss = { showLimit = false },
            onSave = { millis, mask ->
                showLimit = false
                guard.run {
                    jit.requireOverlay {
                        jit.requireNotifications {
                            vm.saveLimit(millis, mask)
                        }
                    }
                }
            },
        )
    }
    if (showBlock) {
        BlockNowDialog(
            onDismiss = { showBlock = false },
            onSave = { span ->
                showBlock = false
                guard.run { vm.saveBlock(span) }
            },
            onClear = if (ui.blockUntilMillis == null) {
                null
            } else {
                {
                    showBlock = false
                    guard.run { vm.clearBlock() }
                }
            },
        )
    }
    if (showCategory) {
        CategoryPickerDialog(
            categories = ui.categories,
            selectedKey = ui.categoryKey,
            onDismiss = { showCategory = false },
            onPick = { key ->
                showCategory = false
                vm.assignCategory(key)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(ui.label.ifBlank { stringResource(R.string.detail_title) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            DetailBody(
                ui = ui,
                onOpenPermissions = onOpenPermissions,
                onLimit = { showLimit = true },
                onBlock = { showBlock = true },
                onCategory = { showCategory = true },
                onExclude = { guard.run { vm.setExcluded(!ui.excluded) } },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun DetailBody(
    ui: AppDetailUiState,
    onOpenPermissions: () -> Unit,
    onLimit: () -> Unit,
    onBlock: () -> Unit,
    onCategory: () -> Unit,
    onExclude: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cardColor = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val shape = RoundedCornerShape(22.dp)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PackageIcon(ui.packageName, ui.label)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(ui.categoryColor)),
                        )
                        Text(
                            ui.categoryLabel,
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (!ui.usageAccess) {
            item {
                Card(colors = cardColor, shape = shape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.usage_gate_body), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onOpenPermissions) {
                            Text(stringResource(R.string.usage_gate_action))
                        }
                    }
                }
            }
        }
        if (ui.excluded) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = shape,
                ) {
                    Text(
                        stringResource(R.string.detail_excluded),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            Card(colors = cardColor, shape = shape) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric(stringResource(R.string.detail_today), formatDuration(ui.todayMillis), Modifier.weight(1f))
                    Metric(stringResource(R.string.detail_week), formatDuration(ui.weekMillis), Modifier.weight(1f))
                    Metric(stringResource(R.string.detail_average), formatDuration(ui.averageMillis), Modifier.weight(1f))
                }
            }
        }
        item {
            Card(colors = cardColor, shape = shape) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Fact(
                        stringResource(R.string.detail_opens_today),
                        pluralStringResource(R.plurals.open_count, ui.opensToday, ui.opensToday),
                    )
                    Fact(
                        stringResource(R.string.detail_opens_week),
                        pluralStringResource(R.plurals.open_count, ui.opensWeek, ui.opensWeek),
                    )
                    Fact(
                        stringResource(R.string.detail_avg_session),
                        if (!ui.hasSessionStats) stringResource(R.string.detail_none) else formatDuration(ui.averageSessionMillis),
                    )
                    Fact(
                        stringResource(R.string.detail_longest_session),
                        if (!ui.hasSessionStats) stringResource(R.string.detail_none) else formatDuration(ui.longestSessionMillis),
                    )
                    Fact(
                        stringResource(R.string.detail_first),
                        ui.firstUseMillis?.let { formatClock(it, context) } ?: stringResource(R.string.detail_none),
                    )
                    Fact(
                        stringResource(R.string.detail_last),
                        ui.lastUseMillis?.let { formatClock(it, context) } ?: stringResource(R.string.detail_none),
                    )
                    ui.notificationCount?.let { count ->
                        Fact(
                            stringResource(R.string.detail_notifs),
                            pluralStringResource(R.plurals.notif_count, count, count),
                        )
                    }
                }
            }
        }
        item {
            Card(colors = cardColor, shape = shape) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.detail_hourly), style = MaterialTheme.typography.titleMedium)
                    val peak = formatDuration(ui.hourly.maxOrNull() ?: 0L)
                    val chartDescription = stringResource(R.string.detail_hourly) + ". " +
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
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Card(colors = cardColor, shape = shape) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.detail_seven_days), style = MaterialTheme.typography.titleMedium)
                    DayBarChart(
                        values = ui.daily,
                        highlightIndex = ui.daily.lastIndex,
                        barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                        highlightColor = MaterialTheme.colorScheme.secondary,
                        averageColor = MaterialTheme.colorScheme.outline,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ui.dailyLabels.forEach { label ->
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (ui.sessions.isEmpty()) {
            item {
                Text(stringResource(R.string.detail_no_sessions), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            item {
                Text(
                    pluralStringResource(R.plurals.session_count, ui.sessions.size, ui.sessions.size),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(ui.sessions, key = { "${it.startMillis}-${it.endMillis}" }) { session ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(session.startMillis, context), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        formatDuration((session.endMillis - session.startMillis).coerceAtLeast(0L)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        item {
            Text(stringResource(R.string.detail_actions), style = MaterialTheme.typography.titleMedium)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.limitMillis?.let { limit ->
                    Text(
                        stringResource(R.string.limit_current, formatDuration(limit)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        weekdayCaption(ui.limitWeekdays),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ui.blockUntilMillis?.let { until ->
                    val label = if (until == LimitKeys.PERMANENT_UNTIL) {
                        stringResource(R.string.block_active_permanent)
                    } else {
                        stringResource(R.string.block_active_until, formatWhen(until, context))
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
                OutlinedButton(onClick = onLimit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_limit))
                }
                OutlinedButton(onClick = onBlock, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_block))
                }
                OutlinedButton(onClick = onCategory, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_category))
                }
                OutlinedButton(onClick = onExclude, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (ui.excluded) R.string.action_include else R.string.action_exclude))
                }
                Text(
                    stringResource(R.string.enforcement_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
