package app.unscroll.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.unscroll.R
import app.unscroll.prefs.EditLock
import app.unscroll.prefs.PinGate
import app.unscroll.ui.guard.PinPad
import kotlinx.coroutines.launch

/** Le code, s’il existe, est demandé une fois. Quitter l’application le redemande. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGate(
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var passed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resetKey by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && PinGate.isEnabled(context) && !EditLock.allows(context)) {
                passed = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val open = !PinGate.isEnabled(context) || passed || EditLock.allows(context)
    if (open) {
        content()
        return
    }
    val wrong = stringResource(R.string.pin_wrong)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            PinPad(
                title = stringResource(R.string.settings_pin_title),
                error = error,
                resetKey = resetKey,
                onDismiss = onBack,
                onSubmit = { pin ->
                    if (checking) return@PinPad
                    checking = true
                    error = null
                    scope.launch {
                        val ok = PinGate.check(context, pin)
                        checking = false
                        if (ok) {
                            EditLock.unlock()
                            passed = true
                        } else {
                            error = wrong
                            resetKey += 1
                        }
                    }
                },
            )
        }
    }
}
