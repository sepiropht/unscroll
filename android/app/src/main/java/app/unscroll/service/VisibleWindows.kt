package app.unscroll.service

/**
 * Ce qui reste visible à l’écran : volet d’écran partagé non focalisé, fenêtre flottante.
 * Le premier plan sert aux statistiques. Les autres fenêtres servent au blocage,
 * pour qu’un partage d’écran ou une image dans l’image ne laisse pas l’application limitée ouverte.
 */
data class AppWindow(
    val packageName: String,
    val focused: Boolean,
    val active: Boolean,
    val pictureInPicture: Boolean,
)

data class ScreenLayout(
    val foreground: String?,
    val exposed: List<String>,
    val pictureInPicture: Set<String>,
)

object VisibleWindows {
    fun layout(windows: List<AppWindow>, noise: (String) -> Boolean): ScreenLayout {
        val visible = windows.filter { it.packageName.isNotBlank() && !noise(it.packageName) }
        val foreground = visible.firstOrNull { it.focused && !it.pictureInPicture }?.packageName
            ?: visible.firstOrNull { it.active && !it.pictureInPicture }?.packageName
            ?: visible.firstOrNull { !it.pictureInPicture }?.packageName
        val pip = visible.filter { it.pictureInPicture }.map { it.packageName }.distinct()
        val exposed = (pip + visible.map { it.packageName }.filter { it != foreground }).distinct()
        return ScreenLayout(foreground, exposed, pip.toSet())
    }
}
