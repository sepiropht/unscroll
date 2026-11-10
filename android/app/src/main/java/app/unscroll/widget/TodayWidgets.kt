package app.unscroll.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.unscroll.MainActivity
import app.unscroll.R
import app.unscroll.data.repo.UsageRepository
import app.unscroll.data.stats.excluding
import app.unscroll.permissions.PermissionChecks
import app.unscroll.ui.format.formatDuration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class WidgetApp(
    val label: String,
    val duration: String,
    val icon: Bitmap?,
)

data class WidgetModel(
    val title: String,
    val total: String,
    val unlocks: String,
    val message: String?,
    val apps: List<WidgetApp>,
    val emptyApps: String,
    val markLabel: String,
)

class TodayTimeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = loadModel(context)
        provideContent { SmallToday(model) }
    }
}

class TodayAppsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = loadModel(context)
        provideContent { MediumToday(model) }
    }
}

class TodayTimeReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayTimeWidget()
}

class TodayAppsReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayAppsWidget()
}

object HomeWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refreshAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { refresh(app) }
    }

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        refreshOne(app) { TodayTimeWidget() }
        refreshOne(app) { TodayAppsWidget() }
    }

    private suspend fun refreshOne(app: Context, widget: () -> GlanceAppWidget) {
        try {
            widget().updateAll(app)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }
}

private suspend fun loadModel(context: Context): WidgetModel {
    val app = context.applicationContext
    return try {
        readModel(app)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        failure(app)
    }
}

private suspend fun readModel(app: Context): WidgetModel {
    val title = app.getString(R.string.widget_title)
    val mark = app.getString(R.string.cd_app_mark)
    val empty = app.getString(R.string.widget_empty_apps)
    if (!PermissionChecks.hasUsageAccess(app)) {
        return WidgetModel(
            title = title,
            total = "",
            unlocks = "",
            message = app.getString(R.string.widget_need_access),
            apps = emptyList(),
            emptyApps = empty,
            markLabel = mark,
        )
    }
    val repo = UsageRepository(app)
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val day = repo.loadDays(today, today.plusDays(1))
        .firstOrNull()
        ?.excluding(repo.statsExclusions())
    val unlocks = day?.unlockCount ?: 0
    val apps = day?.packages.orEmpty()
        .filter { it.durationMillis > 0L }
        .sortedByDescending { it.durationMillis }
        .take(3)
        .map { pkg ->
            WidgetApp(
                label = repo.appLabel(pkg.packageName),
                duration = formatDuration(pkg.durationMillis, app),
                icon = appIcon(app, pkg.packageName),
            )
        }
    return WidgetModel(
        title = title,
        total = formatDuration(day?.totalMillis ?: 0L, app),
        unlocks = app.resources.getQuantityString(R.plurals.unlock_count, unlocks, unlocks),
        message = null,
        apps = apps,
        emptyApps = empty,
        markLabel = mark,
    )
}

private fun failure(app: Context) = WidgetModel(
    title = app.getString(R.string.widget_title),
    total = "",
    unlocks = "",
    message = app.getString(R.string.widget_unavailable),
    apps = emptyList(),
    emptyApps = app.getString(R.string.widget_empty_apps),
    markLabel = app.getString(R.string.cd_app_mark),
)

private fun appIcon(context: Context, packageName: String): Bitmap? {
    return runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        copy.toBitmap(width = 64, height = 64, config = Bitmap.Config.ARGB_8888)
    }.getOrNull()
}

private object Ink {
    val paper = ColorProvider(day = Color(0xFFF4F7F6), night = Color(0xFF101616))
    val muted = ColorProvider(day = Color(0xFF3E4A48), night = Color(0xFFC1CBC8))
    val teal = ColorProvider(day = Color(0xFF0E6E6A), night = Color(0xFF7DDBD2))
    val amber = ColorProvider(day = Color(0xFF8A5A00), night = Color(0xFFF0B429))
    val title = TextStyle(color = muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val total = TextStyle(color = teal, fontSize = 28.sp, fontWeight = FontWeight.Medium)
    val unlocks = TextStyle(color = amber, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(color = muted, fontSize = 13.sp)
    val appName = TextStyle(color = ColorProvider(day = Color(0xFF1A2322), night = Color(0xFFE3E8E6)), fontSize = 14.sp)
    val appTime = TextStyle(color = teal, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
@GlanceComposable
private fun widgetModifier(): GlanceModifier {
    val context = LocalContext.current
    return GlanceModifier
        .fillMaxSize()
        .background(Ink.paper)
        .cornerRadius(22.dp)
        .clickable(
            actionStartActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            ),
        )
        .padding(14.dp)
}

@Composable
@GlanceComposable
private fun Mark(label: String) {
    Image(
        provider = ImageProvider(R.drawable.ic_mark),
        contentDescription = label,
        modifier = GlanceModifier.size(22.dp),
    )
}

@Composable
@GlanceComposable
private fun SmallToday(model: WidgetModel) {
    Column(
        modifier = widgetModifier(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mark(model.markLabel)
            Spacer(GlanceModifier.width(8.dp))
            Text(model.title, style = Ink.title, maxLines = 1)
        }
        Spacer(GlanceModifier.height(6.dp))
        if (model.message != null) {
            Text(model.message, style = Ink.body, maxLines = 4)
        } else {
            Text(model.total, style = Ink.total, maxLines = 1)
            Text(model.unlocks, style = Ink.unlocks, maxLines = 1)
        }
    }
}

@Composable
@GlanceComposable
private fun MediumToday(model: WidgetModel) {
    Column(modifier = widgetModifier()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mark(model.markLabel)
            Spacer(GlanceModifier.width(8.dp))
            Text(model.title, style = Ink.title, maxLines = 1, modifier = GlanceModifier.defaultWeight())
            if (model.message == null) {
                Text(model.unlocks, style = Ink.unlocks, maxLines = 1)
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        if (model.message != null) {
            Text(model.message, style = Ink.body, maxLines = 4)
        } else {
            Text(model.total, style = Ink.total, maxLines = 1)
            Spacer(GlanceModifier.height(8.dp))
            if (model.apps.isEmpty()) {
                Text(model.emptyApps, style = Ink.body, maxLines = 2)
            } else {
                model.apps.forEach { app -> AppLine(app) }
            }
        }
    }
}

@Composable
@GlanceComposable
private fun AppLine(app: WidgetApp) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = app.label,
                modifier = GlanceModifier.size(18.dp),
            )
            Spacer(GlanceModifier.width(8.dp))
        }
        Text(app.label, style = Ink.appName, maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.width(8.dp))
        Text(app.duration, style = Ink.appTime, maxLines = 1)
    }
}
