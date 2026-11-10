package app.unscroll.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.unscroll.R
import app.unscroll.permissions.PermissionChecks
import app.unscroll.permissions.openUsageAccessSettings

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    if (step == 0) {
        WelcomeStep(onNext = { step = 1 })
    } else {
        UsageAccessStep(onBack = { step = 0 }, onContinue = onFinished)
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.size(24.dp))
            Image(
                painter = painterResource(R.drawable.ic_mark),
                contentDescription = stringResource(R.string.cd_app_mark),
                modifier = Modifier.size(72.dp),
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.welcome_lead), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.welcome_local),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(48.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_start))
            }
        }
    }
}

@Composable
private fun UsageAccessStep(onBack: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    var hasUsage by remember { mutableStateOf(PermissionChecks.hasUsageAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsage = PermissionChecks.hasUsageAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.step_indicator, 2, 2),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(stringResource(R.string.perm_usage), style = MaterialTheme.typography.headlineSmall)
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val status = if (hasUsage) {
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
                        Text(
                            stringResource(R.string.perm_usage),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        if (hasUsage) stringResource(R.string.perm_granted) else stringResource(R.string.perm_missing),
                        style = MaterialTheme.typography.labelLarge,
                        color = status,
                    )
                    Text(stringResource(R.string.perm_usage_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { context.openUsageAccessSettings() }) {
                        Text(stringResource(if (hasUsage) R.string.manage else R.string.open_settings))
                    }
                }
            }
            Spacer(Modifier.size(24.dp))
            Button(
                onClick = onContinue,
                enabled = hasUsage,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.enter_app))
            }
            if (!hasUsage) {
                Text(
                    stringResource(R.string.usage_required_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.back))
            }
        }
    }
}
