package app.unscroll.data.stats

/** Clés de [app.unscroll.data.db.UsageLimit]. */
object LimitKeys {
    fun app(packageName: String): String = "app:$packageName"

    fun category(categoryKey: String): String = "cat:$categoryKey"

    fun web(domain: String): String = "web:$domain"

    fun isWeb(targetKey: String): Boolean = targetKey.startsWith("web:")

    fun isCategory(targetKey: String): Boolean = targetKey.startsWith("cat:")

    fun payload(targetKey: String): String = targetKey.substringAfter(':')

    const val PERMANENT_UNTIL: Long = Long.MAX_VALUE
    const val EXTRA_MILLIS: Long = 5L * 60L * 1000L
}
