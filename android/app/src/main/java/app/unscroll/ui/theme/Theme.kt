package app.unscroll.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.unscroll.prefs.AppPrefs
import app.unscroll.prefs.PrefsBus

private val Teal = Color(0xFF0E6E6A)
private val TealBright = Color(0xFF7DDBD2)
private val Ink = Color(0xFF1A2322)
private val Amber = Color(0xFF8A5A00)
private val AmberBright = Color(0xFFF0B429)
private val Sand = Color(0xFFFFDDB3)
private val Paper = Color(0xFFF4F7F6)
private val PaperCard = Color(0xFFF7F6F2)
private val Night = Color(0xFF101616)
private val NightCard = Color(0xFF1B2423)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7EBE4),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Sand,
    onSecondaryContainer = Color(0xFF2C1700),
    tertiary = Color(0xFF3E5C4C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5E8DB),
    onTertiaryContainer = Color(0xFF0E1F16),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE3E8E6),
    onSurfaceVariant = Color(0xFF3E4A48),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = PaperCard,
    surfaceContainer = Color(0xFFEEF3F1),
    surfaceContainerHigh = Color(0xFFE6EEEB),
    surfaceContainerHighest = Color(0xFFDEE6E3),
    outline = Color(0xFF6E7C79),
    outlineVariant = Color(0xFFC5D0CC),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TealBright,
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF0E4F4C),
    onPrimaryContainer = Color(0xFFB7EBE4),
    secondary = AmberBright,
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Sand,
    tertiary = Color(0xFFB7CCBE),
    onTertiary = Color(0xFF1B3428),
    tertiaryContainer = Color(0xFF2A4638),
    onTertiaryContainer = Color(0xFFD5E8DB),
    background = Night,
    onBackground = Color(0xFFE3E8E6),
    surface = Night,
    onSurface = Color(0xFFE3E8E6),
    surfaceVariant = Color(0xFF2A3332),
    onSurfaceVariant = Color(0xFFC1CBC8),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF0B1111),
    surfaceContainerLow = NightCard,
    surfaceContainer = Color(0xFF222C2B),
    surfaceContainerHigh = Color(0xFF2C3635),
    surfaceContainerHighest = Color(0xFF384240),
    outline = Color(0xFF8B9693),
    outlineVariant = Color(0xFF3E4A48),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 42.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun UnscrollTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val revision by PrefsBus.revision.collectAsState()
    val themeMode = remember(revision) { AppPrefs(context).themeMode }
    val dynamicOn = remember(revision) { AppPrefs(context).dynamicColor }
    val dark = when (themeMode) {
        AppPrefs.THEME_LIGHT -> false
        AppPrefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val dynamic = dynamicOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic && !dark -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val window = activity.window
            window.decorView.setBackgroundColor(colors.background.toArgb())
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
