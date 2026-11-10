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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.data.guard.Weekdays
import app.unscroll.prefs.PinGate
import app.unscroll.ui.format.formatCountdown
import app.unscroll.ui.format.formatDuration
import app.unscroll.ui.guard.AppPickerDialog
import app.unscroll.ui.guard.DailyLimitEditorDialog
import app.unscroll.ui.guard.EditGuardDialog
import app.unscroll.ui.guard.PinPad
import app.unscroll.ui.guard.rememberEditGuard
import app.unscroll.ui.guard.weekdayCaption
import app.unscroll.ui.permissions.rememberJitPermissionGate
import app.unscroll.ui.permissions.requireNotifications
import app.unscroll.ui.permissions.requireOverlay
import kotlinx.coroutines.delay

private enum class PinAsk { Pause, Resume, Create, ChangeCurrent, Remove }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitsScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenWebsites: () -> Unit,
    vm: LimitsViewModel = viewModel(),
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
    LaunchedEffect(ui.pauseUntil) {
        if (ui.pauseUntil <= System.currentTimeMillis()) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now >= ui.pauseUntil) {
                vm.refresh()
                break
            }
            delay(1_000)
        }
    }
    val guard = rememberEditGuard()
    EditGuardDialog(guard)
    val jit = rememberJitPermissionGate()
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<PinAsk?>(null) }
    var createStep by remember { mutableIntStateOf(1) }
    var createFirst by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var resetKey by remember { mutableIntStateOf(0) }

    if (picking) {
        AppPickerDialog(
            apps = ui.apps,
            selected = emptySet(),
            onDismiss = { picking = false },
            onToggle = { pkg ->
                editing = pkg
                picking = false
            },
        )
    }
    val editingPkg = editing
    if (editingPkg != null) {
        val row = ui.rows.find { it.packageName == editingPkg }
        val label = row?.label ?: ui.apps.find { it.packageName == editingPkg }?.label ?: editingPkg
        DailyLimitEditorDialog(
            title = label,
            currentMillis = row?.dailyMillis,
            weekdaysMask = row?.weekdaysMask ?: Weekdays.ALL,
            onDismiss = { editing = null },
            onSave = { millis, mask ->
                editing = null
                guard.run {
                    jit.requireOverlay {
                        jit.requireNotifications {
                            vm.saveDaily(editingPkg, millis, mask)
                        }
                    }
                }
            },
        )
    }

    fun openPin(next: PinAsk) {
        pinError = null
        createStep = 1
        createFirst = ""
        resetKey += 1
        ask = next
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.limits_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (ask != null) ask = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (ask != null) {
            val wrong = stringResource(R.string.pin_wrong)
            val mismatch = stringResource(R.string.pin_mismatch)
            val title = when (ask) {
                PinAsk.Create -> if (createStep == 1) {
                    stringResource(R.string.pin_create)
                } else {
                    stringResource(R.string.pin_confirm)
                }
                PinAsk.ChangeCurrent, PinAsk.Remove -> stringResource(R.string.pin_current)
                else -> stringResource(R.string.pin_prompt)
            }
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                PinPad(
                    title = title,
                    error = pinError,
                    resetKey = resetKey,
                    onDismiss = { ask = null },
                    onSubmit = { pin ->
                        when (ask) {
                            PinAsk.Pause -> if (PinGate.matches(context, pin)) {
                                ask = null
                                vm.pauseFive()
                            } else {
                                pinError = wrong
                                resetKey += 1
                            }
                            PinAsk.Resume -> if (PinGate.matches(context, pin)) {
                                ask = null
                                vm.resume()
                            } else {
                                pinError = wrong
                                resetKey += 1
                            }
                            PinAsk.ChangeCurrent -> if (PinGate.matches(context, pin)) {
                                pinError = null
                                createStep = 1
                                createFirst = ""
                                resetKey += 1
                                ask = PinAsk.Create
                            } else {
                                pinError = wrong
                                resetKey += 1
                            }
                            PinAsk.Remove -> if (PinGate.matches(context, pin)) {
                                ask = null
                                vm.clearPin()
                            } else {
                                pinError = wrong
                                resetKey += 1
                            }
                            PinAsk.Create -> if (createStep == 1) {
                                createFirst = pin
                                createStep = 2
                                pinError = null
                                resetKey += 1
                            } else if (pin == createFirst) {
                                ask = null
                                vm.setPin(pin)
                            } else {
                                createStep = 1
                                createFirst = ""
                                pinError = mismatch
                                resetKey += 1
                            }
                            null -> Unit
                        }
                    },
                )
            }
        } else {
            LimitsBody(
                ui = ui,
                now = now,
                onOpenPermissions = onOpenPermissions,
                onOpenSchedules = onOpenSchedules,
                onOpenSleep = onOpenSleep,
                onOpenFocus = onOpenFocus,
                onOpenWebsites = onOpenWebsites,
                onPause = {
                    if (PinGate.isEnabled(context)) openPin(PinAsk.Pause) else vm.pauseFive()
                },
                onResume = {
                    if (PinGate.isEnabled(context)) openPin(PinAsk.Resume) else vm.resume()
                },
                onExtra = { enabled -> guard.run { vm.setExtra(enabled) } },
                onCreatePin = { openPin(PinAsk.Create) },
                onChangePin = { openPin(PinAsk.ChangeCurrent) },
                onRemovePin = { openPin(PinAsk.Remove) },
                onAdd = { picking = true },
                onEdit = { editing = it },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun LimitsBody(
    ui: LimitsUiState,
    now: Long,
    onOpenPermissions: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenWebsites: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExtra: (Boolean) -> Unit,
    onCreatePin: () -> Unit,
    onChangePin: () -> Unit,
    onRemovePin: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paused = ui.pauseUntil > now
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.limits_lead), style = MaterialTheme.typography.bodyLarge)
        if (!ui.overlay) {
            LinkCard(
                title = stringResource(R.string.limits_overlay_missing),
                body = stringResource(R.string.limits_overlay_open),
                onClick = onOpenPermissions,
            )
        }
        if (!ui.notifications) {
            LinkCard(
                title = stringResource(R.string.limits_notif_missing),
                body = stringResource(R.string.limits_a11y_open),
                onClick = onOpenPermissions,
            )
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.limits_pause_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.limits_pause_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (paused) {
                    Text(
                        formatCountdown(ui.pauseUntil - now),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.limits_pause_active), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.limits_pause_resume))
                    }
                } else {
                    OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.limits_pause_action))
                    }
                }
            }
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.limits_extra_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.limits_extra_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.extraEnabled, onCheckedChange = onExtra)
            }
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.limits_pin_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (ui.pinOn) R.string.limits_pin_on else R.string.limits_pin_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.limits_pin_storage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.limits_pin_forgot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.pinOn) {
                    OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.limits_pin_change))
                    }
                    TextButton(onClick = onRemovePin, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.limits_pin_remove))
                    }
                } else {
                    OutlinedButton(onClick = onCreatePin, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.limits_pin_set))
                    }
                }
            }
        }
        LinkCard(
            title = stringResource(R.string.limits_schedules),
            body = stringResource(R.string.settings_schedules_sub),
            onClick = onOpenSchedules,
        )
        LinkCard(
            title = stringResource(R.string.sleep_title),
            body = stringResource(R.string.settings_sleep_sub),
            onClick = onOpenSleep,
        )
        LinkCard(
            title = stringResource(R.string.limits_focus),
            body = stringResource(R.string.settings_focus_sub),
            onClick = onOpenFocus,
        )
        LinkCard(
            title = stringResource(R.string.settings_websites),
            body = stringResource(R.string.settings_websites_sub),
            onClick = onOpenWebsites,
        )
        Text(stringResource(R.string.limits_daily_heading), style = MaterialTheme.typography.titleMedium)
        if (ui.rows.isEmpty() && !ui.loading) {
            Text(
                stringResource(R.string.limits_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.rows.forEach { row ->
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(row.packageName) },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.limit_current, formatDuration(row.dailyMillis)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        weekdayCaption(row.weekdaysMask),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.limits_add))
        }
    }
}

@Composable
private fun LinkCard(title: String, body: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
