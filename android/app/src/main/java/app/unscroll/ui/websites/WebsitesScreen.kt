package app.unscroll.ui.websites

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.ui.permissions.rememberJitPermissionGate
import app.unscroll.ui.permissions.requireAccessibility
import app.unscroll.ui.permissions.requireOverlay
import app.unscroll.ui.permissions.requireNotifications
import app.unscroll.data.guard.Domains
import app.unscroll.data.stats.LimitKeys
import app.unscroll.ui.dashboard.ProportionBar
import app.unscroll.ui.detail.BlockNowDialog
import app.unscroll.ui.format.formatDuration
import app.unscroll.ui.format.formatWhen
import app.unscroll.ui.guard.DailyLimitEditorDialog
import app.unscroll.ui.guard.EditGuardDialog
import app.unscroll.ui.guard.rememberEditGuard
import app.unscroll.ui.guard.weekdayCaption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebsitesScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenDomain: (String) -> Unit,
    vm: WebsitesViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.websites_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (ui.loading && ui.rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(stringResource(R.string.websites_lead), style = MaterialTheme.typography.bodyLarge)
            }
            if (!ui.accessibility) {
                item {
                    NoticeCard(
                        title = stringResource(R.string.websites_a11y),
                        action = stringResource(R.string.limits_a11y_open),
                        onClick = onOpenPermissions,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.domain_add)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text(stringResource(R.string.websites_invalid)) }
                    } else {
                        null
                    },
                )
                OutlinedButton(
                    onClick = {
                        val domain = Domains.normalize(draft)
                        if (domain == null) {
                            invalid = true
                        } else {
                            draft = ""
                            vm.ensure(domain)
                            onOpenDomain(domain)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.websites_add))
                }
            }
            if (ui.rows.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.websites_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(ui.rows, key = { it.domain }) { row ->
                    WebsiteLine(row, onOpen = { onOpenDomain(row.domain) })
                }
            }
        }
    }
}

@Composable
private fun WebsiteLine(row: WebsiteRow, onOpen: () -> Unit) {
    val duration = formatDuration(row.todayMillis)
    val visits = pluralStringResource(R.plurals.visit_count, row.todayVisits, row.todayVisits)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "${row.domain}, $duration, $visits" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    row.domain.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.domain,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    visits,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                row.limitMillis?.let { limit ->
                    Text(
                        stringResource(R.string.limit_current, formatDuration(limit)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (row.limitMillis == null) {
                    row.coveredBy?.let { parent ->
                        Text(
                            stringResource(R.string.websites_covered, parent),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                row.blockUntil?.let { until ->
                    val label = if (until == LimitKeys.PERMANENT_UNTIL) {
                        stringResource(R.string.block_active_permanent)
                    } else {
                        stringResource(R.string.block_active_until, formatWhen(until, LocalContext.current))
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebsiteDetailScreen(
    onBack: () -> Unit,
    vm: WebsiteDetailViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var editingLimit by remember { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf(false) }
    val guard = rememberEditGuard()
    val jit = rememberJitPermissionGate()
    EditGuardDialog(guard)
    if (editingLimit && !ui.invalid) {
        DailyLimitEditorDialog(
            title = ui.domain,
            currentMillis = ui.limitMillis,
            weekdaysMask = ui.weekdaysMask,
            onDismiss = { editingLimit = false },
            onSave = { millis, mask ->
                editingLimit = false
                guard.run {
                    jit.requireAccessibility {
                        jit.requireOverlay {
                            jit.requireNotifications {
                                vm.saveLimit(millis, mask)
                            }
                        }
                    }
                }
            },
        )
    }
    if (editingBlock && !ui.invalid) {
        BlockNowDialog(
            onDismiss = { editingBlock = false },
            onSave = { span ->
                editingBlock = false
                guard.run { vm.saveBlock(span) }
            },
            onClear = if (ui.blockUntil == null) {
                null
            } else {
                {
                    editingBlock = false
                    guard.run { vm.clearBlock() }
                }
            },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.domain.ifBlank { stringResource(R.string.websites_title) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (ui.invalid) {
            Column(Modifier.padding(padding).padding(20.dp)) {
                Text(stringResource(R.string.website_missing))
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            }
            return@Scaffold
        }
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.websites_today), style = MaterialTheme.typography.titleMedium)
            Text(formatDuration(ui.todayMillis), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                pluralStringResource(R.plurals.visit_count, ui.todayVisits, ui.todayVisits),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.websites_week, formatDuration(ui.weekMillis)),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (ui.includesSubdomains) {
                Text(
                    stringResource(R.string.websites_subdomains),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.coveredBy?.let { parent ->
                Text(
                    stringResource(R.string.websites_covered, parent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            ui.limitMillis?.let { limit ->
                Text(
                    stringResource(R.string.limit_current, formatDuration(limit)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    weekdayCaption(ui.weekdaysMask),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.blockUntil?.let { until ->
                val label = if (until == LimitKeys.PERMANENT_UNTIL) {
                    stringResource(R.string.block_active_permanent)
                } else {
                    stringResource(R.string.block_active_until, formatWhen(until, LocalContext.current))
                }
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            OutlinedButton(onClick = { editingLimit = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_limit))
            }
            OutlinedButton(onClick = { editingBlock = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_block))
            }
        }
    }
}

@Composable
private fun NoticeCard(title: String, action: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(action, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
