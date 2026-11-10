package app.unscroll.service

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleAlarmsTest {
    @Test
    fun exactWhileIdleBeforeAndroid12() {
        assertEquals(IdleAlarms.Kind.EXACT_WHILE_IDLE, IdleAlarms.kind(26, exactAlarmAllowed = false))
        assertEquals(IdleAlarms.Kind.EXACT_WHILE_IDLE, IdleAlarms.kind(30, exactAlarmAllowed = true))
    }

    @Test
    fun idleWhenExactAlarmsAreNotGranted() {
        assertEquals(IdleAlarms.Kind.WHILE_IDLE, IdleAlarms.kind(31, exactAlarmAllowed = false))
        assertEquals(IdleAlarms.Kind.WHILE_IDLE, IdleAlarms.kind(35, exactAlarmAllowed = false))
    }

    @Test
    fun exactWhileIdleWhenTheSystemAllowsIt() {
        assertEquals(IdleAlarms.Kind.EXACT_WHILE_IDLE, IdleAlarms.kind(34, exactAlarmAllowed = true))
    }
}
