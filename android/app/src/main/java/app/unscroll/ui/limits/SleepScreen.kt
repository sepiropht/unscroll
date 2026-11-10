package app.unscroll.ui.limits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.ui.guard.AppPickerDialog
import app.unscroll.ui.guard.HourMinuteField
import app.unscroll.ui.guard.WeekdayRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    onBack: () -> Unit,
    vm: SleepViewModel = viewModel(),
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
                title = { Text(stringResource(R.string.sleep_title)) },
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.sleep_lead), style = MaterialTheme.typography.bodyLarge)
            if (ui.activeNow && !ui.dirty) {
                Text(
                    stringResource(R.string.sleep_now),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sleep_enabled),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(checked = ui.enabled, onCheckedChange = vm::setEnabled)
            }
            HourMinuteField(
                label = stringResource(R.string.sleep_bedtime),
                minuteOfDay = ui.startMinute,
                onChange = vm::setStart,
            )
            HourMinuteField(
                label = stringResource(R.string.sleep_wake),
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
            Text(stringResource(R.string.sleep_allow), style = MaterialTheme.typography.titleMedium)
            val labels = ui.apps.filter { it.packageName in ui.packages }.map { it.label }
            Text(
                if (ui.packages.isEmpty()) {
                    stringResource(R.string.sleep_allow_none)
                } else {
                    labels.joinToString(" · ").ifBlank { stringResource(R.string.schedule_apps_count, ui.packages.size) }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.focus_pick))
            }
            OutlinedButton(onClick = vm::restoreSuggested, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sleep_restore))
            }
            ui.error?.let { error ->
                val message = when (error) {
                    SleepError.SPAN -> stringResource(R.string.schedule_need_span)
                    SleepError.DAY -> stringResource(R.string.schedule_need_day)
                }
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = vm::save, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sleep_save))
            }
        }
    }
}
