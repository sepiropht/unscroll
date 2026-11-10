package app.unscroll.ui.guard

import app.unscroll.ui.format.formatMinute as formatMinuteFq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.unscroll.R
import app.unscroll.data.guard.AppChoice
import app.unscroll.data.guard.Domains
import app.unscroll.data.guard.Weekdays
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WeekdayRow(mask: Int, onChange: (Int) -> Unit) {
    val locale = Locale.getDefault()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = Weekdays.contains(mask, day),
                onClick = { onChange(Weekdays.toggle(mask, day)) },
                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MinuteChoices(
    minutes: Int?,
    presets: List<Int>,
    onPick: (Int?) -> Unit,
) {
    var custom by rememberSaveable(minutes) { mutableStateOf(if (minutes != null && minutes !in presets && minutes > 0) minutes.toString() else "") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = minutes == null || minutes == 0,
            onClick = {
                custom = ""
                onPick(null)
            },
            label = { Text(stringResource(R.string.choice_off)) },
        )
        presets.forEach { value ->
            FilterChip(
                selected = minutes == value && custom.isBlank(),
                onClick = {
                    custom = ""
                    onPick(value)
                },
                label = { Text(stringResource(R.string.minutes_short, value)) },
            )
        }
    }
    OutlinedTextField(
        value = custom,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(4)
            custom = digits
            onPick(digits.toIntOrNull()?.takeIf { it > 0 })
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        label = { Text(stringResource(R.string.choice_custom_minutes)) },
        singleLine = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CountChoices(count: Int?, presets: List<Int>, onPick: (Int?) -> Unit) {
    var custom by rememberSaveable(count) { mutableStateOf(if (count != null && count !in presets && count > 0) count.toString() else "") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = count == null || count == 0,
            onClick = {
                custom = ""
                onPick(null)
            },
            label = { Text(stringResource(R.string.choice_off)) },
        )
        presets.forEach { value ->
            FilterChip(
                selected = count == value && custom.isBlank(),
                onClick = {
                    custom = ""
                    onPick(value)
                },
                label = { Text(value.toString()) },
            )
        }
    }
    OutlinedTextField(
        value = custom,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(3)
            custom = digits
            onPick(digits.toIntOrNull()?.takeIf { it > 0 })
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        label = { Text(stringResource(R.string.choice_custom_count)) },
        singleLine = true,
    )
}

@Composable
fun HourMinuteField(label: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
    var hour by rememberSaveable(minuteOfDay) { mutableStateOf((minuteOfDay / 60).toString()) }
    var minute by rememberSaveable(minuteOfDay) { mutableStateOf((minuteOfDay % 60).toString().padStart(2, '0')) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hour,
                onValueChange = { raw ->
                    hour = raw.filter { it.isDigit() }.take(2)
                    emit(hour, minute, onChange)
                },
                label = { Text(stringResource(R.string.field_hour)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = minute,
                onValueChange = { raw ->
                    minute = raw.filter { it.isDigit() }.take(2)
                    emit(hour, minute, onChange)
                },
                label = { Text(stringResource(R.string.field_minute)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun emit(hour: String, minute: String, onChange: (Int) -> Unit) {
    val h = hour.toIntOrNull()?.coerceIn(0, 23) ?: return
    val m = minute.toIntOrNull()?.coerceIn(0, 59) ?: return
    onChange(h * 60 + m)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DomainEditor(domains: Set<String>, onChange: (Set<String>) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.domain_add)) },
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = {
                    val domain = Domains.normalize(draft) ?: return@TextButton
                    onChange(domains + domain)
                    draft = ""
                }) { Text(stringResource(R.string.domain_add_action)) }
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            domains.sorted().forEach { domain ->
                FilterChip(
                    selected = true,
                    onClick = { onChange(domains - domain) },
                    label = { Text(domain) },
                )
            }
        }
    }
}

@Composable
fun AppPickerDialog(
    apps: List<AppChoice>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = apps.filter { choice ->
        query.isBlank() || choice.label.contains(query, ignoreCase = true) || choice.packageName.contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_apps)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.pick_search)) },
                    singleLine = true,
                )
                LazyColumn(Modifier.height(360.dp)) {
                    items(shown, key = { it.packageName }) { choice ->
                        val on = choice.packageName in selected
                        TextButton(onClick = { onToggle(choice.packageName) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (on) "✓  ${choice.label}" else choice.label,
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pick_done)) }
        },
    )
}

fun formatMinuteOfDay(minute: Int, context: android.content.Context): String {
    return formatMinuteFq(minute, context)
}

@Composable
fun weekdayCaption(mask: Int): String {
    if (Weekdays.coversAll(mask)) return stringResource(R.string.limits_all_days)
    val locale = Locale.getDefault()
    return DayOfWeek.entries
        .filter { Weekdays.contains(mask, it) }
        .joinToString(" · ") { it.getDisplayName(TextStyle.SHORT, locale) }
        .ifBlank { stringResource(R.string.limit_need_day) }
}
