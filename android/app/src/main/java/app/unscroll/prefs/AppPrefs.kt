package app.unscroll.prefs

import android.content.Context
import app.unscroll.data.achieve.AchievementStore
import app.unscroll.data.repo.InstalledApps
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppPrefs(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDING, value).apply()

    /** Activé par défaut. Sans effet avant Android 12, où la palette du système est lue. */
    var dynamicColor: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC, true)
        set(value) {
            sp.edit().putBoolean(KEY_DYNAMIC, value).apply()
            PrefsBus.bump()
        }

    var themeMode: String
        get() = sp.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) {
            val stored = when (value) {
                THEME_LIGHT, THEME_DARK -> value
                else -> THEME_SYSTEM
            }
            sp.edit().putString(KEY_THEME, stored).apply()
            PrefsBus.bump()
        }

    /** Lundi par défaut. Dimanche décale la semaine civile du tableau de bord et du bilan. */
    var weekStartsMonday: Boolean
        get() = sp.getBoolean(KEY_WEEK_MONDAY, true)
        set(value) = sp.edit().putBoolean(KEY_WEEK_MONDAY, value).apply()

    fun weekStartDay(): DayOfWeek = if (weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY

    var hourFormat: String
        get() = when (val value = sp.getString(KEY_HOUR, HOUR_SYSTEM)) {
            HOUR_24, HOUR_12 -> value
            else -> HOUR_SYSTEM
        }
        set(value) {
            val stored = when (value) {
                HOUR_24, HOUR_12 -> value
                else -> HOUR_SYSTEM
            }
            sp.edit().putString(KEY_HOUR, stored).apply()
        }

    fun uses24Hour(context: Context): Boolean = when (hourFormat) {
        HOUR_24 -> true
        HOUR_12 -> false
        else -> android.text.format.DateFormat.is24HourFormat(context)
    }

    /** Vrai : les applications système restent dans les totaux. Faux : elles en sortent. */
    var showSystemApps: Boolean
        get() = sp.getBoolean(KEY_SHOW_SYSTEM, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_SYSTEM, value).apply()

    /** Exclusions explicites, plus les applications système lorsque le masque est actif. */
    fun hiddenFromStats(context: Context): Set<String> {
        val excluded = excludedPackages
        if (showSystemApps) return excluded
        return excluded + InstalledApps.systemPackages(context)
    }

    /** Applications retirées des totaux, des graphiques et des listes. */
    var excludedPackages: Set<String>
        get() = sp.getStringSet(KEY_EXCLUDED, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_EXCLUDED, value).apply()

    fun setPackageExcluded(packageName: String, excluded: Boolean) {
        val next = excludedPackages.toMutableSet()
        if (excluded) next += packageName else next -= packageName
        excludedPackages = next
    }

    /** Vrai lorsqu’une empreinte de code est enregistrée. */
    var pinEnabled: Boolean
        get() = sp.getBoolean(KEY_PIN_ENABLED, false)
        private set(value) = sp.edit().putBoolean(KEY_PIN_ENABLED, value).apply()

    fun hasPin(): Boolean = pinEnabled && pinHash.isNotBlank() && pinSalt.isNotBlank()

    /** 4 à 6 chiffres. L’empreinte PBKDF2 remplace le code. Le sel change à chaque enregistrement. */
    fun setPin(pin: String): Boolean {
        if (!PinHash.wellFormed(pin)) return false
        val salt = PinHash.newSalt()
        val hash = PinHash.pbkdf2(pin, salt) ?: return false
        sp.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_KDF, PinHash.KDF_PBKDF2)
            .putInt(KEY_PIN_ITERS, PinHash.ITERATIONS)
            .putBoolean(KEY_PIN_ENABLED, true)
            .apply()
        return true
    }

    fun clearPin() {
        sp.edit()
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_KDF)
            .remove(KEY_PIN_ITERS)
            .putBoolean(KEY_PIN_ENABLED, false)
            .apply()
    }

    fun matchesPin(pin: String): Boolean {
        val expected = pinHash
        val salt = pinSalt
        if (!pinEnabled || expected.isBlank() || salt.isBlank() || !PinHash.wellFormed(pin)) return false
        val kdf = sp.getString(KEY_PIN_KDF, null)
        if (kdf == PinHash.KDF_PBKDF2) {
            val iterations = sp.getInt(KEY_PIN_ITERS, PinHash.ITERATIONS)
            return PinHash.same(PinHash.pbkdf2(pin, salt, iterations), expected)
        }
        if (!PinHash.same(PinHash.legacySha256(pin, salt), expected)) return false
        val freshSalt = PinHash.newSalt()
        val fresh = PinHash.pbkdf2(pin, freshSalt) ?: return true
        sp.edit()
            .putString(KEY_PIN_SALT, freshSalt)
            .putString(KEY_PIN_HASH, fresh)
            .putString(KEY_PIN_KDF, PinHash.KDF_PBKDF2)
            .putInt(KEY_PIN_ITERS, PinHash.ITERATIONS)
            .apply()
        return true
    }

    fun wipeFile() {
        sp.edit().clear().commit()
        PrefsBus.bump()
    }

    var extraMinutesEnabled: Boolean
        get() = sp.getBoolean(KEY_EXTRA, true)
        set(value) = sp.edit().putBoolean(KEY_EXTRA, value).apply()

    var pauseUntilMillis: Long
        get() = sp.getLong(KEY_PAUSE_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_PAUSE_UNTIL, value).apply()

    var focusUntilMillis: Long
        get() = sp.getLong(KEY_FOCUS_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_FOCUS_UNTIL, value).apply()

    var focusStartedMillis: Long
        get() = sp.getLong(KEY_FOCUS_STARTED, 0L)
        set(value) = sp.edit().putLong(KEY_FOCUS_STARTED, value).apply()

    var focusPackages: Set<String>
        get() = sp.getStringSet(KEY_FOCUS_PACKAGES, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_FOCUS_PACKAGES, value).apply()

    var focusDomains: Set<String>
        get() = sp.getStringSet(KEY_FOCUS_DOMAINS, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_FOCUS_DOMAINS, value).apply()

    fun clearFocus() {
        sp.edit()
            .putLong(KEY_FOCUS_UNTIL, 0L)
            .putLong(KEY_FOCUS_STARTED, 0L)
            .putStringSet(KEY_FOCUS_PACKAGES, emptySet())
            .putStringSet(KEY_FOCUS_DOMAINS, emptySet())
            .apply()
        runCatching { AchievementStore.get(app).onManualCleared(System.currentTimeMillis()) }
    }

    /** Dernier créneau manuel, gardé pour la tuile des réglages rapides. */
    var focusDraftMinutes: Int
        get() = sp.getInt(KEY_DRAFT_MINUTES, 25).coerceIn(1, 180)
        set(value) = sp.edit().putInt(KEY_DRAFT_MINUTES, value.coerceIn(1, 180)).apply()

    var focusDraftPackages: Set<String>
        get() = sp.getStringSet(KEY_DRAFT_PACKAGES, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_DRAFT_PACKAGES, value.toSet()).apply()

    /** 0 = désactivé. Minutes d’usage continu de la même application avant une notification. */
    var usageReminderMinutes: Int
        get() = storedMinutes(sp.getInt(KEY_USAGE_REMIND, 0))
        set(value) = sp.edit().putInt(KEY_USAGE_REMIND, storedMinutes(value)).apply()

    /** 0 = désactivé. Minutes d’écran continu avant le rappel de pause des yeux. */
    var eyeBreakMinutes: Int
        get() = storedMinutes(sp.getInt(KEY_EYE, 0))
        set(value) = sp.edit().putInt(KEY_EYE, storedMinutes(value)).apply()

    /** -1 = désactivé. Minute de la journée (0–1439) pour le résumé. */
    var dailySummaryMinute: Int
        get() {
            val value = sp.getInt(KEY_SUMMARY_MINUTE, -1)
            return if (value == -1 || value in 0 until MINUTES_PER_DAY) value else -1
        }
        set(value) {
            val stored = if (value < 0) -1 else value.coerceIn(0, MINUTES_PER_DAY - 1)
            sp.edit().putInt(KEY_SUMMARY_MINUTE, stored).apply()
        }

    /** Début du jour local déjà résumé. commit pour éviter un second envoi. */
    var dailySummaryPostedDay: Long
        get() = sp.getLong(KEY_SUMMARY_DAY, 0L)
        set(value) {
            sp.edit().putLong(KEY_SUMMARY_DAY, value).commit()
        }

    fun routineSkips(now: Long): Map<Long, Long> {
        val raw = sp.getStringSet(KEY_ROUTINE_SKIPS, emptySet()) ?: emptySet()
        val live = LinkedHashMap<Long, Long>()
        for (item in raw) {
            val cut = item.indexOf(':')
            if (cut <= 0) continue
            val id = item.substring(0, cut).toLongOrNull() ?: continue
            val until = item.substring(cut + 1).toLongOrNull() ?: continue
            if (until > now) live[id] = until
        }
        return live
    }

    fun skipRoutine(id: Long, until: Long) {
        if (id == 0L) return
        val live = routineSkips(System.currentTimeMillis()).toMutableMap()
        live[id] = until
        val encoded = live.map { (key, value) -> "$key:$value" }.toSet()
        sp.edit().putStringSet(KEY_ROUTINE_SKIPS, encoded).apply()
        runCatching { AchievementStore.get(app).onRoutineSkipped(id, System.currentTimeMillis()) }
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val HOUR_SYSTEM = "system"
        const val HOUR_24 = "h24"
        const val HOUR_12 = "h12"

        private const val FILE = "temps_ecran"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_WEEK_MONDAY = "week_starts_monday"
        private const val KEY_HOUR = "hour_format"
        private const val KEY_SHOW_SYSTEM = "show_system_apps"
        private const val KEY_EXCLUDED = "excluded_packages"
        private const val KEY_PIN_ENABLED = "pin_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_KDF = "pin_kdf"
        private const val KEY_PIN_ITERS = "pin_iters"
        private const val KEY_EXTRA = "extra_minutes"
        private const val KEY_PAUSE_UNTIL = "pause_until"
        private const val KEY_FOCUS_UNTIL = "focus_until"
        private const val KEY_FOCUS_STARTED = "focus_started"
        private const val KEY_FOCUS_PACKAGES = "focus_packages"
        private const val KEY_FOCUS_DOMAINS = "focus_domains"
        private const val KEY_DRAFT_MINUTES = "focus_draft_minutes"
        private const val KEY_DRAFT_PACKAGES = "focus_draft_packages"
        private const val KEY_ROUTINE_SKIPS = "focus_routine_skips"
        private const val KEY_USAGE_REMIND = "usage_reminder_minutes"
        private const val KEY_EYE = "eye_break_minutes"
        private const val KEY_SUMMARY_MINUTE = "daily_summary_minute"
        private const val KEY_SUMMARY_DAY = "daily_summary_day"
        private const val MINUTES_PER_DAY = 24 * 60

        private fun storedMinutes(value: Int): Int = when {
            value == 0 -> 0
            value in 15..180 -> value
            else -> 0
        }

    }

    private val pinHash: String
        get() = sp.getString(KEY_PIN_HASH, "").orEmpty()

    private val pinSalt: String
        get() = sp.getString(KEY_PIN_SALT, "").orEmpty()
}

/** Vrai seulement lorsqu’un code a été défini. */
object PinGate {
    fun isEnabled(context: Context): Boolean = AppPrefs(context).hasPin()

    fun matches(context: Context, pin: String): Boolean = AppPrefs(context).matchesPin(pin)

    suspend fun check(context: Context, pin: String): Boolean =
        withContext(Dispatchers.Default) { matches(context, pin) }
}
