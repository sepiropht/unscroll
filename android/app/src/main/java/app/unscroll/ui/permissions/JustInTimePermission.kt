package app.unscroll.ui.permissions

import android.Manifest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.unscroll.R
import app.unscroll.permissions.PermissionChecks
import app.unscroll.permissions.openAccessibilitySettings
import app.unscroll.permissions.openBatterySettings
import app.unscroll.permissions.openOverlaySettings

enum class JitPermission {
    Overlay,
    Accessibility,
    Notifications,
    Battery,
}

@Composable
fun rememberJitPermissionGate(): JitPermissionGate {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pendingState = remember { mutableStateOf<Pair<JitPermission, () -> Unit>?>(null) }
    var pending by pendingState
    val resumeCont = remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val cont = pendingState.value?.second
        pendingState.value = null
        cont?.invoke()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val cont = resumeCont.value
                if (cont != null) {
                    resumeCont.value = null
                    cont()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val dialogPending = pendingState.value
    if (dialogPending != null) {
        val (kind, _) = dialogPending
        if (kind == JitPermission.Accessibility) {
            AccessibilityDisclosure(
                onDismiss = {
                    val cont = dialogPending.second
                    pending = null
                    Handler(Looper.getMainLooper()).post { cont() }
                },
                onConfirm = {
                    val cont = dialogPending.second
                    pending = null
                    resumeCont.value = cont
                    context.openAccessibilitySettings()
                },
            )
        } else {
        val title = when (kind) {
            JitPermission.Overlay -> stringResource(R.string.perm_overlay)
            JitPermission.Accessibility -> stringResource(R.string.perm_a11y)
            JitPermission.Notifications -> stringResource(R.string.perm_notif)
            JitPermission.Battery -> stringResource(R.string.perm_battery)
        }
        val body = when (kind) {
            JitPermission.Overlay -> stringResource(R.string.perm_overlay_body)
            JitPermission.Accessibility -> stringResource(R.string.perm_a11y_body)
            JitPermission.Notifications -> stringResource(R.string.perm_notif_body)
            JitPermission.Battery -> stringResource(R.string.perm_battery_body)
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cont = dialogPending.second
                        when (kind) {
                            JitPermission.Overlay -> {
                                pending = null
                                resumeCont.value = cont
                                context.openOverlaySettings()
                            }
                            JitPermission.Accessibility -> {
                                pending = null
                                resumeCont.value = cont
                                context.openAccessibilitySettings()
                            }
                            JitPermission.Notifications -> {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    pending = null
                                    Handler(Looper.getMainLooper()).post { cont() }
                                }
                            }
                            JitPermission.Battery -> {
                                pending = null
                                resumeCont.value = cont
                                context.openBatterySettings()
                            }
                        }
                    },
                ) { Text(stringResource(R.string.perm_jit_allow)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val cont = dialogPending.second
                        pending = null
                        Handler(Looper.getMainLooper()).post { cont() }
                    },
                ) { Text(stringResource(R.string.perm_jit_not_now)) }
            },
        )
        }
    }
    return remember(context) {
        JitPermissionGate { kind, alreadyOk, continueAnyway ->
            if (alreadyOk(context)) {
                continueAnyway()
            } else {
                Handler(Looper.getMainLooper()).post {
                    pendingState.value = kind to continueAnyway
                }
            }
        }
    }
}

fun interface JitPermissionGate {
    fun run(kind: JitPermission, alreadyOk: (android.content.Context) -> Boolean, continueAnyway: () -> Unit)
}

fun JitPermissionGate.requireOverlay(continueAnyway: () -> Unit) =
    run(JitPermission.Overlay, { PermissionChecks.canOverlay(it) }, continueAnyway)

fun JitPermissionGate.requireNotifications(continueAnyway: () -> Unit) =
    run(JitPermission.Notifications, { PermissionChecks.hasNotifications(it) }, continueAnyway)

fun JitPermissionGate.requireBattery(continueAnyway: () -> Unit) =
    run(JitPermission.Battery, { PermissionChecks.ignoresBatteryOptimizations(it) }, continueAnyway)

fun JitPermissionGate.requireAccessibility(continueAnyway: () -> Unit) =
    run(JitPermission.Accessibility, { PermissionChecks.isAccessibilityEnabled(it) }, continueAnyway)
