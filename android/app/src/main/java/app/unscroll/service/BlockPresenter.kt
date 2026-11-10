package app.unscroll.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.unscroll.R
import app.unscroll.data.guard.BlockDecision
import app.unscroll.ui.block.BlockActivity
import app.unscroll.ui.block.BlockScreen
import app.unscroll.ui.block.BlockUi
import app.unscroll.ui.theme.UnscrollTheme

/**
 * Affiche l'écran de pause par-dessus l'application limitée.
 * Superposition si l'autorisation est là, sinon activité plein écran.
 */
class BlockPresenter(context: Context) {
    private val app = context.applicationContext
    private val windowManager = app.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var owner: OverlayOwner? = null
    private val state = mutableStateOf<BlockUi?>(null)
    var visible: Boolean = false
        private set

    fun show(decision: BlockDecision) {
        val ui = decision.toUi() ?: return
        state.value = ui
        revealNow()
    }

    fun hide() {
        visible = false
        state.value = null
        removeOverlay()
        BlockActivity.finishIfShown()
    }

    fun destroy() {
        hide()
    }

    private fun revealNow() {
        val ui = state.value ?: return
        if (Settings.canDrawOverlays(app)) {
            if (overlay == null) {
                val added = runCatching { addOverlay() }.isSuccess
                if (!added) launchActivity(ui)
            }
            BlockActivity.finishIfShown()
        } else {
            removeOverlay()
            launchActivity(ui)
        }
        visible = true
    }

    private fun addOverlay() {
        val themed = android.view.ContextThemeWrapper(app, R.style.Theme_Unscroll)
        val life = OverlayOwner()
        life.start()
        val view = ComposeView(themed).apply {
            setViewTreeLifecycleOwner(life)
            setViewTreeViewModelStoreOwner(life)
            setViewTreeSavedStateRegistryOwner(life)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    GuardRuntime.requestClose()
                    true
                } else {
                    false
                }
            }
            setContent {
                UnscrollTheme {
                    val ui = state.value
                    if (ui != null) {
                        BlockScreen(
                            state = ui,
                            onClose = { GuardRuntime.requestClose() },
                            onExtra = {
                                GuardRuntime.requestExtra(ui.targetKey, ui.reason.name)
                            },
                        )
                    }
                }
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val screen = screenSize()
        val params = WindowManager.LayoutParams(
            screen.first,
            screen.second,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                setFitInsetsSides(0)
            }
        }
        windowManager.addView(view, params)
        view.requestFocus()
        overlay = view
        owner = life
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        runCatching { windowManager.removeView(view) }
        owner?.destroy()
        overlay = null
        owner = null
    }

    private fun launchActivity(ui: BlockUi) {
        val intent = Intent(app, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockActivity.EXTRA_REASON, ui.reason.name)
            putExtra(BlockActivity.EXTRA_KIND, ui.subjectKind.name)
            putExtra(BlockActivity.EXTRA_SUBJECT, ui.subject)
            putExtra(BlockActivity.EXTRA_UNTIL, ui.untilMillis ?: Long.MIN_VALUE)
            putExtra(BlockActivity.EXTRA_ALLOW, ui.allowExtra)
            putExtra(BlockActivity.EXTRA_TARGET, ui.targetKey)
            putExtra(BlockActivity.EXTRA_SCHEDULE, ui.scheduleName)
        }
        app.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val size = Point()
        windowManager.defaultDisplay.getRealSize(size)
        return size.x to size.y
    }
}

private fun BlockDecision.toUi(): BlockUi? {
    val reason = reason ?: return null
    return BlockUi(
        reason = reason,
        subjectKind = subjectKind,
        subject = subject,
        untilMillis = untilMillis,
        allowExtra = allowExtra,
        targetKey = targetKey,
        scheduleName = scheduleName,
    )
}

private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
