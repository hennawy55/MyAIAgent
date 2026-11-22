package com.example.myaiagent.core

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

data class ParsedReminder(val dateTime: LocalDateTime, val originalText: String)
sealed class ParseResult {
    data class Success(val reminder: ParsedReminder) : ParseResult()
    data class Failure(val reason: String) : ParseResult()
}

object ReminderParser {
    private val timeRegex = Regex("""(?<!\d)(?:(?:الساعة|الساعه)\s*)?(\d{1,2})(?::(\d{2})|\s*(ونص|ونصف|وربع))?\s*(صباحا|الصبح|صباح|مساء|بالليل|ليل|am|pm)?(?!\d)""")
    private val dateYmd = Regex("""(?<!\d)(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")
    private val dateDmy = Regex("""(?<!\d)(\d{1,2})[-/.](\d{1,2})[-/.](20\d{2})(?!\d)""")
    private val dateDm = Regex("""(?<!\d)(\d{1,2})[-/.](\d{1,2})(?!\d)""")

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): ParseResult {
        return try {
            if (input.isBlank()) return ParseResult.Failure("اكتب نص الرسالة أولًا.")
            val s = normalize(input)
            val explicitDate = parseDate(s, now.toLocalDate())
            val hasDateToken = dateYmd.containsMatchIn(s) || dateDmy.containsMatchIn(s) || dateDm.containsMatchIn(s)
            if (hasDateToken && explicitDate == null) {
                return ParseResult.Failure("صيغة التاريخ غير صحيحة. استخدم يوم/شهر/سنة أو سنة-شهر-يوم.")
            }
            val withoutDate = dateYmd.replace(s, " ")
                .let { dateDmy.replace(it, " ") }
                .let { dateDm.replace(it, " ") }
            val match = timeRegex.find(withoutDate)
                ?: return ParseResult.Failure("لم أجد وقتًا واضحًا. مثال: الساعة 8:30 مساءً.")
            val rawHour = match.groupValues[1].toIntOrNull()
                ?: return ParseResult.Failure("صيغة الساعة غير صحيحة.")
            val minute = when {
                match.groupValues[2].isNotBlank() -> match.groupValues[2].toIntOrNull() ?: -1
                match.groupValues[3] in listOf("ونص", "ونصف") -> 30
                match.groupValues[3] == "وربع" -> 15
                else -> 0
            }
            val meridiem = match.groupValues[4]
            if (rawHour !in 0..23 || minute !in 0..59)
                return ParseResult.Failure("الساعة أو الدقائق خارج النطاق الصحيح.")

            var hour = rawHour
            when (meridiem) {
                "صباحا", "الصبح", "صباح", "am" -> {
                    if (hour == 12) hour = 0
                    else if (hour !in 0..11) return ParseResult.Failure("وقت الصباح يجب أن يكون من 1 إلى 12.")
                }
                "مساء", "بالليل", "ليل", "pm" -> {
                    if (hour in 1..11) hour += 12
                    else if (hour != 12) return ParseResult.Failure("وقت المساء يجب أن يكون من 1 إلى 12.")
                }
                else -> if (hour in 1..12)
                    return ParseResult.Failure("الساعة $rawHour غير محددة صباحًا أم مساءً.")
            }

            val date = explicitDate ?: relativeDate(s, now.toLocalDate())
                ?: if (hour >= 13 || hour == 0) {
                    val candidate = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))
                    if (candidate.isAfter(now)) now.toLocalDate() else now.toLocalDate().plusDays(1)
                } else return ParseResult.Failure("حدد اليوم أو التاريخ، مثل: بكرة الساعة 8 مساءً.")

            val target = LocalDateTime.of(date, LocalTime.of(hour, minute))
            if (!target.isAfter(now))
                return ParseResult.Failure("الموعد المحدد أصبح في الماضي. غيّر التاريخ أو الوقت.")
            ParseResult.Success(ParsedReminder(target, input.trim()))
        } catch (_: Exception) {
            ParseResult.Failure("تعذر فهم الرسالة. راجع صيغة التاريخ والوقت وحاول مرة أخرى.")
        }
    }

    private fun normalize(value: String): String {
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        val persian = "۰۱۲۳۴۵۶۷۸۹"
        val digits = value.map { c ->
            val a = arabic.indexOf(c)
            val p = persian.indexOf(c)
            when {
                a >= 0 -> ('0'.code + a).toChar()
                p >= 0 -> ('0'.code + p).toChar()
                else -> c
            }
        }.joinToString("")
        return Normalizer.normalize(digits, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace("ـ", "")
            .replace('،', ' ')
            .replace('٫', ':')
            .replace("مساءً", "مساء")
            .replace("مساءا", "مساء")
            .replace("صباحًا", "صباحا")
            .replace("صباحاً", "صباحا")
    }

    private fun parseDate(s: String, today: LocalDate): LocalDate? {
        dateYmd.find(s)?.let {
            return runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
        }
        dateDmy.find(s)?.let {
            return runCatching { LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
        }
        dateDm.find(s)?.let {
            return runCatching {
                var d = LocalDate.of(today.year, it.groupValues[2].toInt(), it.groupValues[1].toInt())
                if (d.isBefore(today)) d = d.plusYears(1)
                d
            }.getOrNull()
        }
        return null
    }

    private fun relativeDate(s: String, today: LocalDate): LocalDate? {
        if (listOf("بعد بكرة", "بعد بكره", "بعد غد", "بعد الغد").any(s::contains)) return today.plusDays(2)
        if (listOf("بكرة", "بكره", "غدا", "غداً", "غدًا").any(s::contains)) return today.plusDays(1)
        if (listOf("النهارده", "انهارده", "اليوم").any(s::contains)) return today
        val weekdays = listOf(
            listOf("الاثنين", "الإثنين") to 1,
            listOf("التلات", "الثلاثاء") to 2,
            listOf("الأربعاء", "الاربعاء") to 3,
            listOf("الخميس") to 4,
            listOf("الجمعة") to 5,
            listOf("السبت") to 6,
            listOf("الأحد", "الاحد") to 7
        )
        weekdays.firstOrNull { pair -> pair.first.any(s::contains) }?.let { entry ->
            var delta = (entry.second - today.dayOfWeek.value + 7) % 7
            if (delta == 0) delta = 7
            return today.plusDays(delta.toLong())
        }
        return null
    }
}
