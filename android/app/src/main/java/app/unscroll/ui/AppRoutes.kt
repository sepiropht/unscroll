package app.unscroll.ui

import android.net.Uri

object AppRoutes {
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
    const val LIMIT = "limit/{targetKey}"
    const val SCHEDULES = "schedules"
    const val SCHEDULE = "schedule/{scheduleId}"
    const val SLEEP = "sleep"
    const val FOCUS = "focus"
    const val FOCUS_ROUTINE = "focus-routine/{routineId}"
    const val PAUSE = "pause"
    const val WEBSITES = "websites"
    const val WEBSITE = "website/{domain}"
    const val REMINDERS = "reminders"
    const val ACHIEVEMENTS = "achievements"
    const val EXCLUSIONS = "exclusions"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"
    const val SYNC = "sync"

    fun category(key: String) = "category/${Uri.encode(key)}"
    fun website(domain: String) = "website/${Uri.encode(domain)}"
    fun detail(packageName: String) = "detail/${Uri.encode(packageName)}"
    fun limit(targetKey: String) = "limit/${Uri.encode(targetKey)}"
    fun schedule(id: Long) = "schedule/$id"
    fun focusRoutine(id: Long) = "focus-routine/$id"
}
