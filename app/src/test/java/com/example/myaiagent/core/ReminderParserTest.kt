package com.example.myaiagent.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ReminderParserTest {
    private val now = LocalDateTime.of(2026, 11, 22, 17, 0)

    @Test fun parsesEgyptianTomorrowEvening() {
        val result = ReminderParser.parse("فكرني بكرة الساعة 8 مساءً", now)
        assertTrue(result is ParseResult.Success)
        val dateTime = (result as ParseResult.Success).reminder.dateTime
        assertEquals(LocalDateTime.of(2026, 11, 23, 20, 0), dateTime)
    }

    @Test fun parsesArabicDigitsAndMinutes() {
        val result = ReminderParser.parse("ذكرني غدا الساعة ٨:٣٠ مساء", now)
        assertTrue(result is ParseResult.Success)
        assertEquals(LocalDateTime.of(2026, 11, 23, 20, 30), (result as ParseResult.Success).reminder.dateTime)
    }

    @Test fun parsesTwentyFourHourDate() {
        val result = ReminderParser.parse("اجتماع يوم 25/12/2026 الساعة 18:45", now)
        assertTrue(result is ParseResult.Success)
        assertEquals(LocalDateTime.of(2026, 12, 25, 18, 45), (result as ParseResult.Success).reminder.dateTime)
    }


    @Test fun parsesEgyptianHalfHourPhrase() {
        val result = ReminderParser.parse("فكرني بكرة الساعة 8 ونص مساء", now)
        assertTrue(result is ParseResult.Success)
        assertEquals(LocalDateTime.of(2026, 11, 23, 20, 30), (result as ParseResult.Success).reminder.dateTime)
    }

    @Test fun rejectsAmbiguousHourWithoutCrashing() {
        val result = ReminderParser.parse("فكرني بكرة الساعة 8", now)
        assertTrue(result is ParseResult.Failure)
    }

    @Test fun rejectsOrdinaryWordWithoutCrashing() {
        val result = ReminderParser.parse("اختبار", now)
        assertTrue(result is ParseResult.Failure)
    }

    @Test fun rejectsPastReminder() {
        val result = ReminderParser.parse("النهارده الساعة 4 مساء", now)
        assertTrue(result is ParseResult.Failure)
    }
}
