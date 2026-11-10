package app.unscroll.ui.limits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.ui.guard.AppPickerDialog
import app.unscroll.ui.guard.DomainEditor
import app.unscroll.ui.guard.HourMinuteField
import app.unscroll.ui.guard.WeekdayRow
import app.unscroll.ui.guard.formatMinuteOfDay
import app.unscroll.ui.guard.weekdayCaption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
    vm: SchedulesViewModel = viewModel(),
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.schedules_lead), style = MaterialTheme.typography.bodyLarge)
            if (ui.loading) {
                CircularProgressIndicator()
            } else if (ui.rows.isEmpty()) {
                Text(
                    stringResource(R.string.schedules_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.rows.forEach { row ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(row.id) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${formatMinuteOfDay(row.startMinute, LocalContext.current)} – ${formatMinuteOfDay(row.endMinute, LocalContext.current)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                weekdayCaption(row.weekdaysMask),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.schedule_apps_count, row.appCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (row.siteCount > 0) {
                                Text(
                                    pluralStringResource(R.plurals.schedule_sites_count, row.siteCount, row.siteCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(checked = row.enabled, onCheckedChange = { vm.toggle(row.id) })
                    }
                }
            }
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.schedules_add))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(
    onBack: () -> Unit,
    vm: ScheduleEditorViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        AppPickerDialog(
            apps = ui.apps,
            selected = ui.packages,
            onDismiss = { picking = false },
            onToggle = vm::togglePackage,
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (ui.name.isBlank()) R.string.schedule_new else R.string.schedule_edit),
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
        if (!ui.ready) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (ui.missing) {
            Column(Modifier.padding(padding).padding(20.dp)) {
                Text(stringResource(R.string.schedule_missing))
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
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
            OutlinedTextField(
                value = ui.name,
                onValueChange = vm::setName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.schedule_name)) },
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.schedule_enabled),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(checked = ui.enabled, onCheckedChange = vm::setEnabled)
            }
            HourMinuteField(
                label = stringResource(R.string.schedule_start),
                minuteOfDay = ui.startMinute,
                onChange = vm::setStart,
            )
            HourMinuteField(
                label = stringResource(R.string.schedule_end),
                minuteOfDay = ui.endMinute,
                onChange = vm::setEnd,
            )
            if (ui.endMinute < ui.startMinute) {
                Text(
                    stringResource(R.string.schedule_overnight),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(stringResource(R.string.limits_days), style = MaterialTheme.typography.titleMedium)
            WeekdayRow(mask = ui.weekdaysMask, onChange = vm::setDays)
            Text(stringResource(R.string.schedule_apps), style = MaterialTheme.typography.titleMedium)
            Text(
                if (ui.packages.isEmpty()) {
                    stringResource(R.string.schedule_apps_none)
                } else {
                    stringResource(R.string.schedule_apps_count, ui.packages.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.focus_pick))
            }
            Text(stringResource(R.string.schedule_sites), style = MaterialTheme.typography.titleMedium)
            Text(
                if (ui.domains.isEmpty()) {
                    stringResource(R.string.schedule_sites_none)
                } else {
                    pluralStringResource(R.plurals.schedule_sites_count, ui.domains.size, ui.domains.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DomainEditor(domains = ui.domains, onChange = vm::setDomains)
            ui.error?.let { error ->
                val message = when (error) {
                    ScheduleError.NAME -> stringResource(R.string.schedule_need_name)
                    ScheduleError.APPS -> stringResource(R.string.schedule_need_apps)
                    ScheduleError.SPAN -> stringResource(R.string.schedule_need_span)
                    ScheduleError.DAY -> stringResource(R.string.schedule_need_day)
                }
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = { vm.save(onBack) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.schedule_save))
            }
            if (ui.persisted) {
                TextButton(onClick = { vm.delete(onBack) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.schedule_delete))
                }
            }
        }
    }
}
