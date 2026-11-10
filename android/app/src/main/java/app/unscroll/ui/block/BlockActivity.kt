package app.unscroll.ui.block

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.unscroll.data.guard.BlockReason
import app.unscroll.data.guard.SubjectKind
import app.unscroll.service.ScreenTimeAccessibilityService
import app.unscroll.ui.theme.UnscrollTheme

class BlockActivity : ComponentActivity() {
    private var state by mutableStateOf<BlockUi?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableEdgeToEdge()
        current = this
        state = read(intent)
        setContent {
            UnscrollTheme {
                val ui = state
                if (ui != null) {
                    BlockScreen(
                        state = ui,
                        onClose = {
                            if (!ScreenTimeAccessibilityService.requestClose()) {
                                ScreenTimeAccessibilityService.goHomeFallback(this@BlockActivity)
                                finish()
                            }
                        },
                        onExtra = {
                            ScreenTimeAccessibilityService.requestExtra(ui.targetKey, ui.reason.name)
                        },
                    )
                }
            }
        }
    }

    override fun onPictureInPictureRequested(): Boolean = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        state = read(intent)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun read(intent: Intent?): BlockUi? {
        intent ?: return null
        val reason = runCatching { BlockReason.valueOf(intent.getStringExtra(EXTRA_REASON) ?: return null) }.getOrNull()
            ?: return null
        val kind = runCatching {
            SubjectKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: SubjectKind.APP.name)
        }.getOrDefault(SubjectKind.APP)
        val untilRaw = intent.getLongExtra(EXTRA_UNTIL, NO_UNTIL)
        return BlockUi(
            reason = reason,
            subjectKind = kind,
            subject = intent.getStringExtra(EXTRA_SUBJECT).orEmpty(),
            untilMillis = if (untilRaw == NO_UNTIL) null else untilRaw,
            allowExtra = intent.getBooleanExtra(EXTRA_ALLOW, false),
            targetKey = intent.getStringExtra(EXTRA_TARGET).orEmpty(),
            scheduleName = intent.getStringExtra(EXTRA_SCHEDULE).orEmpty(),
        )
    }

    companion object {
        const val EXTRA_REASON = "reason"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SUBJECT = "subject"
        const val EXTRA_UNTIL = "until"
        const val EXTRA_ALLOW = "allow"
        const val EXTRA_TARGET = "target"
        const val EXTRA_SCHEDULE = "schedule"
        private const val NO_UNTIL = Long.MIN_VALUE

        @Volatile
        var current: BlockActivity? = null

        fun finishIfShown() {
            current?.let { activity ->
                activity.runOnUiThread { activity.finish() }
            }
        }
    }
}
