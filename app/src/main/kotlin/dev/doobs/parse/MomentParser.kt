package dev.doobs.parse

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Turns a time phrase into a [Moment]. Understands durations ("in 20 minutes",
 * "in half an hour"), clock times ("7:30", "seven thirty", "0730", "quarter to
 * eight", "noon") and am/pm hints ("in the morning", "tonight").
 *
 * A bare hour with no am/pm is read as the next time that hour comes around,
 * which is what people mean by "set an alarm for seven".
 */
object MomentParser {

    private val LEADING_PREPOSITION = Regex("^(?:for|at|on|by|of|in the|at the)\\s+")

    private val EXPLICIT_AM = Regex("\\bam\\b")
    private val EXPLICIT_PM = Regex("\\bpm\\b")
    private val VAGUE_AM = Regex("\\b(?:in the morning|this morning|morning)\\b")
    private val VAGUE_PM = Regex(
        "\\b(?:in the afternoon|this afternoon|afternoon" +
            "|in the evening|this evening|evening|tonight|at night|night)\\b",
    )

    private val FILLER = Regex(
        "\\b(?:the|a|an|about|around|roughly|please|today|tomorrow|tomorrows|sharp|exactly|next|from now)\\b",
    )

    private val DURATION = Regex("(\\d+|an|a)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b")

    /** "in 5" with no unit — always minutes, never "at 5 o'clock". */
    private val BARE_IN = Regex("^in\\s+(\\d{1,3})$")
    private val LEADING_IN = Regex("^in\\s+")

    private val PAST = Regex("^(?:(\\d{1,2})|quarter|half)\\s+past\\s+(\\d{1,2})\\b")
    private val TILL = Regex("^(?:(\\d{1,2})|quarter|half)\\s+(?:to|till|til|before)\\s+(\\d{1,2})\\b")
    // A dot counts as a separator too, now that normalize keeps decimal points:
    // "7.30" is a time here, while "1.5" stays a number for the converter.
    private val HH_MM = Regex("^(\\d{1,2})\\s*[:.]\\s*(\\d{2})\\b")
    private val HHMM = Regex("^([01]\\d|2[0-3])([0-5]\\d)\\b")
    private val HH_SP_MM = Regex("^(\\d{1,2})\\s+([0-5]\\d)\\b")
    private val HH = Regex("^(\\d{1,2})\\b")

    private const val MINUTES_PER_DAY = 24 * 60

    fun parse(raw: String, now: LocalDateTime): Moment? {
        var text = Words.digitize(Words.normalize(raw))
        if (text.isEmpty()) return null

        val meridiem = meridiemOf(text)
        text = stripMeridiem(text)
        text = text.replace(LEADING_PREPOSITION, "").trim()

        BARE_IN.find(text)?.let { return Moment.After(Duration.ofMinutes(it.groupValues[1].toLong())) }

        // Durations need "a"/"an" and the filler words intact, so try them first.
        // The unit word is mandatory, so clock times can't match here by accident.
        duration(text)?.let { return Moment.After(it) }

        text = text.replace(FILLER, " ").replace(Regex("\\s+"), " ").trim()
        text = text.replace(LEADING_PREPOSITION, "").replace(LEADING_IN, "").trim()
        if (text.isEmpty()) return null

        named(text)?.let { return Moment.At(it) }

        PAST.find(text)?.let { m ->
            val minute = m.groupValues[1].toIntOrNull() ?: if ("quarter" in m.value) 15 else 30
            return at(m.groupValues[2].toInt(), minute, meridiem, now)
        }
        TILL.find(text)?.let { m ->
            val before = m.groupValues[1].toIntOrNull() ?: if ("quarter" in m.value) 15 else 30
            if (before !in 1..59) return null
            val hour = m.groupValues[2].toInt()
            return at(if (hour == 0) 23 else hour - 1, 60 - before, meridiem, now)
        }
        HH_MM.find(text)?.let { return at(it.groupValues[1].toInt(), it.groupValues[2].toInt(), meridiem, now) }
        // "0730" / "1945" — saying it four digits at a time means 24-hour time.
        HHMM.find(text)?.let {
            return at(it.groupValues[1].toInt(), it.groupValues[2].toInt(), meridiem, now, literal = true)
        }
        HH_SP_MM.find(text)?.let { return at(it.groupValues[1].toInt(), it.groupValues[2].toInt(), meridiem, now) }
        HH.find(text)?.let { return at(it.groupValues[1].toInt(), 0, meridiem, now) }

        return null
    }

    private fun named(text: String): LocalTime? = when {
        text.startsWith("noon") || text.startsWith("midday") -> LocalTime.NOON
        text.startsWith("midnight") -> LocalTime.MIDNIGHT
        else -> null
    }

    private fun meridiemOf(text: String): Char? = when {
        EXPLICIT_AM.containsMatchIn(text) -> 'a'
        EXPLICIT_PM.containsMatchIn(text) -> 'p'
        VAGUE_AM.containsMatchIn(text) -> 'a'
        VAGUE_PM.containsMatchIn(text) -> 'p'
        else -> null
    }

    private fun stripMeridiem(text: String): String = text
        .replace(EXPLICIT_AM, " ")
        .replace(EXPLICIT_PM, " ")
        .replace(VAGUE_AM, " ")
        .replace(VAGUE_PM, " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun duration(text: String): Duration? {
        val expanded = text
            .replace("half an hour", "30 minutes")
            .replace("half a hour", "30 minutes")
            .replace("half hour", "30 minutes")
            .replace("half a minute", "30 seconds")
            .replace("hour and a half", "90 minutes")
            .replace("quarter of an hour", "15 minutes")

        var seconds = 0L
        var matched = false
        for (m in DURATION.findAll(expanded)) {
            val count = when (val raw = m.groupValues[1]) {
                "a", "an" -> 1L
                else -> raw.toLongOrNull() ?: continue
            }
            val unit = m.groupValues[2]
            seconds += when {
                unit.startsWith("sec") -> count
                unit.startsWith("min") -> count * 60
                else -> count * 3600
            }
            matched = true
        }
        return if (matched && seconds > 0) Duration.ofSeconds(seconds) else null
    }

    /** [literal] means the hour was already given in 24-hour form; don't second-guess it. */
    private fun at(
        rawHour: Int,
        minute: Int,
        meridiem: Char?,
        now: LocalDateTime,
        literal: Boolean = false,
    ): Moment.At? {
        if (minute !in 0..59 || rawHour !in 0..23) return null
        val hour = when {
            meridiem == 'a' -> rawHour % 12
            meridiem == 'p' -> (rawHour % 12) + 12
            literal || rawHour >= 13 || rawHour == 0 -> rawHour
            else -> soonest(rawHour % 12, minute, now)
        }
        return Moment.At(LocalTime.of(hour, minute))
    }

    /** Of `base` and `base + 12`, the one that comes round sooner. */
    private fun soonest(base: Int, minute: Int, now: LocalDateTime): Int {
        val nowMinutes = now.hour * 60 + now.minute
        fun minutesAhead(hour: Int): Int {
            val delta = (hour * 60 + minute) - nowMinutes
            return if (delta <= 0) delta + MINUTES_PER_DAY else delta
        }
        return if (minutesAhead(base) <= minutesAhead(base + 12)) base else base + 12
    }
}
