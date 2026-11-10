package app.unscroll.data.stats

import app.unscroll.data.db.Category

data class CategoryShare(
    val key: String,
    val label: String,
    val colorArgb: Int,
    val durationMillis: Long,
    val fraction: Float,
)

fun categoryShares(
    days: List<DaySlice>,
    assignments: Map<String, String>,
    categories: List<Category>,
): List<CategoryShare> {
    val known = categories.associateBy { it.key }
    val totals = linkedMapOf<String, Long>()
    for (day in days) {
        for (pkg in day.packages) {
            val raw = assignments[pkg.packageName] ?: "autre"
            val key = if (raw in known) raw else "autre"
            totals[key] = (totals[key] ?: 0L) + pkg.durationMillis
        }
    }
    val rows = totals.filterValues { it > 0L }.entries.sortedByDescending { it.value }
    val sum = rows.sumOf { it.value }
    if (sum <= 0L) return emptyList()
    return rows.map { (key, duration) ->
        val category = known[key]
        CategoryShare(
            key = key,
            label = category?.label ?: key,
            colorArgb = category?.colorArgb ?: AUTRE_COLOR,
            durationMillis = duration,
            fraction = duration.toFloat() / sum.toFloat(),
        )
    }
}

private val AUTRE_COLOR = 0xFF5C6B68.toInt()
