package app.unscroll.data.stats

/** Retire des applications des totaux, des heures et des sessions. Les déverrouillages restent. */
fun DaySlice.excluding(packageNames: Set<String>): DaySlice {
    if (packageNames.isEmpty()) return this
    val kept = packages.filter { it.packageName !in packageNames }
    if (kept.size == packages.size) return this
    val hourly = LongArray(24)
    for (pkg in kept) {
        pkg.hourly.forEachIndexed { index, value -> hourly[index] += value }
    }
    return copy(
        totalMillis = kept.sumOf { it.durationMillis },
        hourly = hourly.toList(),
        packages = kept,
        sessions = sessions.filter { it.packageName !in packageNames },
    )
}
