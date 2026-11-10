package app.unscroll.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unscroll.R
import app.unscroll.sync.AndroidSnapshotStore
import app.unscroll.sync.BillingPlan
import app.unscroll.sync.SyncBilling
import app.unscroll.sync.PairingLink
import app.unscroll.sync.PairingMaterial
import app.unscroll.sync.SyncBindings
import app.unscroll.sync.SyncRules
import app.unscroll.sync.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PrivacyUi(
    val enabled: Boolean = false,
    val url: String = "",
    val token: String = "",
    val passphrase: String = "",
    val tokenKept: Boolean = false,
    val secretKept: Boolean = false,
    val canTransfer: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val noticeError: Boolean = false,
    val paste: String = "",
    val showPairing: Boolean = false,
    val pairingLink: String? = null,
    val billing: BillingPlan = BillingPlan(),
)

class PrivacyViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SyncSettings(app)
    private val store = AndroidSnapshotStore(app)
    private val provider = SyncBindings.provider(app)
    private val _ui = MutableStateFlow(PrivacyUi())
    val ui: StateFlow<PrivacyUi> = _ui.asStateFlow()

    init {
        reload(notice = null, error = false)
    }

    fun setEnabled(value: Boolean) = _ui.update { it.copy(enabled = value, notice = null) }

    fun setUrl(value: String) = _ui.update { it.copy(url = value, notice = null) }

    fun setToken(value: String) = _ui.update { it.copy(token = value, notice = null) }

    fun setPassphrase(value: String) = _ui.update { it.copy(passphrase = value, notice = null) }

    fun setPaste(value: String) {
        if (value.length > SyncRules.PAIRING_LINK_MAX) {
            val text = getApplication<Application>().getString(R.string.privacy_invalid)
            _ui.update { it.copy(notice = text, noticeError = true) }
            return
        }
        _ui.update { it.copy(paste = value, notice = null) }
    }

    fun save() {
        val ui = _ui.value
        if (ui.busy) return
        val urlInput = ui.url.trim().ifEmpty { SyncRules.DEFAULT_SYNC_URL }
        val base = SyncRules.normalizeBase(urlInput)
        if (base == null) {
            invalid()
            return
        }
        val typedToken = ui.token.trim()
        val typedPhrase = ui.passphrase
        val provisioning = ui.enabled &&
            typedToken.isEmpty() &&
            typedPhrase.isEmpty() &&
            !SyncRules.validToken(settings.token)
        if (provisioning) {
            provision(base)
            return
        }
        val token = if (typedToken.isNotEmpty()) typedToken else settings.token
        val kind: String
        val secret: String
        if (typedPhrase.isNotEmpty()) {
            kind = SyncRules.SECRET_PASSPHRASE
            secret = typedPhrase
        } else if (settings.secretKind == SyncRules.SECRET_RAW || settings.secretKind == SyncRules.SECRET_PASSPHRASE) {
            kind = settings.secretKind
            secret = settings.secret
        } else {
            kind = ""
            secret = ""
        }
        val tokenOk = token.isEmpty() || SyncRules.validToken(token)
        val secretOk = when (kind) {
            "" -> secret.isEmpty()
            SyncRules.SECRET_RAW -> SyncRules.isRawKey(secret)
            SyncRules.SECRET_PASSPHRASE -> SyncRules.validPassphrase(secret)
            else -> false
        }
        val ready = SyncRules.validToken(token) && secretOk && kind.isNotEmpty()
        if (!tokenOk || !secretOk || (ui.enabled && !ready)) {
            invalid()
            return
        }
        settings.save(ui.enabled, base, token, kind, secret)
        val saved = if (ui.enabled) R.string.privacy_saved_on else R.string.privacy_saved
        reload(notice = getApplication<Application>().getString(saved), error = false)
    }

    /** Envoie puis reçoit. L'usage ne concerne que cet appareil. Les réglages suivent la révision. */
    fun syncNow() {
        if (_ui.value.busy || !_ui.value.canTransfer) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, notice = null) }
            val outcome = withContext(Dispatchers.IO) { pushAndPull() }
            _ui.update {
                it.copy(
                    busy = false,
                    notice = outcome.second,
                    noticeError = outcome.first,
                    canTransfer = provider.status().let { status -> status.enabled && status.configured },
                )
            }
            refreshBilling()
        }
    }

    fun togglePairing() {
        val ui = _ui.value
        if (ui.busy) return
        if (ui.showPairing) {
            _ui.update { it.copy(showPairing = false, pairingLink = null) }
            return
        }
        val link = currentLink()
        if (link == null) {
            val text = getApplication<Application>().getString(R.string.privacy_sync_pair_need)
            _ui.update { it.copy(notice = text, noticeError = true, showPairing = false, pairingLink = null) }
            return
        }
        _ui.update { it.copy(showPairing = true, pairingLink = link, notice = null, noticeError = false) }
    }

    fun applyPasted() {
        val link = _ui.value.paste
        if (link.isBlank()) return
        applyPairing(link, fromPaste = true)
    }

    fun applyPairing(raw: String, fromPaste: Boolean = false) {
        if (_ui.value.busy) return
        val decoded = PairingLink.decode(raw)
        if (decoded == null) {
            invalid()
            return
        }
        val kind = if (decoded.mode == SyncRules.SECRET_RAW) SyncRules.SECRET_RAW else SyncRules.SECRET_PASSPHRASE
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, notice = null, showPairing = false, pairingLink = null) }
            val outcome = withContext(Dispatchers.IO) {
                settings.save(true, decoded.baseUrl, decoded.token, kind, decoded.secret)
                pushAndPull()
            }
            val app = getApplication<Application>()
            if (outcome.first) {
                reload(notice = outcome.second, error = true, paste = if (fromPaste) _ui.value.paste else "")
            } else {
                reload(notice = app.getString(R.string.privacy_sync_paired), error = false)
            }
        }
    }

    /** Hosted plan, shown only when the server reports billing. */
    fun refreshBilling() {
        if (!settings.enabled || !SyncRules.validToken(settings.token)) {
            _ui.update { it.copy(billing = BillingPlan()) }
            return
        }
        val base = settings.baseUrl.ifEmpty { SyncRules.DEFAULT_SYNC_URL }
        val token = settings.token
        viewModelScope.launch {
            val plan = withContext(Dispatchers.IO) { SyncBilling.fetch(base, token) }
            _ui.update { it.copy(billing = plan) }
        }
    }

    fun subscribe(yearly: Boolean) = openBilling(portal = false, yearly = yearly)

    fun manageSubscription() = openBilling(portal = true, yearly = false)

    private fun openBilling(portal: Boolean, yearly: Boolean) {
        if (_ui.value.busy) return
        val base = settings.baseUrl.ifEmpty { SyncRules.DEFAULT_SYNC_URL }
        val token = settings.token
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, notice = null) }
            val (url, reason) = withContext(Dispatchers.IO) { SyncBilling.link(base, token, portal, yearly) }
            val app = getApplication<Application>()
            if (url == null) {
                _ui.update { it.copy(busy = false, notice = reasonText(reason), noticeError = true) }
                return@launch
            }
            val opened = try {
                app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
            _ui.update {
                it.copy(
                    busy = false,
                    notice = if (opened) null else app.getString(R.string.privacy_reason_network),
                    noticeError = !opened,
                )
            }
        }
    }

    fun report(messageId: Int, error: Boolean) {
        val text = getApplication<Application>().getString(messageId)
        _ui.update { it.copy(notice = text, noticeError = error) }
    }

    private fun provision(base: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, notice = null, showPairing = false, pairingLink = null) }
            val attempt = withContext(Dispatchers.IO) {
                val created = SyncBindings.provision(base)
                if (!created.ok || created.token.isNullOrEmpty()) {
                    return@withContext Attempt(provisioned = false, error = true, code = created.reason ?: "network")
                }
                val key = SyncRules.generateRawKey()
                settings.save(true, base, created.token, SyncRules.SECRET_RAW, key)
                val sync = pushAndPull()
                if (sync.first) Attempt(provisioned = true, error = true, text = sync.second) else Attempt(provisioned = true)
            }
            when {
                !attempt.provisioned -> {
                    _ui.update {
                        it.copy(busy = false, notice = reasonText(attempt.code), noticeError = true)
                    }
                }
                attempt.error -> reload(notice = accountSaved(attempt.text), error = true)
                else -> reload(
                    notice = getApplication<Application>().getString(R.string.privacy_sync_provisioned),
                    error = false,
                )
            }
        }
    }

    private fun accountSaved(detail: String?): String {
        val base = getApplication<Application>().getString(R.string.privacy_sync_account_saved)
        if (detail.isNullOrEmpty()) return base
        return "$base $detail"
    }

    private suspend fun pushAndPull(): Pair<Boolean, String> {
        val sent = provider.push(store.capture())
        if (!sent.ok) return true to reasonText(sent.reason)
        val result = provider.pull()
        if (result.snapshot != null) store.applyUsage(result.snapshot)
        if (result.ok && result.settings != null &&
            result.settingsRevision != null && result.settingsEtag != null && result.settingsBody != null
        ) {
            store.applySettings(result.settings)
            settings.commitSettings(result.settingsRevision, result.settingsEtag, result.settingsBody)
        }
        return if (!result.ok) {
            true to reasonText(result.reason)
        } else {
            false to getApplication<Application>().getString(R.string.privacy_sync_ok)
        }
    }

    private fun currentLink(): String? {
        val mode = when (settings.secretKind) {
            SyncRules.SECRET_RAW -> SyncRules.SECRET_RAW
            SyncRules.SECRET_PASSPHRASE -> "pbkdf2"
            else -> return null
        }
        return try {
            PairingLink.encode(
                PairingMaterial(
                    baseUrl = settings.baseUrl,
                    token = settings.token,
                    mode = mode,
                    secret = settings.secret,
                ),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun invalid() {
        val text = getApplication<Application>().getString(R.string.privacy_invalid)
        _ui.update { it.copy(notice = text, noticeError = true) }
    }

    private fun reload(notice: String?, error: Boolean, paste: String = "") {
        val status = provider.status()
        val storedError = settings.lastError
        val showStored = notice == null && storedError != null
        _ui.value = PrivacyUi(
            enabled = settings.enabled,
            url = settings.baseUrl.ifEmpty { SyncRules.DEFAULT_SYNC_URL },
            tokenKept = settings.hasToken(),
            secretKept = settings.hasSecret(),
            canTransfer = status.enabled && status.configured,
            notice = if (showStored) reasonText(storedError) else notice,
            noticeError = if (showStored) true else error,
            paste = paste,
            billing = _ui.value.billing,
        )
        refreshBilling()
    }

    private data class Attempt(
        val provisioned: Boolean,
        val error: Boolean = false,
        val code: String? = null,
        val text: String? = null,
    )

    private fun reasonText(reason: String?): String {
        val app = getApplication<Application>()
        return when {
            reason == "disabled" -> app.getString(R.string.privacy_reason_disabled)
            reason == "invalid" -> app.getString(R.string.privacy_reason_invalid)
            reason == "schema" -> app.getString(R.string.privacy_reason_schema)
            reason == "decrypt" -> app.getString(R.string.privacy_reason_decrypt)
            reason == "too-large" -> app.getString(R.string.privacy_reason_too_large)
            reason == "conflict" -> app.getString(R.string.privacy_reason_conflict)
            reason == "http-401" || reason == "http-403" -> app.getString(R.string.privacy_reason_denied)
            reason == "http-402" -> app.getString(R.string.sync_bill_ended)
            reason != null && reason.startsWith("http-") ->
                app.getString(R.string.privacy_reason_http, reason.removePrefix("http-"))
            else -> app.getString(R.string.privacy_reason_network)
        }
    }
}
