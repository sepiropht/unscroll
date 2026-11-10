package app.unscroll.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.unscroll.R
import app.unscroll.data.guard.GuardRepository
import app.unscroll.data.repo.InstalledApps
import app.unscroll.prefs.AppPrefs
import app.unscroll.ui.common.PackageIcon
import app.unscroll.widget.HomeWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExclusionRow(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val excluded: Boolean,
)

data class ExclusionsUi(
    val loading: Boolean = true,
    val rows: List<ExclusionRow> = emptyList(),
)

class ExclusionsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)
    private val repo = GuardRepository(app)
    private val _ui = MutableStateFlow(ExclusionsUi())
    val ui: StateFlow<ExclusionsUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { load() }
            _ui.value = ExclusionsUi(loading = false, rows = rows)
        }
    }

    fun toggle(packageName: String, excluded: Boolean) {
        prefs.setPackageExcluded(packageName, excluded)
        HomeWidgets.refreshAsync(getApplication())
        _ui.value = _ui.value.copy(
            rows = _ui.value.rows.map { row ->
                if (row.packageName == packageName) row.copy(excluded = excluded) else row
            },
        )
    }

    private fun load(): List<ExclusionRow> {
        val app = getApplication<Application>()
        val system = InstalledApps.systemPackages(app)
        val excluded = prefs.excludedPackages
        val known = repo.launchableApps().associate { it.packageName to it.label }
        val names = (known.keys + excluded).distinct()
        return names.map { packageName ->
            ExclusionRow(
                packageName = packageName,
                label = known[packageName] ?: repo.labelOf(packageName),
                system = packageName in system,
                excluded = packageName in excluded,
            )
        }.sortedBy { it.label.lowercase() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExclusionsScreen(
    onBack: () -> Unit,
    vm: ExclusionsViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    SettingsGate(onBack = onBack) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.exclusions_title)) },
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
                return@Scaffold
            }
            val needle = query.trim()
            val shown = ui.rows.filter { row ->
                needle.isEmpty() || row.label.contains(needle, ignoreCase = true) ||
                    row.packageName.contains(needle, ignoreCase = true)
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.exclusions_lead),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(60) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.exclusions_search)) },
                        singleLine = true,
                    )
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.exclusions_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(shown, key = { it.packageName }) { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PackageIcon(row.packageName, row.label)
                        Column(Modifier.weight(1f)) {
                            Text(row.label, style = MaterialTheme.typography.titleMedium)
                            if (row.system) {
                                Text(
                                    stringResource(R.string.exclusions_system),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = row.excluded,
                            onCheckedChange = { vm.toggle(row.packageName, it) },
                        )
                    }
                }
            }
        }
    }
}
