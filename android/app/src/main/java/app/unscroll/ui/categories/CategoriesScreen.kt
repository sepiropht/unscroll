package app.unscroll.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.ui.chart.CategoryRing
import app.unscroll.ui.chart.ShareLegend
import app.unscroll.ui.common.PackageIcon
import app.unscroll.ui.format.formatDuration

internal val CustomCategoryColors: List<Int> = listOf(
    0xFF0E6E6A,
    0xFFC9841A,
    0xFF8C4A3A,
    0xFF3D6B8C,
    0xFF2F6F4E,
    0xFF6A4E8C,
    0xFFA15C38,
    0xFF3E7C74,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenPermissions: () -> Unit,
    vm: CategoriesViewModel = viewModel(),
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
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        CreateCategoryDialog(
            onDismiss = { creating = false },
            onCreate = { label, color ->
                creating = false
                vm.create(label, color)
            },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.categories_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            CategoriesBody(
                ui = ui,
                onOpenCategory = onOpenCategory,
                onOpenApp = onOpenApp,
                onOpenPermissions = onOpenPermissions,
                onCreate = { creating = true },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun CategoriesBody(
    ui: CategoriesUiState,
    onOpenCategory: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenPermissions: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardColor = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val shape = RoundedCornerShape(22.dp)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.categories_lead), style = MaterialTheme.typography.bodyLarge)
        }
        if (!ui.usageAccess) {
            item {
                Card(colors = cardColor, shape = shape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.usage_gate_body), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onOpenPermissions) {
                            Text(stringResource(R.string.usage_gate_action))
                        }
                    }
                }
            }
        }
        item {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.categories_create))
            }
        }
        if (ui.shares.isNotEmpty()) {
            item {
                Card(colors = cardColor, shape = shape) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.categories_today), style = MaterialTheme.typography.titleMedium)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CategoryRing(
                                fractions = ui.shares.map { it.fraction },
                                colors = ui.shares.map { Color(it.colorArgb) },
                                track = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Text(
                                formatDuration(ui.shares.sumOf { it.durationMillis }),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        ShareLegend(ui.shares)
                    }
                }
            }
        }
        items(ui.rows, key = { it.key }) { row ->
            Card(
                colors = cardColor,
                shape = shape,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenCategory(row.key) },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(row.colorArgb)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            row.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatDuration(row.todayMillis),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (row.custom) {
                        Text(
                            stringResource(R.string.categories_custom),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Text(
                        stringResource(R.string.categories_week_meta, formatDuration(row.weekMillis)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    row.limitMillis?.let { limit ->
                        Text(
                            stringResource(R.string.categories_limit_meta, formatDuration(limit)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
        if (ui.excluded.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.categories_excluded_heading), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.categories_excluded_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.excluded, key = { "ex-${it.packageName}" }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenApp(app.packageName) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PackageIcon(app.packageName, app.label)
                    Spacer(Modifier.width(12.dp))
                    Text(app.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CreateCategoryDialog(onDismiss: () -> Unit, onCreate: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(CustomCategoryColors.first()) }
    val colorLabel = stringResource(R.string.category_color_label)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.category_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(colorLabel, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CustomCategoryColors.take(4).forEach { swatch ->
                        ColorDot(swatch, swatch == color, colorLabel) { color = swatch }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CustomCategoryColors.drop(4).forEach { swatch ->
                        ColorDot(swatch, swatch == color, colorLabel) { color = swatch }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), color) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.limit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .padding(5.dp)
            .clip(CircleShape)
            .background(Color(color))
            .semantics { contentDescription = label },
    )
}
