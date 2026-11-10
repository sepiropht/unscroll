package app.unscroll.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.prefs.AppPrefs
import app.unscroll.prefs.EditLock
import app.unscroll.prefs.PinGate
import app.unscroll.ui.guard.PinPad
import kotlinx.coroutines.launch

private enum class PinStep { Create, Confirm, CurrentChange, CurrentRemove }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenLimits: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenWebsites: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenExclusions: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    onWiped: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.writeBackup(uri) else EditLock.releaseDocument()
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.stageRestore(uri) else EditLock.releaseDocument()
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.writeCsv(uri) else EditLock.releaseDocument()
    }
    SettingsGate(onBack = onBack) {
        SettingsBody(
            vm = vm,
            onBack = onBack,
            onOpenPermissions = onOpenPermissions,
            onOpenLimits = onOpenLimits,
            onOpenSchedules = onOpenSchedules,
            onOpenSleep = onOpenSleep,
            onOpenFocus = onOpenFocus,
            onOpenWebsites = onOpenWebsites,
            onOpenReminders = onOpenReminders,
            onOpenAchievements = onOpenAchievements,
            onOpenExclusions = onOpenExclusions,
            onOpenSync = onOpenSync,
            onOpenPrivacy = onOpenPrivacy,
            onOpenAbout = onOpenAbout,
            onWiped = onWiped,
            onExportJson = {
                vm.holdDocument()
                exportJson.launch("unscroll-backup.json")
            },
            onImportJson = {
                vm.holdDocument()
                importJson.launch(arrayOf("application/json", "text/*", "*/*"))
            },
            onExportCsv = {
                vm.holdDocument()
                exportCsv.launch("unscroll-history.csv")
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsBody(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenLimits: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenWebsites: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenExclusions: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    onWiped: () -> Unit,
    onExportJson: () -> Unit,
    onImportJson: () -> Unit,
    onExportCsv: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1.0"
    }
    var pinStep by remember { mutableStateOf<PinStep?>(null) }
    var pinFirst by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pinReset by remember { mutableIntStateOf(0) }
    val restore = ui.restore
    if (restore != null) {
        AlertDialog(
            onDismissRequest = { if (!ui.busy) vm.dismissRestore() },
            title = { Text(stringResource(R.string.settings_restore_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_restore_body,
                        restore.limits,
                        restore.schedules,
                        restore.categories,
                        restore.routines,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmRestore, enabled = !ui.busy) {
                    Text(stringResource(R.string.settings_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissRestore, enabled = !ui.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (ui.confirmReset) {
        AlertDialog(
            onDismissRequest = { if (!ui.busy) vm.dismissReset() },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = {
                TextButton(onClick = { vm.confirmReset(onWiped) }, enabled = !ui.busy) {
                    Text(stringResource(R.string.settings_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissReset, enabled = !ui.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (pinStep != null) pinStep = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (pinStep != null) {
            val wrong = stringResource(R.string.pin_wrong)
            val mismatch = stringResource(R.string.pin_mismatch)
            val title = when (pinStep) {
                PinStep.Confirm -> stringResource(R.string.pin_confirm)
                PinStep.CurrentChange, PinStep.CurrentRemove -> stringResource(R.string.pin_current)
                else -> stringResource(R.string.pin_create)
            }
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                PinPad(
                    title = title,
                    error = pinError,
                    resetKey = pinReset,
                    onDismiss = { pinStep = null },
                    onSubmit = { pin ->
                        when (pinStep) {
                            PinStep.CurrentChange, PinStep.CurrentRemove -> scope.launch {
                                if (PinGate.check(context, pin)) {
                                    if (pinStep == PinStep.CurrentRemove) {
                                        pinStep = null
                                        vm.clearPin()
                                    } else {
                                        pinStep = PinStep.Create
                                        pinFirst = ""
                                        pinError = null
                                        pinReset += 1
                                    }
                                } else {
                                    pinError = wrong
                                    pinReset += 1
                                }
                            }
                            PinStep.Create -> {
                                pinFirst = pin
                                pinStep = PinStep.Confirm
                                pinError = null
                                pinReset += 1
                            }
                            PinStep.Confirm -> if (pin == pinFirst) {
                                pinStep = null
                                vm.setPin(pin)
                            } else {
                                pinStep = PinStep.Create
                                pinFirst = ""
                                pinError = mismatch
                                pinReset += 1
                            }
                            null -> Unit
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
            Text(
                stringResource(R.string.settings_local),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.notice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.noticeError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            if (ui.busy) {
                Text(stringResource(R.string.settings_busy), style = MaterialTheme.typography.labelLarge)
            }
            SettingsLink(
                title = stringResource(R.string.settings_sync),
                body = stringResource(if (ui.syncEnabled) R.string.settings_sync_on else R.string.settings_sync_off),
                onClick = onOpenSync,
            )
            SectionCard(stringResource(R.string.settings_section_look)) {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(R.string.settings_theme_system, ui.themeMode == AppPrefs.THEME_SYSTEM) { vm.setTheme(AppPrefs.THEME_SYSTEM) }
                    ThemeChip(R.string.settings_theme_light, ui.themeMode == AppPrefs.THEME_LIGHT) { vm.setTheme(AppPrefs.THEME_LIGHT) }
                    ThemeChip(R.string.settings_theme_dark, ui.themeMode == AppPrefs.THEME_DARK) { vm.setTheme(AppPrefs.THEME_DARK) }
                }
                SwitchLine(
                    title = stringResource(R.string.settings_dynamic),
                    body = stringResource(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.settings_dynamic_sub else R.string.settings_dynamic_legacy,
                    ),
                    checked = ui.dynamicColor,
                    onChecked = vm::setDynamic,
                )
            }
            SectionCard(stringResource(R.string.settings_section_calendar)) {
                Text(stringResource(R.string.settings_week), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(R.string.settings_week_monday, ui.weekMonday) { vm.setWeekMonday(true) }
                    ThemeChip(R.string.settings_week_sunday, !ui.weekMonday) { vm.setWeekMonday(false) }
                }
                Text(stringResource(R.string.settings_hour), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(R.string.settings_hour_system, ui.hourFormat == AppPrefs.HOUR_SYSTEM) { vm.setHourFormat(AppPrefs.HOUR_SYSTEM) }
                    ThemeChip(R.string.settings_hour_24, ui.hourFormat == AppPrefs.HOUR_24) { vm.setHourFormat(AppPrefs.HOUR_24) }
                    ThemeChip(R.string.settings_hour_12, ui.hourFormat == AppPrefs.HOUR_12) { vm.setHourFormat(AppPrefs.HOUR_12) }
                }
            }
            SectionCard(stringResource(R.string.settings_section_stats)) {
                SwitchLine(
                    title = stringResource(R.string.settings_system_apps),
                    body = stringResource(R.string.settings_system_apps_sub),
                    checked = ui.showSystemApps,
                    onChecked = vm::setShowSystem,
                )
                SettingsLink(
                    title = stringResource(R.string.settings_exclusions),
                    body = pluralStringResource(R.plurals.settings_exclusions_sub, ui.excludedCount, ui.excludedCount),
                    onClick = onOpenExclusions,
                )
            }
            SectionCard(stringResource(R.string.settings_section_pin)) {
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
                    OutlinedButton(
                        onClick = {
                            pinError = null
                            pinReset += 1
                            pinStep = PinStep.CurrentChange
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.limits_pin_change)) }
                    TextButton(
                        onClick = {
                            pinError = null
                            pinReset += 1
                            pinStep = PinStep.CurrentRemove
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.limits_pin_remove)) }
                } else {
                    OutlinedButton(
                        onClick = {
                            pinError = null
                            pinFirst = ""
                            pinReset += 1
                            pinStep = PinStep.Create
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.limits_pin_set)) }
                }
            }
            SectionCard(stringResource(R.string.settings_section_data)) {
                Text(
                    stringResource(R.string.settings_backup_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onExportJson, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_backup))
                }
                OutlinedButton(onClick = onImportJson, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_restore))
                }
                Text(
                    stringResource(R.string.settings_csv_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onExportCsv, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_csv))
                }
                TextButton(onClick = vm::askReset, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_reset))
                }
            }
            Text(stringResource(R.string.settings_section_screens), style = MaterialTheme.typography.titleMedium)
            SettingsLink(stringResource(R.string.permissions_title), stringResource(R.string.settings_permissions_sub), onOpenPermissions)
            SettingsLink(stringResource(R.string.settings_schedules), stringResource(R.string.settings_schedules_sub), onOpenSchedules)
            SettingsLink(stringResource(R.string.sleep_title), stringResource(R.string.settings_sleep_sub), onOpenSleep)
            SettingsLink(stringResource(R.string.settings_websites), stringResource(R.string.settings_websites_sub), onOpenWebsites)
            SettingsLink(stringResource(R.string.settings_reminders), stringResource(R.string.settings_reminders_sub), onOpenReminders)
            SettingsLink(stringResource(R.string.settings_achievements), stringResource(R.string.settings_achievements_sub), onOpenAchievements)
            SettingsLink(stringResource(R.string.privacy_title), stringResource(R.string.settings_privacy_sub), onOpenPrivacy)
            SettingsLink(stringResource(R.string.about_title), stringResource(R.string.settings_about_sub), onOpenAbout)
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.version_label, version),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeChip(label: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(label)) })
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchLine(title: String, body: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body.isNotBlank()) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun SettingsLink(title: String, body: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body.isNotBlank()) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
