package app.unscroll.ui.limits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.data.guard.FocusDurations
import app.unscroll.ui.guard.AppPickerDialog
import app.unscroll.ui.guard.HourMinuteField
import app.unscroll.ui.guard.WeekdayRow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FocusRoutineScreen(
    onBack: () -> Unit,
    vm: FocusRoutineViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf("") }
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ui.ready, ui.durationMinutes) {
        if (!ui.ready || seeded) return@LaunchedEffect
        if (ui.durationMinutes !in FocusDurations.PRESETS) custom = ui.durationMinutes.toString()
        seeded = true
    }
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
                        stringResource(
                            if (ui.persisted) R.string.focus_routine_edit else R.string.focus_routine_new,
                        ),
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
                Text(stringResource(R.string.focus_routine_missing))
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
            Text(stringResource(R.string.focus_routines_lead), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = ui.name,
                onValueChange = vm::setName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.focus_routine_name)) },
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.focus_routine_enabled),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(checked = ui.enabled, onCheckedChange = vm::setEnabled)
            }
            HourMinuteField(
                label = stringResource(R.string.focus_routine_start),
                minuteOfDay = ui.startMinute,
                onChange = vm::setStart,
            )
            Text(stringResource(R.string.focus_duration), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusDurations.PRESETS.forEach { minutes ->
                    FilterChip(
                        selected = ui.durationMinutes == minutes && custom.isBlank(),
                        onClick = {
                            custom = ""
                            vm.setDuration(minutes)
                        },
                        label = { Text(stringResource(R.string.minutes_short, minutes)) },
                    )
                }
            }
            OutlinedTextField(
                value = custom,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(3)
                    custom = digits
                    digits.toIntOrNull()?.takeIf { it in 1..FocusDurations.MAX }?.let(vm::setDuration)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.choice_custom_minutes)) },
                singleLine = true,
            )
            Text(stringResource(R.string.limits_days), style = MaterialTheme.typography.titleMedium)
            WeekdayRow(mask = ui.weekdaysMask, onChange = vm::setDays)
            Text(stringResource(R.string.focus_apps), style = MaterialTheme.typography.titleMedium)
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
            ui.error?.let { error ->
                val message = when (error) {
                    RoutineError.NAME -> stringResource(R.string.focus_routine_need_name)
                    RoutineError.APPS -> stringResource(R.string.schedule_need_apps)
                    RoutineError.DAY -> stringResource(R.string.schedule_need_day)
                    RoutineError.DURATION -> stringResource(R.string.focus_routine_need_duration)
                }
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = { vm.save(onBack) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.focus_routine_save))
            }
            if (ui.persisted) {
                TextButton(onClick = { vm.delete(onBack) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.focus_routine_delete))
                }
            }
        }
    }
}
