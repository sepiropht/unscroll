package app.unscroll.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unscroll.R
import app.unscroll.data.backup.ArchiveException
import app.unscroll.data.backup.SettingsArchive
import app.unscroll.data.backup.Snapshot
import app.unscroll.prefs.AppPrefs
import app.unscroll.sync.SyncSettings
import app.unscroll.prefs.EditLock
import app.unscroll.widget.HomeWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RestorePrompt(
    val limits: Int,
    val schedules: Int,
    val categories: Int,
    val routines: Int,
)

data class SettingsUi(
    val themeMode: String = AppPrefs.THEME_SYSTEM,
    val dynamicColor: Boolean = true,
    val weekMonday: Boolean = true,
    val hourFormat: String = AppPrefs.HOUR_SYSTEM,
    val showSystemApps: Boolean = true,
    val pinOn: Boolean = false,
    val excludedCount: Int = 0,
    val busy: Boolean = false,
    val notice: String? = null,
    val noticeError: Boolean = false,
    val restore: RestorePrompt? = null,
    val confirmReset: Boolean = false,
    val syncEnabled: Boolean = false,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)
    private val archive = SettingsArchive(app)
    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()
    private var pending: Snapshot? = null

    init {
        publish()
    }

    fun reload() = publish()

    fun setTheme(mode: String) {
        prefs.themeMode = mode
        publish()
    }

    fun setDynamic(enabled: Boolean) {
        prefs.dynamicColor = enabled
        publish()
    }

    fun setWeekMonday(monday: Boolean) {
        prefs.weekStartsMonday = monday
        publish()
    }

    fun setHourFormat(format: String) {
        prefs.hourFormat = format
        publish()
    }

    fun setShowSystem(show: Boolean) {
        prefs.showSystemApps = show
        HomeWidgets.refreshAsync(getApplication())
        publish()
    }

    fun setPin(pin: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { prefs.setPin(pin) }
            if (ok) {
                EditLock.unlock()
                publish()
            }
        }
    }

    fun clearPin() {
        prefs.clearPin()
        publish()
    }

    fun holdDocument() = EditLock.holdForDocument()

    fun writeBackup(uri: Uri) {
        EditLock.releaseDocument()
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, notice = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { archive.writeBackup(uri) }.fold(
                    onSuccess = { false to getApplication<Application>().getString(R.string.settings_backup_ok) },
                    onFailure = { true to failureText(it) },
                )
            }
            _ui.update { it.copy(busy = false, notice = result.second, noticeError = result.first) }
        }
    }

    fun writeCsv(uri: Uri) {
        EditLock.releaseDocument()
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, notice = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { archive.writeHistory(uri) }.fold(
                    onSuccess = { false to getApplication<Application>().getString(R.string.settings_csv_ok) },
                    onFailure = { true to failureText(it) },
                )
            }
            _ui.update { it.copy(busy = false, notice = result.second, noticeError = result.first) }
        }
    }

    fun stageRestore(uri: Uri) {
        EditLock.releaseDocument()
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, notice = null, restore = null) }
            val staged = withContext(Dispatchers.IO) {
                runCatching { archive.readBackup(uri) }
            }
            staged.fold(
                onSuccess = { (preview, snapshot) ->
                    pending = snapshot
                    _ui.update {
                        it.copy(
                            busy = false,
                            restore = RestorePrompt(
                                limits = preview.limits,
                                schedules = preview.schedules,
                                categories = preview.categories,
                                routines = preview.routines,
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    pending = null
                    _ui.update { it.copy(busy = false, notice = failureText(error), noticeError = true) }
                },
            )
        }
    }

    fun dismissRestore() {
        pending = null
        _ui.update { it.copy(restore = null) }
    }

    fun confirmRestore() {
        val snapshot = pending ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, restore = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { archive.apply(snapshot) }.fold(
                    onSuccess = { false to getApplication<Application>().getString(R.string.settings_restore_ok) },
                    onFailure = { true to failureText(it) },
                )
            }
            pending = null
            publish(result.second, result.first)
        }
    }

    fun askReset() {
        _ui.update { it.copy(confirmReset = true, notice = null) }
    }

    fun dismissReset() {
        _ui.update { it.copy(confirmReset = false) }
    }

    fun confirmReset(onDone: () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, confirmReset = false) }
            val failed = runCatching { archive.wipe() }.exceptionOrNull()
            if (failed != null) {
                _ui.update { it.copy(busy = false, notice = failureText(failed), noticeError = true) }
            } else {
                onDone()
            }
        }
    }

    private fun publish(notice: String? = _ui.value.notice, noticeError: Boolean = _ui.value.noticeError) {
        _ui.value = SettingsUi(
            themeMode = prefs.themeMode,
            dynamicColor = prefs.dynamicColor,
            weekMonday = prefs.weekStartsMonday,
            hourFormat = prefs.hourFormat,
            showSystemApps = prefs.showSystemApps,
            pinOn = prefs.hasPin(),
            excludedCount = prefs.excludedPackages.size,
            busy = false,
            notice = notice,
            noticeError = noticeError,
            restore = _ui.value.restore,
            confirmReset = _ui.value.confirmReset,
            syncEnabled = SyncSettings(getApplication()).enabled,
        )
    }

    private fun failureText(error: Throwable): String {
        val app = getApplication<Application>()
        val kind = (error as? ArchiveException)?.kind
        val res = when (kind) {
            ArchiveException.Kind.FORMAT -> R.string.settings_file_bad
            ArchiveException.Kind.SIZE -> R.string.settings_file_big
            ArchiveException.Kind.READ -> R.string.settings_file_read
            else -> R.string.settings_file_write
        }
        return app.getString(res)
    }
}
