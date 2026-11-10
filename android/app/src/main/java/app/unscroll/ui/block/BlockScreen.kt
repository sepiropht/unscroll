package app.unscroll.ui.block

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.unscroll.R
import app.unscroll.data.guard.BlockReason
import app.unscroll.data.guard.Quotes
import app.unscroll.data.guard.SubjectKind
import app.unscroll.data.stats.LimitKeys
import app.unscroll.prefs.PinGate
import app.unscroll.ui.format.formatCountdown
import app.unscroll.ui.guard.PinPad
import kotlinx.coroutines.delay

data class BlockUi(
    val reason: BlockReason,
    val subjectKind: SubjectKind,
    val subject: String,
    val untilMillis: Long?,
    val allowExtra: Boolean,
    val targetKey: String,
    val scheduleName: String,
)

@Composable
fun BlockScreen(
    state: BlockUi,
    onClose: () -> Unit,
    onExtra: () -> Unit,
) {
    val context = LocalContext.current
    var enteringPin by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pinReset by remember { mutableIntStateOf(0) }
    BackHandler(onBack = {
        if (enteringPin) enteringPin = false else onClose()
    })
    val quote = remember(state.targetKey, state.reason) {
        Quotes.pick(context, state.targetKey + state.reason.name)
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.untilMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Image(
            painter = painterResource(R.drawable.ic_mark),
            contentDescription = stringResource(R.string.cd_app_mark),
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.height(22.dp))
        Text(
            stringResource(reasonChip(state.reason)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            state.subject.ifBlank { stringResource(R.string.block_subject_fallback) },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            reasonText(state),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val until = state.untilMillis
        if (until != null && until != LimitKeys.PERMANENT_UNTIL) {
            val remain = (until - now).coerceAtLeast(0L)
            Spacer(Modifier.height(18.dp))
            Text(
                formatCountdown(remain),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.block_until_caption),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (until == LimitKeys.PERMANENT_UNTIL) {
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.block_no_end),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(22.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(18.dp),
        ) {
            Text(
                quote,
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(28.dp))
        if (enteringPin) {
            val wrong = stringResource(R.string.pin_wrong)
            PinPad(
                title = stringResource(R.string.pin_prompt),
                error = pinError,
                resetKey = pinReset,
                onDismiss = { enteringPin = false },
                onSubmit = { pin ->
                    if (PinGate.matches(context, pin)) {
                        enteringPin = false
                        onExtra()
                    } else {
                        pinError = wrong
                        pinReset += 1
                    }
                },
            )
        } else {
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.block_close_app))
            }
            if (state.allowExtra) {
                TextButton(
                    onClick = {
                        if (PinGate.isEnabled(context)) {
                            pinError = null
                            pinReset += 1
                            enteringPin = true
                        } else {
                            onExtra()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.block_extra_five))
                }
            }
        }
    }
}

private fun reasonChip(reason: BlockReason): Int = when (reason) {
    BlockReason.DAILY, BlockReason.SESSION, BlockReason.OPENS -> R.string.reason_limit
    BlockReason.SCHEDULE -> R.string.reason_schedule
    BlockReason.FOCUS -> R.string.reason_focus
    BlockReason.COOLDOWN -> R.string.reason_pause
    BlockReason.SLEEP -> R.string.reason_sleep
    BlockReason.INSTANT -> R.string.reason_block
}

@Composable
private fun reasonText(state: BlockUi): String {
    val subject = state.subject
    return when (state.reason) {
        BlockReason.DAILY -> when (state.subjectKind) {
            SubjectKind.CATEGORY -> stringResource(R.string.block_reason_daily_category, subject)
            SubjectKind.WEB -> stringResource(R.string.block_reason_daily_web, subject)
            SubjectKind.APP -> stringResource(R.string.block_reason_daily_app, subject)
        }
        BlockReason.SESSION -> stringResource(R.string.block_reason_session, subject)
        BlockReason.COOLDOWN -> stringResource(R.string.block_reason_cooldown, subject)
        BlockReason.OPENS -> when (state.subjectKind) {
            SubjectKind.CATEGORY -> stringResource(R.string.block_reason_opens_category, subject)
            SubjectKind.WEB -> stringResource(R.string.block_reason_opens_web, subject)
            SubjectKind.APP -> stringResource(R.string.block_reason_opens_app, subject)
        }
        BlockReason.INSTANT -> stringResource(R.string.block_reason_instant, subject)
        BlockReason.SCHEDULE -> stringResource(R.string.block_reason_schedule, state.scheduleName.ifBlank { subject })
        BlockReason.SLEEP -> stringResource(R.string.block_reason_sleep)
        BlockReason.FOCUS -> stringResource(R.string.block_reason_focus, subject)
    }
}
