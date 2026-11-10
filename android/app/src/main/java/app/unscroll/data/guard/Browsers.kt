package app.unscroll.data.guard

import android.net.Uri

/**
 * Barres d’adresse des navigateurs courants, par identifiant de vue public.
 * Plusieurs identifiants sont essayés : les navigateurs les déplacent d’une version à l’autre.
 */
object Browsers {
    private val ids: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("url_bar"),
        "com.chrome.beta" to listOf("url_bar"),
        "com.chrome.dev" to listOf("url_bar"),
        "com.chrome.canary" to listOf("url_bar"),
        "com.brave.browser" to listOf("url_bar"),
        "com.brave.browser_beta" to listOf("url_bar"),
        "com.microsoft.emmx" to listOf("url_bar"),
        "com.microsoft.emmx.beta" to listOf("url_bar"),
        "com.sec.android.app.sbrowser" to listOf(
            "location_bar_edit_text",
            "url_bar_text",
            "location_bar",
            "url_bar",
        ),
        "com.opera.browser" to listOf("url_field", "url_bar"),
        "com.opera.browser.beta" to listOf("url_field", "url_bar"),
        "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.fenix" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.focus" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
    )

    fun isBrowser(packageName: String): Boolean = ids.containsKey(packageName)

    fun addressBarViewIds(packageName: String): List<String> {
        val names = ids[packageName] ?: return emptyList()
        return names.map { short -> "$packageName:id/$short" }
    }
}

object Domains {
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim().trimEnd('/')
        if (text.any { it.isWhitespace() }) return null
        val hasScheme = text.startsWith("http://") || text.startsWith("https://")
        if (!hasScheme) {
            if (!text.contains('.')) return null
            text = "https://$text"
        }
        val host = runCatching { Uri.parse(text).host }.getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            ?.trim('.')
            ?: return null
        if (host.isBlank() || !host.contains('.') || host.any { it.isWhitespace() }) return null
        return host
    }

    /** Vrai si [host] est [rule], ou un sous-domaine de [rule]. */
    fun covers(host: String, rule: String): Boolean {
        if (host.isEmpty() || !rule.contains('.')) return false
        return host == rule || host.endsWith(".$rule")
    }

    /** Règle la plus précise parmi celles qui couvrent [host]. */
    fun bestMatch(host: String, rules: Iterable<String>): String? {
        var best: String? = null
        for (rule in rules) {
            if (!covers(host, rule)) continue
            val current = best
            if (current == null || rule.length > current.length) best = rule
        }
        return best
    }
}
