package app.unscroll.ui.categories

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.ui.common.PackageIcon
import app.unscroll.ui.detail.CategoryPickerDialog
import app.unscroll.ui.detail.DailyLimitDialog
import app.unscroll.ui.guard.EditGuardDialog
import app.unscroll.ui.guard.rememberEditGuard
import app.unscroll.ui.format.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenPermissions: () -> Unit,
    vm: CategoryDetailViewModel = viewModel(),
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
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var reassignPackage by rememberSaveable { mutableStateOf("") }
    val guard = rememberEditGuard()
    EditGuardDialog(guard)
    if (showLimit) {
        DailyLimitDialog(
            title = stringResource(R.string.category_limit_title),
            currentMillis = ui.limitMillis,
            onDismiss = { showLimit = false },
            onSave = { millis ->
                showLimit = false
                guard.run { vm.saveLimit(millis) }
            },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.category_delete_title, ui.label)) },
            text = { Text(stringResource(R.string.category_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    guard.run { vm.delete(onBack) }
                }) { Text(stringResource(R.string.category_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (reassignPackage.isNotEmpty()) {
        val packageToMove = reassignPackage
        CategoryPickerDialog(
            categories = ui.categories,
            selectedKey = ui.key,
            onDismiss = { reassignPackage = "" },
            onPick = { key ->
                reassignPackage = ""
                vm.assign(packageToMove, key)
            },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (ui.missing) stringResource(R.string.categories_title) else ui.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
            ui.missing -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.category_missing))
            }
            else -> DetailBody(
                ui = ui,
                onOpenApp = onOpenApp,
                onOpenPermissions = onOpenPermissions,
                onLimit = { showLimit = true },
                onReassign = { reassignPackage = it },
                onDelete = { showDelete = true },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun DetailBody(
    ui: CategoryDetailUiState,
    onOpenApp: (String) -> Unit,
    onOpenPermissions: () -> Unit,
    onLimit: () -> Unit,
    onReassign: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardColor = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val shape = RoundedCornerShape(22.dp)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(ui.colorArgb)),
                )
                Spacer(Modifier.width(10.dp))
                Text(ui.label, style = MaterialTheme.typography.headlineSmall)
            }
        }
        if (!ui.usageAccess) {
            item {
                Card(colors = cardColor, shape = shape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.usage_gate_body))
                        TextButton(onClick = onOpenPermissions) {
                            Text(stringResource(R.string.usage_gate_action))
                        }
                    }
                }
            }
        }
        item {
            Card(colors = cardColor, shape = shape) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mini(stringResource(R.string.detail_today), formatDuration(ui.todayMillis), Modifier.weight(1f))
                    Mini(stringResource(R.string.detail_seven_days), formatDuration(ui.weekMillis), Modifier.weight(1f))
                    Mini(stringResource(R.string.detail_average), formatDuration(ui.averageMillis), Modifier.weight(1f))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.limitMillis?.let { limit ->
                    Text(
                        stringResource(R.string.limit_current, formatDuration(limit)),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(onClick = onLimit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.category_limit_title))
                }
                Text(
                    stringResource(R.string.enforcement_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Text(stringResource(R.string.category_apps), style = MaterialTheme.typography.titleMedium)
        }
        if (ui.apps.isEmpty()) {
            item {
                Text(stringResource(R.string.category_no_apps), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(ui.apps, key = { it.packageName }) { app ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PackageIcon(app.packageName, app.label)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(
                                    R.string.categories_row_meta,
                                    formatDuration(app.todayMillis),
                                    formatDuration(app.weekMillis),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (app.excluded) {
                                Text(
                                    stringResource(R.string.category_excluded_tag),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = { onOpenApp(app.packageName) }) {
                            Text(stringResource(R.string.open_detail))
                        }
                        TextButton(onClick = { onReassign(app.packageName) }) {
                            Text(stringResource(R.string.category_change))
                        }
                    }
                }
            }
        }
        if (ui.custom) {
            item {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.category_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun Mini(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
