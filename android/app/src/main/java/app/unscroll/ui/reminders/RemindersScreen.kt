package app.unscroll.ui.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.unscroll.R
import app.unscroll.ui.permissions.rememberJitPermissionGate
import app.unscroll.ui.permissions.requireNotifications
import app.unscroll.permissions.PermissionSnapshot
import app.unscroll.prefs.AppPrefs
import app.unscroll.service.ReminderScheduler
import app.unscroll.ui.guard.HourMinuteField

private val USAGE_CHOICES = intArrayOf(0, 15, 30, 45, 60)
private val EYE_CHOICES = intArrayOf(0, 20, 30, 45, 60)
private const val DEFAULT_SUMMARY_MINUTE = 21 * 60

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val jit = rememberJitPermissionGate()
    var usage by remember { mutableIntStateOf(prefs.usageReminderMinutes) }
    var eye by remember { mutableIntStateOf(prefs.eyeBreakMinutes) }
    var summaryMinute by remember { mutableIntStateOf(prefs.dailySummaryMinute) }
    var snap by remember { mutableIntStateOf(0) }
    val permissions = remember(snap) { PermissionSnapshot.capture(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usage = prefs.usageReminderMinutes
                eye = prefs.eyeBreakMinutes
                summaryMinute = prefs.dailySummaryMinute
                snap += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reminders_title)) },
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
            Text(stringResource(R.string.reminders_lead), style = MaterialTheme.typography.bodyLarge)
            if (!permissions.notifications) {
                Notice(stringResource(R.string.reminders_notif_missing), onOpenPermissions)
            }
            if (!permissions.accessibility) {
                Notice(stringResource(R.string.reminders_a11y_missing), onOpenPermissions)
            }
            if (!permissions.usage) {
                Notice(stringResource(R.string.reminders_usage_missing), onOpenPermissions)
            }
            ReminderCard(title = stringResource(R.string.reminders_usage_title), body = stringResource(R.string.reminders_usage_body)) {
                MinuteChips(selected = usage, choices = USAGE_CHOICES) { value ->
                    usage = value
                    prefs.usageReminderMinutes = value
                }
            }
            ReminderCard(title = stringResource(R.string.reminders_eye_title), body = stringResource(R.string.reminders_eye_body)) {
                MinuteChips(selected = eye, choices = EYE_CHOICES) { value ->
                    eye = value
                    prefs.eyeBreakMinutes = value
                }
            }
            ReminderCard(
                title = stringResource(R.string.reminders_summary_title),
                body = stringResource(R.string.reminders_summary_body),
                trailing = {
                    Switch(
                        checked = summaryMinute >= 0,
                        onCheckedChange = { enabled ->
                            fun apply() {
                                val next = if (enabled) {
                                    if (summaryMinute >= 0) summaryMinute else DEFAULT_SUMMARY_MINUTE
                                } else {
                                    -1
                                }
                                summaryMinute = next
                                prefs.dailySummaryMinute = next
                                ReminderScheduler.schedule(context)
                            }
                            if (enabled) jit.requireNotifications { apply() } else apply()
                        },
                    )
                },
            ) {
                if (summaryMinute >= 0) {
                    HourMinuteField(
                        label = stringResource(R.string.reminders_summary_time),
                        minuteOfDay = summaryMinute,
                        onChange = { minute ->
                            summaryMinute = minute
                            prefs.dailySummaryMinute = minute
                            ReminderScheduler.schedule(context)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.limits_a11y_open),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ReminderCard(
    title: String,
    body: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                trailing?.invoke()
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MinuteChips(selected: Int, choices: IntArray, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onPick(value) },
                label = {
                    Text(
                        if (value == 0) {
                            stringResource(R.string.choice_off)
                        } else {
                            stringResource(R.string.minutes_short, value)
                        },
                    )
                },
            )
        }
    }
}
