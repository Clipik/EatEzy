package app.eatezy

import app.eatezy.data.SleepEntry
import app.eatezy.data.averageClock
import app.eatezy.data.bedBefore
import app.eatezy.data.eveningReminder
import app.eatezy.data.exportDiary
import app.eatezy.data.importDiary
import app.eatezy.data.morningReminder
import app.eatezy.data.nearest
import app.eatezy.data.Targets
import app.eatezy.data.usualBed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SleepTest {
    private fun night(day: Int, bed: String, wake: String): SleepEntry {
        val w = LocalDate.of(2026, 11, day).atTime(LocalTime.parse(wake))
        return SleepEntry(bed = bedBefore(LocalTime.parse(bed), w), wake = w)
    }

    @Test fun averagesAroundMidnight() {
        assertEquals(LocalTime.of(0, 0), averageClock(listOf(LocalTime.of(23, 30), LocalTime.of(0, 30))))
        assertEquals(LocalTime.of(7, 30), averageClock(listOf(LocalTime.of(7, 0), LocalTime.of(8, 0))))
    }

    @Test fun nightBelongsToWakeDay() {
        val n = night(28, "23:40", "07:55")
        assertEquals(LocalDate.of(2026, 11, 28), n.date)
        assertEquals(LocalDateTime.of(2026, 11, 27, 23, 40), n.bed)
        assertEquals(8 * 60 + 15L, n.minutes)
        // Still asleep: a night started in the evening belongs to the next morning.
        assertEquals(LocalDate.of(2026, 11, 28), SleepEntry(bed = LocalDateTime.of(2026, 11, 27, 23, 0), wake = null).date)
    }

    @Test fun usualBedIsLastSevenNights() {
        val old = (1..5).map { night(it, "21:00", "06:00") }
        val recent = (10..16).map { night(it, "23:00", "07:00") }
        assertEquals(LocalTime.of(23, 0), usualBed(old + recent))
    }

    @Test fun remindersFollowSleepAfterThreeNights() {
        assertEquals(LocalTime.of(22, 30), eveningReminder(emptyList()))
        assertEquals(LocalTime.of(8, 15), morningReminder(listOf(night(1, "00:00", "07:00"), night(2, "00:00", "07:00"))))
        val three = listOf(night(1, "00:00", "07:00"), night(2, "00:00", "07:00"), night(3, "00:00", "07:00"))
        assertEquals(LocalTime.of(23, 30), eveningReminder(three))
        assertEquals(LocalTime.of(7, 15), morningReminder(three))
    }

    @Test fun pickedTimesLandOnTheRightDay() {
        val now = LocalDateTime.of(2026, 11, 28, 23, 50)
        assertEquals(LocalDateTime.of(2026, 11, 29, 0, 20), nearest(LocalTime.of(0, 20), now))
        assertEquals(LocalDateTime.of(2026, 11, 28, 23, 10), nearest(LocalTime.of(23, 10), now))
    }

    @Test fun sleepSurvivesBackup() {
        val n = night(28, "23:40", "07:55")
        val back = importDiary(exportDiary(emptyList(), emptyList(), Targets(), listOf(n))).sleep
        assertEquals(listOf(n.copy(source = n.source)), back)
    }

    @Test fun reportHasSleep() {
        val n = night(27, "23:40", "07:55")
        val r = app.eatezy.data.aiReport(
            LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 27), emptyList(), emptyList(), emptyMap(),
            app.eatezy.data.Prefs(), ru = true, now = LocalDateTime.of(2026, 11, 27, 20, 0), sleep = listOf(n),
        )
        assertTrue(r, r.contains("Сон: 23:40 → 07:55 (8:15)"))
        assertTrue(r, r.contains("записано ночей 1"))
    }
}
