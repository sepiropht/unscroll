package app.unscroll.ui.achieve

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unscroll.R
import app.unscroll.data.achieve.AchievementRepository
import app.unscroll.data.achieve.AchievementResult
import app.unscroll.data.achieve.AchievementRules
import app.unscroll.data.achieve.BadgeProgress
import app.unscroll.permissions.PermissionChecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BadgeUi(
    val key: String,
    val title: String,
    val body: String,
    val unlocked: Boolean,
    val progressLabel: String?,
    val hint: String?,
)

data class AchievementsUiState(
    val loading: Boolean = true,
    val usageAccess: Boolean = false,
    val goalMinutes: Int = AchievementRules.DEFAULT_GOAL_MINUTES,
    val todayMillis: Long = 0L,
    val goalMillis: Long = AchievementStoreMillis.DEFAULT,
    val todayObserved: Boolean = false,
    val todayUnder: Boolean = false,
    val liveStreak: Int = 0,
    val bestStreak: Int = 0,
    val focusSessions: Int = 0,
    val badges: List<BadgeUi> = emptyList(),
)

private object AchievementStoreMillis {
    const val DEFAULT = AchievementRules.DEFAULT_GOAL_MINUTES * 60_000L
}

class AchievementsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AchievementRepository(app)
    private val _ui = MutableStateFlow(AchievementsUiState())
    val ui: StateFlow<AchievementsUiState> = _ui.asStateFlow()
    private var generation = 0

    init {
        refresh()
    }

    fun refresh() {
        val ticket = ++generation
        viewModelScope.launch {
            val access = PermissionChecks.hasUsageAccess(getApplication())
            val loaded = withContext(Dispatchers.IO) { repo.load() }
            if (ticket != generation) return@launch
            _ui.value = map(loaded, access)
        }
    }

    fun setGoalMinutes(minutes: Int) {
        if (minutes !in AchievementRules.GOAL_MINUTES) return
        if (_ui.value.goalMinutes == minutes && !_ui.value.loading) return
        repo.setGoalMinutes(minutes)
        refresh()
    }

    private fun map(result: AchievementResult, access: Boolean): AchievementsUiState {
        val app = getApplication<Application>()
        return AchievementsUiState(
            loading = false,
            usageAccess = access,
            goalMinutes = (result.goalMillis / 60_000L).toInt(),
            todayMillis = result.todayMillis,
            goalMillis = result.goalMillis,
            todayObserved = result.todayObserved,
            todayUnder = result.todayUnder,
            liveStreak = result.liveStreak,
            bestStreak = result.bestStreak,
            focusSessions = result.focusSessions,
            badges = result.badges.map { badge -> badge.toUi(app, result) },
        )
    }

    private fun BadgeProgress.toUi(app: Application, result: AchievementResult): BadgeUi {
        val title = app.getString(titleRes(key))
        val body = app.getString(bodyRes(key))
        val progressLabel = if (target > 1 && !unlocked) {
            app.getString(R.string.badge_progress, progress.coerceAtLeast(0), target)
        } else if (unlocked) {
            app.getString(R.string.badge_earned)
        } else {
            null
        }
        val hint = if (key == AchievementRules.CLEAR_DAY) clearHint(app, result) else null
        return BadgeUi(key, title, body, unlocked, progressLabel, hint)
    }

    private fun clearHint(app: Application, result: AchievementResult): String {
        val mark = result.todayGuard
        return when {
            mark == null || !mark.armed -> app.getString(R.string.badge_clear_today_none)
            mark.launches == 0 -> app.getString(R.string.badge_clear_today_clean)
            else -> app.getString(R.string.badge_clear_today_hit)
        }
    }

    private fun titleRes(key: String): Int = when (key) {
        AchievementRules.FIRST_GOAL -> R.string.badge_first_title
        AchievementRules.STREAK_3 -> R.string.badge_streak3_title
        AchievementRules.STREAK_7 -> R.string.badge_streak7_title
        AchievementRules.STREAK_30 -> R.string.badge_streak30_title
        AchievementRules.LIGHT_DAY -> R.string.badge_light_title
        AchievementRules.CLEAR_DAY -> R.string.badge_clear_title
        else -> R.string.badge_focus_title
    }

    private fun bodyRes(key: String): Int = when (key) {
        AchievementRules.FIRST_GOAL -> R.string.badge_first_body
        AchievementRules.STREAK_3 -> R.string.badge_streak3_body
        AchievementRules.STREAK_7 -> R.string.badge_streak7_body
        AchievementRules.STREAK_30 -> R.string.badge_streak30_body
        AchievementRules.LIGHT_DAY -> R.string.badge_light_body
        AchievementRules.CLEAR_DAY -> R.string.badge_clear_body
        else -> R.string.badge_focus_body
    }
}
