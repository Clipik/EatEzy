package app.eatezy.data

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** Defaults for the wake-up dialog; [source] is "button" (logged), "usual" (average) or "none". */
data class SleepPrompt(val bed: LocalDateTime?, val source: String)

/** A night still open for longer than this was forgotten, not slept. */
fun isFresh(e: SleepEntry, now: LocalDateTime = LocalDateTime.now()): Boolean =
    e.bed != null && Duration.between(e.bed, now).toHours() < 20

/** Newest nights first, the last [n] that have a bedtime / a wake-up. */
private fun recent(nights: List<SleepEntry>, n: Int) = nights.sortedByDescending { it.wake ?: it.bed }.take(n)

/** Usual bedtime: average clock time of the last 7 logged bedtimes; null when there are none. */
fun usualBed(nights: List<SleepEntry>): LocalTime? = averageClock(recent(nights.filter { it.bed != null }, 7).map { it.bed!!.toLocalTime() })

fun usualWake(nights: List<SleepEntry>): LocalTime? = averageClock(recent(nights.filter { it.wake != null }, 7).map { it.wake!!.toLocalTime() })

/** Nights that count for the reminders' times (with both ends). */
fun completeNights(nights: List<SleepEntry>) = nights.count { it.minutes != null }

/** The latest moment at [time] before [before] (a bedtime the night before a wake-up). */
fun bedBefore(time: LocalTime, before: LocalDateTime): LocalDateTime {
    val sameDay = before.toLocalDate().atTime(time)
    return if (sameDay.isBefore(before)) sameDay else sameDay.minusDays(1)
}

/** The moment at [time] closest to [now] (a time picked for "now-ish" events). */
fun nearest(time: LocalTime, now: LocalDateTime): LocalDateTime =
    listOf(-1L, 0L, 1L).map { now.toLocalDate().plusDays(it).atTime(time) }.minBy { Duration.between(it, now).abs() }

/** Evening reminder: 30 min before the usual bedtime once 3 nights are logged, else 22:30. */
fun eveningReminder(nights: List<SleepEntry>): LocalTime =
    usualBed(nights)?.takeIf { completeNights(nights) >= 3 }?.minusMinutes(30) ?: LocalTime.of(22, 30)

/** Morning reminder: 15 min after the usual wake-up once 3 nights are logged, else 08:15. */
fun morningReminder(nights: List<SleepEntry>): LocalTime =
    usualWake(nights)?.takeIf { completeNights(nights) >= 3 }?.plusMinutes(15) ?: LocalTime.of(8, 15)

/**
 * The latest day whose night is over. Before the usual wake-up (+3 h; noon when unknown) today's
 * night is still going, even when the user has not gone to bed yet: it is not a missed night.
 */
fun lastSleepDay(nights: List<SleepEntry>, now: LocalDateTime = LocalDateTime.now()): java.time.LocalDate {
    val today = now.toLocalDate()
    if (nights.any { it.date == today && it.wake != null }) return today
    val dayStarts = usualWake(nights)?.plusHours(3) ?: LocalTime.NOON
    return if (now.toLocalTime() >= dayStarts) today else today.minusDays(1)
}
