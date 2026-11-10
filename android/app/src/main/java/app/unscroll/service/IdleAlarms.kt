package app.unscroll.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build

/**
 * Réveil qui part aussi lorsque l’appareil est au repos (Doze).
 * L’alarme exacte n’est utilisée que si le système l’autorise : sinon l’alarme inexacte
 * reste admise en Doze, et le service d’accessibilité ou le travail périodique rattrapent le retard.
 */
internal object IdleAlarms {
    enum class Kind { EXACT_WHILE_IDLE, WHILE_IDLE }

    fun kind(sdkInt: Int, exactAlarmAllowed: Boolean): Kind {
        return if (sdkInt < 31 || exactAlarmAllowed) Kind.EXACT_WHILE_IDLE else Kind.WHILE_IDLE
    }

    fun schedule(alarm: AlarmManager, triggerAtMillis: Long, pending: PendingIntent) {
        val chosen = kind(Build.VERSION.SDK_INT, exactAllowed(alarm))
        try {
            arm(alarm, chosen, triggerAtMillis, pending)
        } catch (_: SecurityException) {
            val fallback = runCatching {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
            if (fallback.isFailure) {
                runCatching { alarm.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending) }
            }
        }
    }

    private fun arm(alarm: AlarmManager, kind: Kind, triggerAtMillis: Long, pending: PendingIntent) {
        when (kind) {
            Kind.EXACT_WHILE_IDLE ->
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            Kind.WHILE_IDLE ->
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    private fun exactAllowed(alarm: AlarmManager): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) alarm.canScheduleExactAlarms() else true
    }
}
