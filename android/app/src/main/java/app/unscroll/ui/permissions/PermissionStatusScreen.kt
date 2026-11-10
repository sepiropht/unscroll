package app.unscroll.ui.permissions

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.unscroll.R
import app.unscroll.permissions.PermissionSnapshot
import app.unscroll.permissions.openAccessibilitySettings
import app.unscroll.permissions.openBatterySettings
import app.unscroll.permissions.openNotificationListenerSettings
import app.unscroll.permissions.openOverlaySettings
import app.unscroll.permissions.openUsageAccessSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionStatusScreen(
    onBack: (() -> Unit)?,
    onContinue: (() -> Unit)? = null,
    stepLabel: String? = null,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(PermissionSnapshot.capture(context)) }
    var showDisclosure by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                snapshot = PermissionSnapshot.capture(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        snapshot = PermissionSnapshot.capture(context)
    }

    if (showDisclosure) {
        AccessibilityDisclosure(
            onDismiss = { showDisclosure = false },
            onConfirm = {
                showDisclosure = false
                context.openAccessibilitySettings()
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.permissions_title),
                            modifier = Modifier.semantics { heading() },
                        )
                        if (stepLabel != null) {
                            Text(stepLabel, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (onContinue != null) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(
                        onClick = onContinue,
                        enabled = snapshot.usage,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.enter_app))
                    }
                    if (!snapshot.usage) {
                        Text(
                            stringResource(R.string.usage_required_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PermissionCard(
                title = stringResource(R.string.perm_usage),
                body = stringResource(R.string.perm_usage_body),
                badge = stringResource(R.string.badge_required),
                granted = snapshot.usage,
                action = stringResource(if (snapshot.usage) R.string.manage else R.string.open_settings),
                onAction = { context.openUsageAccessSettings() },
            )
            PermissionCard(
                title = stringResource(R.string.perm_a11y),
                body = stringResource(R.string.perm_a11y_body),
                badge = stringResource(R.string.badge_recommended),
                granted = snapshot.accessibility,
                action = stringResource(if (snapshot.accessibility) R.string.manage else R.string.open_settings),
                onAction = {
                    if (snapshot.accessibility) context.openAccessibilitySettings()
                    else showDisclosure = true
                },
            )
            PermissionCard(
                title = stringResource(R.string.perm_overlay),
                body = stringResource(R.string.perm_overlay_body),
                badge = stringResource(R.string.badge_recommended),
                granted = snapshot.overlay,
                action = stringResource(if (snapshot.overlay) R.string.manage else R.string.open_settings),
                onAction = { context.openOverlaySettings() },
            )
            PermissionCard(
                title = stringResource(R.string.perm_notif),
                body = if (snapshot.notificationsNeedRequest) {
                    stringResource(R.string.perm_notif_body)
                } else {
                    stringResource(R.string.perm_notif_legacy)
                },
                badge = stringResource(R.string.badge_recommended),
                granted = snapshot.notifications,
                action = if (snapshot.notificationsNeedRequest && !snapshot.notifications) {
                    stringResource(R.string.allow)
                } else {
                    null
                },
                onAction = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            PermissionCard(
                title = stringResource(R.string.perm_listener),
                body = stringResource(R.string.perm_listener_body),
                badge = stringResource(R.string.badge_optional),
                granted = snapshot.notificationListener,
                action = stringResource(if (snapshot.notificationListener) R.string.manage else R.string.open_settings),
                onAction = { context.openNotificationListenerSettings() },
            )
            PermissionCard(
                title = stringResource(R.string.perm_battery),
                body = stringResource(R.string.perm_battery_body),
                badge = stringResource(R.string.badge_optional),
                granted = snapshot.batteryIgnored,
                action = stringResource(if (snapshot.batteryIgnored) R.string.manage else R.string.open_settings),
                onAction = { context.openBatterySettings() },
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    badge: String,
    granted: Boolean,
    action: String?,
    onAction: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val status = if (granted) {
            if (dark) Color(0xFF9BE7B8) else Color(0xFF0B5C2E)
        } else {
            if (dark) Color(0xFFFFB4AB) else Color(0xFFB3261E)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(12.dp).clearAndSetSemantics { }) {
                    drawCircle(status)
                }
                Spacer(Modifier.size(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(
                if (granted) stringResource(R.string.perm_granted) else stringResource(R.string.perm_missing),
                style = MaterialTheme.typography.labelLarge,
                color = status,
            )
            Text(badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                OutlinedButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun AccessibilityDisclosure(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var accepted by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.accessibility_disclosure_body))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(top = 8.dp)
                        .toggleable(
                            value = accepted,
                            role = Role.Checkbox,
                            onValueChange = { accepted = it },
                        ),
                ) {
                    Checkbox(
                        checked = accepted,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Text(
                        stringResource(R.string.accessibility_consent),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = accepted) {
                Text(stringResource(R.string.open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
