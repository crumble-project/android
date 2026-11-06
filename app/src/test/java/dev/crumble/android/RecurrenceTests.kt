package dev.crumble.android

import dev.crumble.android.model.NextRun
import dev.crumble.android.model.Recurrence
import dev.crumble.android.model.TaskDraft
import dev.crumble.android.model.TaskWhen
import dev.crumble.android.model.choice
import dev.crumble.android.model.choosing
import dev.crumble.android.model.cronOf
import dev.crumble.android.model.humanRun
import dev.crumble.android.model.inTimezone
import dev.crumble.android.model.minuteChoices
import dev.crumble.android.model.nextRunOf
import dev.crumble.android.model.recurrenceOf
import dev.crumble.android.model.runningAt
import dev.crumble.android.model.timezoneChoices
import dev.crumble.android.model.wallClock
import dev.crumble.android.model.weekdayName
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceTests {
    private fun draft() = TaskDraft(scheduleID = "", agentID = "a1", timezone = "UTC")
    private val now = Instant.parse("2026-11-05T10:00:00Z")

    @Test
    fun cronReadsBackIntoThePicker() {
        assertEquals(Recurrence(TaskWhen.Hourly, minute = 5), recurrenceOf("5 * * * *"))
        assertEquals(Recurrence(TaskWhen.Daily, 30, 8), recurrenceOf(" 30   8 * * * "))
        assertEquals(Recurrence(TaskWhen.Weekdays, 0, 9), recurrenceOf("0 9 * * 1-5"))
        assertEquals(Recurrence(TaskWhen.Weekly, 0, 9, listOf(1, 3)), recurrenceOf("0 9 * * 1,3"))
        assertEquals(Recurrence(TaskWhen.Weekly, 0, 9, listOf(0)), recurrenceOf("0 9 * * 7,0"))
        assertEquals(Recurrence(TaskWhen.Monthly, 0, 9, day = 15), recurrenceOf("0 9 15 * *"))
    }

    @Test
    fun cronsThePickerCannotShowStayCustom() {
        for (cron in listOf("*/5 * * * *", "0 9 * 1 *", "60 * * * *", "0 24 * * *", "0 9 32 * *", "0 9 0 * *", "99999999999 * * * *", "")) {
            assertNull(cron, recurrenceOf(cron))
        }
    }

    @Test
    fun everyPickerStateRoundTrips() {
        val recurrences = listOf(
            Recurrence(TaskWhen.Hourly, minute = 10),
            Recurrence(TaskWhen.Daily, 5, 6),
            Recurrence(TaskWhen.Weekdays, 5, 6),
            Recurrence(TaskWhen.Weekly, 5, 6, listOf(0, 2)),
            Recurrence(TaskWhen.Monthly, 5, 6, day = 31),
        )
        for (recurrence in recurrences) assertEquals(recurrence, recurrenceOf(cronOf(recurrence)))
        assertEquals("5 6 * * 0,2,5", cronOf(Recurrence(TaskWhen.Weekly, 5, 6, listOf(5, 0, 2))))
    }

    @Test
    fun switchingKindKeepsTheTimeAndLeavesCustomAlone() {
        val daily = draft().copy(repeats = true, cron = "30 7 * * *")
        assertEquals("30 7 * * 1", daily.choosing(TaskWhen.Weekly).cron)
        assertEquals("30 7 1 * *", daily.choosing(TaskWhen.Monthly).cron)
        assertEquals("30 * * * *", daily.choosing(TaskWhen.Hourly).cron)
        assertEquals("0 9 * * 1-5", draft().copy(repeats = true, cron = "*/5 * * * *").choosing(TaskWhen.Weekdays).cron)

        val custom = daily.choosing(TaskWhen.Custom)
        assertEquals(TaskWhen.Custom, custom.choice)
        assertEquals("30 7 * * *", custom.cron)
        assertEquals(TaskWhen.Daily, custom.choosing(TaskWhen.Daily).choice)
        assertEquals(TaskWhen.Once, custom.choosing(TaskWhen.Once).choice)
        assertFalse(custom.choosing(TaskWhen.Once).customCron)
    }

    @Test
    fun aHandWrittenMinuteStaysSelectable() {
        assertEquals(12, minuteChoices(10).size)
        assertTrue(7 in minuteChoices(7))
    }

    @Test
    fun weekdayNamesCountSundayAsZero() {
        assertEquals("Sunday", weekdayName(0, TextStyle.FULL, Locale.US))
        assertEquals("Mon", weekdayName(1, TextStyle.SHORT, Locale.US))
    }

    @Test
    fun changingTheZoneKeepsTheChosenClockTime() {
        val picked = draft().runningAt(LocalDateTime.of(2026, 12, 1, 9, 0))
        val moved = picked.inTimezone("America/New_York")
        assertEquals(LocalDateTime.of(2026, 12, 1, 9, 0), moved.wallClock)
        assertEquals(Instant.parse("2026-12-01T14:00:00Z"), moved.runDate)
    }

    @Test
    fun timezoneChoicesSearchAndKeepTheCurrentZone() {
        val zones = setOf("Europe/Berlin", "America/New_York", "UTC", "Etc/GMT+1", "EST5EDT")
        assertEquals(listOf("America/New_York", "Europe/Berlin", "UTC"), timezoneChoices("", "UTC", zones))
        assertEquals(listOf("America/New_York"), timezoneChoices("new york", "UTC", zones))
        assertTrue("Mars/Base" in timezoneChoices("", "Mars/Base", zones))
    }

    @Test
    fun runsReadRelativeToTheTasksZone() {
        val zone = ZoneId.of("UTC")
        fun at(text: String) = humanRun(Instant.parse(text), zone, now, Locale.UK)
        assertEquals("today at 12:00", at("2026-11-05T12:00:00Z"))
        assertEquals("tomorrow at 09:00", at("2026-11-06T09:00:00Z"))
        assertEquals("Monday at 09:00", at("2026-11-09T09:00:00Z"))
        assertEquals("Mon, Nov 16 at 09:00", humanRun(Instant.parse("2026-11-16T09:00:00Z"), zone, now, Locale.US).replace(' ', ' ').replace(" AM", "").replace("9:00", "09:00"))
    }

    @Test
    fun theDayBoundaryIsTheTasksNotTheDevices() {
        assertEquals("tomorrow at 00:30", humanRun(Instant.parse("2026-11-05T15:30:00Z"), ZoneId.of("Asia/Tokyo"), now, Locale.UK))
    }

    @Test
    fun previewMatchesTheEditorStates() {
        val repeating = draft().copy(repeats = true, cron = "0 9 * * *")
        assertEquals(NextRun.At("Next run", "tomorrow at 09:00", "UTC"), nextRunOf(repeating, now, Locale.UK))
        assertEquals(NextRun.Never, nextRunOf(repeating.copy(cron = "0 0 31 2 *"), now))
        assertEquals(NextRun.Never, nextRunOf(repeating.copy(cron = "nonsense"), now))
        assertEquals(NextRun.Never, nextRunOf(repeating.copy(cron = ""), now))
        assertEquals(NextRun.Never, nextRunOf(repeating.copy(timezone = "Not/AZone"), now))
        assertEquals(NextRun.Unknown, nextRunOf(repeating.copy(cron = "H * * * *"), now))

        val once = draft().copy(runDate = Instant.parse("2026-11-05T12:00:00Z"))
        assertEquals(NextRun.At("Runs", "today at 12:00", "UTC"), nextRunOf(once, now, Locale.UK))
        assertEquals(NextRun.Passed, nextRunOf(once.copy(runDate = now.minusSeconds(60)), now))
    }
}
