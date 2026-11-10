package app.unscroll.ui.guard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.unscroll.R
import app.unscroll.data.guard.Weekdays

private const val DAY_CAP_MINUTES = 24 * 60
private val PRESETS = listOf(15, 30, 45, 60, 90, 120, 180)

@Composable
fun DailyLimitEditorDialog(
    title: String,
    currentMillis: Long?,
    weekdaysMask: Int,
    onDismiss: () -> Unit,
    onSave: (Long?, Int) -> Unit,
) {
    val initial = currentMillis?.let { (it / 60_000L).toInt() }?.takeIf { it > 0 }
    var minutes by remember(currentMillis) { mutableStateOf(initial) }
    var mask by remember(weekdaysMask) { mutableIntStateOf(weekdaysMask and Weekdays.ALL) }
    var error by remember { mutableStateOf<String?>(null) }
    val needDay = stringResource(R.string.limit_need_day)
    val tooLong = stringResource(R.string.limit_too_long)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.limits_days), style = MaterialTheme.typography.titleMedium)
                WeekdayRow(mask = mask, onChange = { mask = it and Weekdays.ALL })
                Text(
                    stringResource(R.string.limit_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                MinuteChoices(minutes = minutes, presets = PRESETS, onPick = { minutes = it })
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val chosen = minutes
                when {
                    chosen != null && chosen > DAY_CAP_MINUTES -> error = tooLong
                    chosen != null && !Weekdays.any(mask) -> error = needDay
                    else -> onSave(chosen?.toLong()?.times(60_000L), mask)
                }
            }) {
                Text(stringResource(R.string.limit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
