package app.unscroll

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import app.unscroll.R
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.unscroll.prefs.AppPrefs
import app.unscroll.sync.SyncRules
import app.unscroll.prefs.EditLock
import app.unscroll.service.GuardNotifications
import app.unscroll.ui.achieve.AchievementsScreen
import app.unscroll.ui.categories.CategoriesScreen
import app.unscroll.ui.categories.CategoryDetailScreen
import app.unscroll.ui.dashboard.DashboardScreen
import app.unscroll.ui.detail.AppDetailScreen
import app.unscroll.ui.history.HistoryScreen
import app.unscroll.service.FocusSessions
import app.unscroll.ui.limits.FocusRoutineScreen
import app.unscroll.ui.limits.FocusScreen
import app.unscroll.ui.limits.LimitsScreen
import app.unscroll.ui.limits.ScheduleEditorScreen
import app.unscroll.ui.limits.SchedulesScreen
import app.unscroll.ui.limits.SleepScreen
import app.unscroll.ui.onboarding.OnboardingScreen
import app.unscroll.ui.permissions.PermissionStatusScreen
import app.unscroll.ui.reminders.RemindersScreen
import app.unscroll.ui.report.WeeklyReportScreen
import app.unscroll.ui.settings.AboutScreen
import app.unscroll.ui.settings.ExclusionsScreen
import app.unscroll.ui.settings.PrivacyScreen
import app.unscroll.ui.settings.SettingsScreen
import app.unscroll.ui.settings.SyncScreen
import app.unscroll.ui.theme.UnscrollTheme
import app.unscroll.ui.websites.WebsiteDetailScreen
import app.unscroll.ui.websites.WebsitesScreen
import app.unscroll.widget.HomeWidgets

class MainActivity : ComponentActivity() {
    private var pendingRoute by mutableStateOf<String?>(null)
    private var pendingStartFocus by mutableStateOf(false)
    private var pendingPairing by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute = intent?.getStringExtra(GuardNotifications.EXTRA_ROUTE)
        pendingStartFocus = intent?.getBooleanExtra(GuardNotifications.EXTRA_START_FOCUS, false) == true
        pendingPairing = pairingUri(intent)
        val start = if (AppPrefs(this).onboardingDone) Routes.DASHBOARD else Routes.ONBOARDING
        setContent {
            UnscrollTheme {
                AppNav(
                    start = start,
                    pendingRoute = pendingRoute,
                    pendingStartFocus = pendingStartFocus,
                    pendingPairing = pendingPairing,
                    onRouteConsumed = { pendingRoute = null },
                    onStartConsumed = { pendingStartFocus = false },
                    onPairingConsumed = { consumePairing() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        HomeWidgets.refreshAsync(this)
    }

    override fun onStop() {
        if (!isChangingConfigurations) EditLock.lockIfIdle()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra(GuardNotifications.EXTRA_ROUTE)
        if (intent.getBooleanExtra(GuardNotifications.EXTRA_START_FOCUS, false)) {
            pendingStartFocus = true
        }
        pairingUri(intent)?.let { pendingPairing = it }
    }

    private fun consumePairing() {
        pendingPairing = null
        val current = intent ?: return
        current.data = null
        if (current.action == Intent.ACTION_VIEW) current.action = Intent.ACTION_MAIN
    }

    private fun pairingUri(source: Intent?): String? {
        if (source?.action != Intent.ACTION_VIEW) return null
        val raw = source.dataString ?: return null
        if (raw.length > SyncRules.PAIRING_LINK_MAX) return null
        val data = source.data ?: return null
        val scheme = data.scheme?.lowercase() ?: return null
        val host = data.host?.lowercase() ?: return null
        if (scheme != "unscroll" || host != "pair") return null
        return raw
    }
}

private object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val HISTORY = "history"
    const val CATEGORIES = "categories"
    const val CATEGORY = "category/{categoryKey}"
    const val REPORT = "report"
    const val DETAIL = "detail/{packageName}"
    const val LIMITS = "limits"
    const val SCHEDULES = "schedules"
    const val SCHEDULE = "schedule/{scheduleId}"
    const val FOCUS = "focus"
    const val SLEEP = "sleep"
    const val FOCUS_ROUTINE = "focus-routine/{routineId}"
    const val WEBSITES = "websites"
    const val WEBSITE = "website/{domain}"
    const val REMINDERS = "reminders"
    const val ACHIEVEMENTS = "achievements"
    const val EXCLUSIONS = "exclusions"
    const val PRIVACY = "privacy"
    const val SYNC = "sync"
    const val ABOUT = "about"

    fun category(key: String) = "category/${Uri.encode(key)}"
    fun detail(packageName: String) = "detail/${Uri.encode(packageName)}"
    fun schedule(id: Long) = "schedule/$id"
    fun focusRoutine(id: Long) = "focus-routine/$id"
    fun website(domain: String) = "website/${Uri.encode(domain)}"
}

@Composable
private fun AppNav(
    start: String,
    pendingRoute: String?,
    pendingStartFocus: Boolean,
    pendingPairing: String?,
    onRouteConsumed: () -> Unit,
    onStartConsumed: () -> Unit,
    onPairingConsumed: () -> Unit,
) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val prefs = AppPrefs(context)
    val pairing = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingPairing) {
        pairing.value = pendingPairing
        if (pendingPairing != null && prefs.onboardingDone) {
            nav.navigate(Routes.SYNC) { launchSingleTop = true }
        }
    }
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        if (prefs.onboardingDone && route in setOf(
                Routes.FOCUS,
                Routes.LIMITS,
                Routes.SCHEDULES,
                Routes.SLEEP,
                Routes.WEBSITES,
                Routes.REMINDERS,
            )
        ) {
            nav.navigate(route) { launchSingleTop = true }
        }
        onRouteConsumed()
    }
    LaunchedEffect(pendingStartFocus) {
        if (!pendingStartFocus) return@LaunchedEffect
        if (prefs.onboardingDone) {
            runCatching { FocusSessions.startDraft(context) }
            nav.navigate(Routes.FOCUS) { launchSingleTop = true }
        }
        onStartConsumed()
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabRoutes = setOf(Routes.DASHBOARD, Routes.LIMITS, Routes.FOCUS, Routes.SETTINGS)
    val showTabs = prefs.onboardingDone && route in tabRoutes

    Scaffold(
        bottomBar = {
            if (showTabs) NavigationBar {
                NavigationBarItem(
                    selected = route == Routes.DASHBOARD,
                    onClick = {
                        nav.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.DASHBOARD) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_home)) },
                )
                NavigationBarItem(
                    selected = route == Routes.LIMITS,
                    onClick = {
                        nav.navigate(Routes.LIMITS) {
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_limits)) },
                )
                NavigationBarItem(
                    selected = route == Routes.FOCUS,
                    onClick = {
                        nav.navigate(Routes.FOCUS) {
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_focus)) },
                )
                NavigationBarItem(
                    selected = route == Routes.SETTINGS,
                    onClick = {
                        nav.navigate(Routes.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_more)) },
                )
            }
        },
    ) { padding ->
    NavHost(
        navController = nav,
        startDestination = start,
        modifier = Modifier.padding(padding),
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    prefs.onboardingDone = true
                    nav.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                    if (pairing.value != null) {
                        nav.navigate(Routes.SYNC) { launchSingleTop = true }
                    }
                },
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) },
                onOpenCategories = { nav.navigate(Routes.CATEGORIES) },
                onOpenReport = { nav.navigate(Routes.REPORT) },
                onOpenLimits = { nav.navigate(Routes.LIMITS) },
                onOpenFocus = { nav.navigate(Routes.FOCUS) },
                onOpenSleep = { nav.navigate(Routes.SLEEP) },
                onOpenWebsites = { nav.navigate(Routes.WEBSITES) },
                onOpenAchievements = { nav.navigate(Routes.ACHIEVEMENTS) },
                onOpenApp = { packageName -> nav.navigate(Routes.detail(packageName)) },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { nav.popBackStack() },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.CATEGORIES) {
            CategoriesScreen(
                onBack = { nav.popBackStack() },
                onOpenCategory = { key -> nav.navigate(Routes.category(key)) },
                onOpenApp = { packageName -> nav.navigate(Routes.detail(packageName)) },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(
            route = Routes.CATEGORY,
            arguments = listOf(navArgument("categoryKey") { type = NavType.StringType }),
        ) {
            CategoryDetailScreen(
                onBack = { nav.popBackStack() },
                onOpenApp = { packageName -> nav.navigate(Routes.detail(packageName)) },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.REPORT) {
            WeeklyReportScreen(
                onBack = { nav.popBackStack() },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("packageName") { type = NavType.StringType }),
        ) {
            AppDetailScreen(
                onBack = { nav.popBackStack() },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    nav.navigate(Routes.DASHBOARD) {
                        launchSingleTop = true
                    }
                },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
                onOpenLimits = { nav.navigate(Routes.LIMITS) },
                onOpenSchedules = { nav.navigate(Routes.SCHEDULES) },
                onOpenSleep = { nav.navigate(Routes.SLEEP) },
                onOpenFocus = { nav.navigate(Routes.FOCUS) },
                onOpenWebsites = { nav.navigate(Routes.WEBSITES) },
                onOpenReminders = { nav.navigate(Routes.REMINDERS) },
                onOpenAchievements = { nav.navigate(Routes.ACHIEVEMENTS) },
                onOpenExclusions = { nav.navigate(Routes.EXCLUSIONS) },
                onOpenSync = { nav.navigate(Routes.SYNC) },
                onOpenPrivacy = { nav.navigate(Routes.PRIVACY) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onWiped = {
                    nav.navigate(Routes.ONBOARDING) {
                        popUpTo(nav.graph.id) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.EXCLUSIONS) {
            ExclusionsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.PRIVACY) {
            PrivacyScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SYNC) {
            SyncScreen(
                onBack = { nav.popBackStack() },
                pendingLink = pairing,
                onLinkConsumed = onPairingConsumed,
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ACHIEVEMENTS) {
            AchievementsScreen(
                onBack = { nav.popBackStack() },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.LIMITS) {
            LimitsScreen(
                onBack = {
                    nav.navigate(Routes.DASHBOARD) {
                        launchSingleTop = true
                    }
                },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
                onOpenSchedules = { nav.navigate(Routes.SCHEDULES) },
                onOpenSleep = { nav.navigate(Routes.SLEEP) },
                onOpenFocus = { nav.navigate(Routes.FOCUS) },
                onOpenWebsites = { nav.navigate(Routes.WEBSITES) },
            )
        }
        composable(Routes.WEBSITES) {
            WebsitesScreen(
                onBack = { nav.popBackStack() },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
                onOpenDomain = { domain -> nav.navigate(Routes.website(domain)) },
            )
        }
        composable(
            route = Routes.WEBSITE,
            arguments = listOf(navArgument("domain") { type = NavType.StringType }),
        ) {
            WebsiteDetailScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.REMINDERS) {
            RemindersScreen(
                onBack = { nav.popBackStack() },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.SCHEDULES) {
            SchedulesScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate(Routes.schedule(id)) },
                onCreate = { nav.navigate(Routes.schedule(0L)) },
            )
        }
        composable(
            route = Routes.SCHEDULE,
            arguments = listOf(navArgument("scheduleId") { type = NavType.LongType }),
        ) {
            ScheduleEditorScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.FOCUS) {
            FocusScreen(
                onBack = {
                    nav.navigate(Routes.DASHBOARD) {
                        launchSingleTop = true
                    }
                },
                onOpenRoutine = { id -> nav.navigate(Routes.focusRoutine(id)) },
                onCreateRoutine = { nav.navigate(Routes.focusRoutine(0L)) },
            )
        }
        composable(Routes.SLEEP) {
            SleepScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.FOCUS_ROUTINE,
            arguments = listOf(navArgument("routineId") { type = NavType.LongType }),
        ) {
            FocusRoutineScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.PERMISSIONS) {
            PermissionStatusScreen(onBack = { nav.popBackStack() })
        }
    }
    }
}
