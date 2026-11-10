package app.unscroll.data.stats

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventFolderTest {
    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 1, 15)
    private val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val next = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val folder = UsageEventFolder(
        zone,
        setOf("android", "com.android.systemui", "app.unscroll"),
    )

    @Test
    fun emptyRangeProducesNothing() {
        assertTrue(folder.fold(emptyList(), start, start, next).isEmpty())
    }

    @Test
    fun quietDayStillHasTwentyFourHours() {
        val slice = fold(emptyList()).single()
        assertEquals(day, slice.date)
        assertEquals(0L, slice.totalMillis)
        assertEquals(24, slice.hourly.size)
        assertTrue(slice.hourly.all { it == 0L })
        assertEquals(0, slice.unlockCount)
        assertTrue(slice.packages.isEmpty())
    }

    @Test
    fun foregroundSpanCountsTimeOpensAndHour() {
        val slice = fold(listOf(fg(at(10, 0)), bg(at(10, 5)))).single()
        assertEquals(5 * MIN, slice.totalMillis)
        assertEquals(5 * MIN, slice.hourly[10])
        assertConserved(slice)
        assertEquals(1, slice.packages.single().openCount)
        assertEquals("com.example.notes", slice.packages.single().packageName)
        val session = slice.sessions.single()
        assertEquals(at(10, 0), session.startMillis)
        assertEquals(at(10, 5), session.endMillis)
        assertEquals(2, slice.eventCount)
        assertEquals(0, slice.unlockCount)
    }

    @Test
    fun gapOfOneAndAHalfSecondsStaysOneSession() {
        val t0 = at(10, 0)
        val pause = t0 + MIN
        val back = pause + 1_500
        val stop = back + 30_000
        val slice = fold(listOf(fg(t0), bg(pause), fg(back), bg(stop))).single()
        assertEquals(stop - t0, slice.totalMillis)
        assertEquals(1, slice.packages.single().openCount)
        assertEquals(1, slice.sessions.size)
        assertConserved(slice)
    }

    @Test
    fun longerGapSplitsTheSessionAndCountsANewOpen() {
        val t0 = at(10, 0)
        val pause = t0 + MIN
        val back = pause + 1_501
        val stop = back + 30_000
        val slice = fold(listOf(fg(t0), bg(pause), fg(back), bg(stop))).single()
        assertEquals((pause - t0) + (stop - back), slice.totalMillis)
        assertEquals(2, slice.packages.single().openCount)
        assertEquals(pause, slice.sessions[0].endMillis)
        assertEquals(back, slice.sessions[1].startMillis)
        assertEquals(stop, slice.sessions[1].endMillis)
        assertConserved(slice)
    }

    @Test
    fun hourBoundarySplitsBarsButNotTheSession() {
        val slice = fold(listOf(fg(at(10, 50)), bg(at(11, 10)))).single()
        assertEquals(10 * MIN, slice.hourly[10])
        assertEquals(10 * MIN, slice.hourly[11])
        assertEquals(20 * MIN, slice.totalMillis)
        assertEquals(1, slice.sessions.size)
        assertConserved(slice)
    }

    @Test
    fun midnightSplitsTheSessionAcrossDays() {
        val from = at(23, 50)
        val to = at(0, 10, date = day.plusDays(1))
        val end = day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val slices = fold(listOf(fg(from), bg(to)), rangeEnd = end, now = end)
        assertEquals(2, slices.size)
        val midnight = next
        assertEquals(10 * MIN, slices[0].totalMillis)
        assertEquals(10 * MIN, slices[0].hourly[23])
        assertEquals(1, slices[0].packages.single().openCount)
        assertEquals(midnight, slices[0].sessions.single().endMillis)
        assertEquals(day.plusDays(1), slices[1].date)
        assertEquals(10 * MIN, slices[1].totalMillis)
        assertEquals(10 * MIN, slices[1].hourly[0])
        assertEquals(0, slices[1].packages.single().openCount)
        assertEquals(midnight, slices[1].sessions.single().startMillis)
        assertEquals(to, slices[1].sessions.single().endMillis)
        slices.forEach(::assertConserved)
    }

    @Test
    fun unlocksCountOnlyInsideTheRange() {
        val slice = fold(
            listOf(
                FoldEvent(start - 1, FoldKind.UNLOCK, null),
                FoldEvent(at(9, 0), FoldKind.UNLOCK, null),
                FoldEvent(at(18, 0), FoldKind.UNLOCK, null),
            ),
        ).single()
        assertEquals(2, slice.unlockCount)
        assertEquals(0L, slice.totalMillis)
        assertEquals(2, slice.eventCount)
    }

    @Test
    fun screenOffStopsTheClock() {
        val slice = fold(
            listOf(fg(at(10, 0)), FoldEvent(at(10, 4), FoldKind.SCREEN_OFF, null)),
        ).single()
        assertEquals(4 * MIN, slice.totalMillis)
        assertEquals(1, slice.packages.single().openCount)
        assertConserved(slice)
    }

    @Test
    fun lockHoldsUntilUnlockEvenIfTheScreenWakes() {
        val slice = fold(
            listOf(
                fg(at(10, 0)),
                FoldEvent(at(10, 2), FoldKind.LOCK, null),
                FoldEvent(at(10, 4), FoldKind.SCREEN_ON, null),
                FoldEvent(at(10, 6), FoldKind.UNLOCK, null),
                bg(at(10, 8)),
            ),
        ).single()
        assertEquals(4 * MIN, slice.totalMillis)
        assertEquals(1, slice.unlockCount)
        assertEquals(2, slice.packages.single().openCount)
        assertEquals(2, slice.sessions.size)
        assertConserved(slice)
    }

    @Test
    fun excludedPackagesDoNotCountAndEndThePreviousApp() {
        val slice = fold(
            listOf(
                fg(at(10, 0)),
                fg(at(10, 3), "android"),
                fg(at(10, 4), "com.android.systemui"),
                fg(at(10, 7)),
                bg(at(10, 9)),
            ),
        ).single()
        assertEquals(listOf("com.example.notes"), slice.packages.map { it.packageName })
        assertEquals(5 * MIN, slice.totalMillis)
        assertEquals(2, slice.packages.single().openCount)
        assertConserved(slice)
    }

    @Test
    fun packagesAreSortedByDuration() {
        val slice = fold(
            listOf(
                fg(at(10, 0), "com.a"),
                fg(at(10, 1), "com.b"),
                fg(at(10, 5), "com.c"),
                bg(at(10, 7), "com.c"),
            ),
        ).single()
        assertEquals(listOf("com.b", "com.c", "com.a"), slice.packages.map { it.packageName })
        assertEquals(listOf(4 * MIN, 2 * MIN, MIN), slice.packages.map { it.durationMillis })
        assertConserved(slice)
    }

    @Test
    fun openSessionIsCutAtNow() {
        val now = at(10, 10)
        val slice = fold(
            listOf(fg(at(10, 0)), bg(at(10, 30))),
            now = now,
        ).single()
        assertEquals(10 * MIN, slice.totalMillis)
        assertEquals(now, slice.sessions.single().endMillis)
        assertConserved(slice)
    }

    @Test
    fun carryInFromBeforeTheRangeDoesNotCountAsAnOpen() {
        val rangeStart = next
        val rangeEnd = next + 2 * 60 * MIN
        val slices = folder.fold(
            listOf(fg(next - 60 * MIN)),
            rangeStart,
            rangeEnd,
            rangeEnd,
        )
        val slice = slices.single { it.date == day.plusDays(1) }
        assertEquals(2 * 60 * MIN, slice.totalMillis)
        assertEquals(0, slice.packages.single().openCount)
        assertEquals(rangeStart, slice.sessions.single().startMillis)
        assertConserved(slice)
    }

    @Test
    fun shutdownEndsTheSession() {
        val slice = fold(
            listOf(fg(at(10, 0)), FoldEvent(at(10, 2), FoldKind.SHUTDOWN, null)),
        ).single()
        assertEquals(2 * MIN, slice.totalMillis)
        assertEquals(1, slice.sessions.size)
        assertConserved(slice)
    }

    private fun at(hour: Int, minute: Int, second: Int = 0, date: LocalDate = day): Long =
        date.atTime(hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    private fun fg(at: Long, pkg: String = "com.example.notes") = FoldEvent(at, FoldKind.FOREGROUND, pkg)

    private fun bg(at: Long, pkg: String = "com.example.notes") = FoldEvent(at, FoldKind.BACKGROUND, pkg)

    private fun fold(
        events: List<FoldEvent>,
        rangeStart: Long = start,
        rangeEnd: Long = next,
        now: Long = next,
    ) = folder.fold(events, rangeStart, rangeEnd, now)

    private fun assertConserved(slice: DaySlice) {
        assertEquals(24, slice.hourly.size)
        assertEquals(slice.totalMillis, slice.hourly.sum())
        assertEquals(slice.totalMillis, slice.packages.sumOf { it.durationMillis })
    }

    private companion object {
        const val MIN = 60_000L
    }
}

class HourlyCodecTest {
    @Test
    fun roundTripPadsToTwentyFourHours() {
        val values = List(24) { index -> if (index == 3) 12L else 0L }
        assertEquals(values, HourlyCodec.decode(HourlyCodec.encode(values)))
        assertEquals(values, HourlyCodec.decode(HourlyCodec.encode(listOf(0, 0, 0, 12))))
    }

    @Test
    fun blankOrBrokenCellsBecomeZero() {
        assertEquals(List(24) { 0L }, HourlyCodec.decode(""))
        assertEquals(List(24) { 0L }, HourlyCodec.decode("   "))
        val parsed = HourlyCodec.decode("4,nope")
        assertEquals(4L, parsed[0])
        assertEquals(0L, parsed[1])
        assertEquals(24, parsed.size)
    }
}
