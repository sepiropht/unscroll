package app.unscroll.ui.limits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.prefs.PinGate
import app.unscroll.service.FocusTileService
import app.unscroll.ui.format.formatCountdown
import app.unscroll.ui.guard.AppPickerDialog
import app.unscroll.ui.guard.PinPad
import app.unscroll.ui.guard.formatMinuteOfDay
import app.unscroll.ui.guard.weekdayCaption
import kotlinx.coroutines.delay

private val FOCUS_PRESETS = listOf(25, 50, 90)

private enum class PinPurpose { STOP, SKIP }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FocusScreen(
    onBack: () -> Unit,
    onOpenRoutine: (Long) -> Unit,
    onCreateRoutine: () -> Unit,
    vm: FocusViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val countdownUntil = if (ui.running) ui.until else ui.activeRoutineUntil
    LaunchedEffect(ui.running, ui.until, ui.activeRoutineUntil) {
        if (countdownUntil <= System.currentTimeMillis()) return@LaunchedEffect
        while (System.currentTimeMillis() < countdownUntil) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        vm.refresh()
    }
    var picking by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    var pinPurpose by remember { mutableStateOf(PinPurpose.STOP) }
    var tileNote by remember { mutableStateOf<Int?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var resetKey by remember { mutableIntStateOf(0) }
    var custom by rememberSaveable { mutableStateOf("") }
    if (picking) {
        AppPickerDialog(
            apps = ui.apps,
            selected = ui.selected,
            onDismiss = { picking = false },
            onToggle = vm::toggle,
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.focus_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (askPin) askPin = false else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (askPin) {
            val wrong = stringResource(R.string.pin_wrong)
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                PinPad(
                    title = stringResource(R.string.pin_prompt),
                    error = pinError,
                    resetKey = resetKey,
                    onDismiss = { askPin = false },
                    onSubmit = { pin ->
                        if (PinGate.matches(context, pin)) {
                            askPin = false
                            if (pinPurpose == PinPurpose.SKIP) vm.skipActive() else vm.stop()
                        } else {
                            pinError = wrong
                            resetKey += 1
                        }
                    },
                )
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
            Text(stringResource(R.string.focus_lead), style = MaterialTheme.typography.bodyLarge)
            if (ui.running) {
                Text(stringResource(R.string.focus_running), style = MaterialTheme.typography.titleMedium)
                Text(
                    formatCountdown((countdownUntil - now).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                val labels = ui.apps.filter { it.packageName in ui.selected }.map { it.label }
                Text(
                    labels.joinToString(" · ").ifBlank { stringResource(R.string.schedule_apps_count, ui.selected.size) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        pinPurpose = PinPurpose.STOP
                        if (PinGate.isEnabled(context)) {
                            pinError = null
                            resetKey += 1
                            askPin = true
                        } else {
                            vm.stop()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.focus_stop_action))
                }
            } else if (ui.activeRoutineId != 0L) {
                Text(stringResource(R.string.focus_routine_running), style = MaterialTheme.typography.titleMedium)
                Text(
                    ui.activeRoutineName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    formatCountdown((countdownUntil - now).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        pinPurpose = PinPurpose.SKIP
                        if (PinGate.isEnabled(context)) {
                            pinError = null
                            resetKey += 1
                            askPin = true
                        } else {
                            vm.skipActive()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.focus_routine_skip))
                }
            } else {
                Text(stringResource(R.string.focus_duration), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FOCUS_PRESETS.forEach { minutes ->
                        FilterChip(
                            selected = ui.minutes == minutes && custom.isBlank(),
                            onClick = {
                                custom = ""
                                vm.setMinutes(minutes)
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
                        digits.toIntOrNull()?.takeIf { it in 1..180 }?.let(vm::setMinutes)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.choice_custom_minutes)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.focus_apps), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ui.selected.isEmpty()) {
                        stringResource(R.string.schedule_apps_none)
                    } else {
                        stringResource(R.string.schedule_apps_count, ui.selected.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.focus_pick))
                }
                if (ui.needApps) {
                    Text(
                        stringResource(R.string.focus_need_apps),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(onClick = vm::start, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.focus_start))
                }
            }
            OutlinedButton(
                onClick = {
                    FocusTileService.requestAdd(context) { result ->
                        tileNote = when (result) {
                            FocusTileService.AddResult.ADDED -> R.string.focus_tile_added
                            FocusTileService.AddResult.DECLINED -> R.string.focus_tile_declined
                            FocusTileService.AddResult.MANUAL -> R.string.focus_tile_manual
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.focus_tile_add))
            }
            tileNote?.let { note ->
                Text(
                    stringResource(note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.focus_routines), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.focus_routines_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!ui.loading && ui.routines.isEmpty()) {
                Text(
                    stringResource(R.string.focus_routines_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.routines.forEach { row ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRoutine(row.id) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${formatMinuteOfDay(row.startMinute, context)} · ${stringResource(R.string.minutes_short, row.durationMinutes)}",
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
                            if (row.activeNow) {
                                Text(
                                    stringResource(R.string.focus_routine_now),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                        Switch(checked = row.enabled, onCheckedChange = { vm.toggleRoutine(row.id) })
                    }
                }
            }
            OutlinedButton(onClick = onCreateRoutine, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.focus_routine_add))
            }
        }
    }
}
