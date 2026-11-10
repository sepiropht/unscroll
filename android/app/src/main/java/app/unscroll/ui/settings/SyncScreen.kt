package app.unscroll.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.sync.SyncRules

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    pendingLink: MutableState<String?>? = null,
    onLinkConsumed: () -> Unit = {},
    vm: PrivacyViewModel = viewModel(),
) {
    val incoming = pendingLink?.value
    LaunchedEffect(incoming) {
        if (incoming.isNullOrEmpty()) return@LaunchedEffect
        vm.applyPairing(incoming)
        onLinkConsumed()
    }
    SettingsGate(onBack = onBack) {
        val ui by vm.ui.collectAsStateWithLifecycle()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.sync_title)) },
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
                if (ui.billing.enabled) BillingCard(ui, vm)
                SyncCard(ui, vm)
            }
        }
    }
}

@Composable
private fun BillingCard(ui: PrivacyUi, vm: PrivacyViewModel) {
    val plan = ui.billing
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.sync_bill_title), style = MaterialTheme.typography.titleMedium)
            val state = when (plan.state) {
                "trial" -> stringResource(R.string.sync_bill_trial, plan.daysLeft)
                "active" -> stringResource(if (plan.cancelAtPeriodEnd) R.string.sync_bill_canceling else R.string.sync_bill_active)
                "past_due" -> stringResource(R.string.sync_bill_past_due)
                "ended" -> stringResource(R.string.sync_bill_ended)
                else -> stringResource(R.string.sync_bill_free)
            }
            Text(
                state,
                style = MaterialTheme.typography.bodyLarge,
                color = if (plan.state == "ended" || plan.state == "past_due") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (plan.state == "ended") {
                Text(
                    stringResource(R.string.sync_bill_kept),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (plan.state == "trial" || plan.state == "ended") {
                Button(onClick = { vm.subscribe(yearly = false) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sync_bill_monthly))
                }
                OutlinedButton(onClick = { vm.subscribe(yearly = true) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sync_bill_yearly))
                }
            }
            if (plan.canManage) {
                OutlinedButton(onClick = vm::manageSubscription, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sync_bill_manage))
                }
            }
        }
    }
}

@Composable
private fun SyncCard(ui: PrivacyUi, vm: PrivacyViewModel) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.privacy_sync_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.privacy_sync_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.privacy_sync_enable), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.privacy_sync_enable_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.enabled, onCheckedChange = vm::setEnabled, enabled = !ui.busy)
            }
            OutlinedTextField(
                value = ui.url,
                onValueChange = vm::setUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.privacy_sync_url)) },
                placeholder = { Text(SyncRules.DEFAULT_SYNC_URL) },
                singleLine = true,
                enabled = !ui.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            TextButton(onClick = { advanced = !advanced }, enabled = !ui.busy) {
                Text(stringResource(R.string.privacy_sync_advanced))
            }
            if (advanced) {
                Text(
                    stringResource(R.string.privacy_sync_advanced_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ui.token,
                    onValueChange = vm::setToken,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.privacy_sync_token)) },
                    supportingText = if (ui.tokenKept) {
                        { Text(stringResource(R.string.privacy_sync_token_kept)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    enabled = !ui.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = ui.passphrase,
                    onValueChange = vm::setPassphrase,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.privacy_sync_phrase)) },
                    supportingText = if (ui.secretKept) {
                        { Text(stringResource(R.string.privacy_sync_secret_kept)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    enabled = !ui.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            Text(
                stringResource(R.string.privacy_sync_http_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = vm::save, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.privacy_sync_save))
            }
            Text(
                stringResource(R.string.privacy_sync_stored),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = vm::syncNow, enabled = !ui.busy && ui.canTransfer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.privacy_sync_now))
            }
            SyncPairingSection(
                busy = ui.busy,
                showPairing = ui.showPairing,
                link = ui.pairingLink,
                paste = ui.paste,
                onToggle = vm::togglePairing,
                onPasteChange = vm::setPaste,
                onPaste = vm::applyPasted,
                onCopied = { vm.report(R.string.privacy_sync_pair_copied, false) },
                onCopyFailed = { vm.report(R.string.privacy_sync_pair_copy_fail, true) },
            )
            if (ui.busy) {
                Text(stringResource(R.string.settings_busy), style = MaterialTheme.typography.labelLarge)
            }
            ui.notice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.noticeError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
