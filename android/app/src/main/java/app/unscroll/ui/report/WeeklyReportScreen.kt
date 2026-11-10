package app.unscroll.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.ui.common.PackageIcon
import app.unscroll.ui.common.UsageAccessGate
import app.unscroll.ui.dashboard.ProportionBar
import app.unscroll.ui.format.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyReportScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    vm: WeeklyReportViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            ui.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !ui.usageAccess -> UsageAccessGate(
                onOpenPermissions = onOpenPermissions,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> ReportBody(ui, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ReportBody(ui: WeeklyReportUiState, modifier: Modifier = Modifier) {
    val cardColor = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val shape = RoundedCornerShape(22.dp)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui.rangeLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.report_compare_caption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Card(colors = cardColor, shape = shape) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        formatDuration(ui.totalMillis),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    ui.versusLabel?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        item {
            Card(colors = cardColor, shape = shape) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Fact(stringResource(R.string.report_busiest), ui.busiestLabel ?: stringResource(R.string.report_no_busiest))
                    Fact(stringResource(R.string.report_peak), ui.peakLabel ?: stringResource(R.string.report_no_peak))
                    Fact(stringResource(R.string.report_unlocks), ui.unlocksLabel)
                }
            }
        }
        item {
            Text(stringResource(R.string.report_top), style = MaterialTheme.typography.titleMedium)
        }
        if (ui.apps.isEmpty()) {
            item {
                Text(stringResource(R.string.report_no_top), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(ui.apps, key = { it.packageName }) { app ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PackageIcon(app.packageName, app.label)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            app.label,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(formatDuration(app.durationMillis), style = MaterialTheme.typography.titleMedium)
                    }
                    ProportionBar(
                        fraction = app.fraction,
                        color = MaterialTheme.colorScheme.primary,
                        track = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
        item {
            Text(stringResource(R.string.report_advice), style = MaterialTheme.typography.titleMedium)
        }
        items(ui.advice) { advice ->
            Card(colors = cardColor, shape = shape) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(advice.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(advice.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
