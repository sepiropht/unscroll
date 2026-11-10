package app.unscroll.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.unscroll.R
import app.unscroll.data.db.Category

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DailyLimitDialog(
    title: String,
    currentMillis: Long?,
    onDismiss: () -> Unit,
    onSave: (Long?) -> Unit,
) {
    val presets = listOf(15, 30, 45, 60, 90, 120)
    var selected by remember(currentMillis) {
        mutableStateOf(presets.firstOrNull { it * 60_000L == currentMillis })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { minutes ->
                    FilterChip(
                        selected = selected == minutes,
                        onClick = { selected = minutes },
                        label = { Text(stringResource(R.string.minutes_short, minutes)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onSave(it * 60_000L) } },
                enabled = selected != null,
            ) {
                Text(stringResource(R.string.limit_save))
            }
        },
        dismissButton = {
            Row {
                if (currentMillis != null) {
                    TextButton(onClick = { onSave(null) }) {
                        Text(stringResource(R.string.limit_remove))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockNowDialog(
    onDismiss: () -> Unit,
    onSave: (BlockSpan) -> Unit,
    onClear: (() -> Unit)?,
) {
    var selected by remember { mutableStateOf(BlockSpan.MINUTES_15) }
    val choices = listOf(
        BlockSpan.MINUTES_15 to R.string.block_15,
        BlockSpan.HOUR to R.string.block_60,
        BlockSpan.UNTIL_TOMORROW to R.string.block_tomorrow,
        BlockSpan.PERMANENT to R.string.block_permanent,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.block_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { (span, label) ->
                    FilterChip(
                        selected = selected == span,
                        onClick = { selected = span },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) {
                Text(stringResource(R.string.block_save))
            }
        },
        dismissButton = {
            Row {
                if (onClear != null) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.block_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
fun CategoryPickerDialog(
    categories: List<Category>,
    selectedKey: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_picker_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                categories.forEach { category ->
                    val selected = category.key == selectedKey
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onPick(category.key) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(category.colorArgb)),
                        )
                        Text(
                            category.label,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
