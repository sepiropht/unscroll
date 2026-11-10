package app.unscroll.ui.guard

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.unscroll.R
import app.unscroll.prefs.EditLock
import app.unscroll.prefs.PinGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Demande le code avant une modification de limite ou de liste de blocage. */
class EditGuard(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var wrongLabel: String = ""
    var pending by mutableStateOf<(() -> Unit)?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var resetKey by mutableIntStateOf(0)
        private set
    private var checking = false

    fun run(block: () -> Unit) {
        if (EditLock.allows(context)) {
            block()
            return
        }
        error = null
        resetKey += 1
        pending = block
    }

    fun dismiss() {
        if (checking) return
        pending = null
        error = null
    }

    fun submit(pin: String) {
        if (checking || pending == null) return
        val action = pending
        checking = true
        error = null
        scope.launch {
            val ok = PinGate.check(context, pin)
            checking = false
            if (ok) {
                EditLock.unlock()
                pending = null
                action?.invoke()
            } else {
                error = wrongLabel
                resetKey += 1
            }
        }
    }
}

@Composable
fun rememberEditGuard(): EditGuard {
    val context = LocalContext.current
    val wrong = stringResource(R.string.pin_wrong)
    val scope = rememberCoroutineScope()
    return remember(context, scope) { EditGuard(context, scope) }.also { it.wrongLabel = wrong }
}

@Composable
fun EditGuardDialog(guard: EditGuard) {
    if (guard.pending == null) return
    Dialog(
        onDismissRequest = { guard.dismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp)) {
                PinPad(
                    title = stringResource(R.string.pin_prompt),
                    error = guard.error,
                    resetKey = guard.resetKey,
                    onDismiss = { guard.dismiss() },
                    onSubmit = guard::submit,
                )
            }
        }
    }
}
