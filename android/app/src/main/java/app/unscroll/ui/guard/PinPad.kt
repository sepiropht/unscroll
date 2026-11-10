package app.unscroll.ui.guard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.unscroll.R

@Composable
fun PinPad(
    title: String,
    error: String?,
    resetKey: Any,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var digits by remember(resetKey) { mutableStateOf("") }
    LaunchedEffect(resetKey) { digits = "" }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            error ?: stringResource(R.string.pin_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            "●".repeat(digits.length).padEnd(6, '○').toCharArray().joinToString(" "),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    OutlinedButton(
                        onClick = { if (digits.length < 6) digits += digit },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(digit, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { digits = digits.dropLast(1) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.pin_backspace))
            }
            OutlinedButton(
                onClick = { if (digits.length < 6) digits += "0" },
                modifier = Modifier.weight(1f),
            ) {
                Text("0", style = MaterialTheme.typography.titleLarge)
            }
            Button(
                onClick = { onSubmit(digits) },
                enabled = digits.length in 4..6,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.pin_ok))
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.cancel))
        }
    }
}
