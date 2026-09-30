package dev.doobs.parse

import java.time.LocalDateTime

/**
 * The whole grammar doobs understands, deliberately small:
 *
 *   set an alarm for 7:30
 *   wake me up at quarter past six
 *   remind me at 6 to take the bins out
 *   remind me to call the dentist at 9 am
 *   remind me in 20 minutes to check the oven
 *
 * Anything else comes back [Parsed.NotUnderstood] rather than being guessed at.
 */
object CommandParser {

    private val POLITENESS = Regex(
        "^(?:(?:hey|ok|okay|hi|yo)\\s+)?(?:doobs\\s+)?(?:please\\s+)?" +
            "(?:(?:can|could|would)\\s+you\\s+)?(?:please\\s+)?",
    )

    private val REMIND = listOf(
        Regex("^remind me\\s+(.+)$"),
        Regex("^(?:set|add|create|make|new)?\\s*(?:a|an)?\\s*reminders?\\s+(.+)$"),
    )

    private val ALARM = listOf(
        Regex("^(?:set|create|make|add|put|start|new)?\\s*(?:an|a|the)?\\s*alarms?\\s*(.*)$"),
        Regex("^wake me(?:\\s+up)?\\s*(.*)$"),
    )

    /** Words that can introduce a time phrase. "for" is left out: too many false hits in labels. */
    private val TIME_LEAD = Regex("\\b(?:at|in|on|by)\\b")
    private val SEPARATOR = " to "
    private val ALPHA = Regex("[a-z]")

    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): Parsed {
        val heard = Words.normalize(raw)
        if (heard.isEmpty()) return Parsed.NotUnderstood(heard)

        val text = heard.replaceFirst(POLITENESS, "").trim()

        for (pattern in REMIND) {
            val tail = pattern.find(text)?.groupValues?.get(1) ?: continue
            reminder(tail, now)?.let { return Parsed.Understood(it, heard) }
        }
        for (pattern in ALARM) {
            val tail = pattern.find(text)?.groupValues?.get(1) ?: continue
            alarm(tail, now)?.let { return Parsed.Understood(it, heard) }
        }
        return Parsed.NotUnderstood(heard)
    }

    private fun alarm(tail: String, now: LocalDateTime): Command.SetAlarm? {
        val phrase = tail.trim()
        if (phrase.isEmpty()) return null

        // "alarm for 6 to go running" — a labelled alarm. Tried first so the label
        // survives, but only when the label is words; "10 to 8" stays a clock time.
        splitAtSeparator(phrase, now)?.let { (moment, label) ->
            return Command.SetAlarm(moment.toAt(now).time, label)
        }
        val moment = MomentParser.parse(phrase, now) ?: return null
        return Command.SetAlarm(moment.toAt(now).time)
    }

    private fun reminder(tail: String, now: LocalDateTime): Command.Remind? {
        val phrase = tail.trim().removePrefix("that ").trim()

        // "to call the dentist at 9 am"
        if (phrase.startsWith("to ")) {
            return splitTrailingTime(phrase.removePrefix("to ").trim(), now)
                ?.let { (moment, text) -> Command.Remind(moment, text) }
        }

        // "at 6 to take the bins out"
        splitAtSeparator(phrase, now)?.let { (moment, text) -> return Command.Remind(moment, text) }

        // "buy milk at 5" — no "to" anywhere
        return splitTrailingTime(phrase, now)?.let { (moment, text) -> Command.Remind(moment, text) }
    }

    /**
     * Splits `<time> to <words>` on the first "to" whose left half is a real time
     * and whose right half is actually words.
     */
    private fun splitAtSeparator(phrase: String, now: LocalDateTime): Pair<Moment, String>? {
        var from = 0
        while (true) {
            val index = phrase.indexOf(SEPARATOR, from)
            if (index < 0) return null
            val left = phrase.substring(0, index).trim()
            val right = phrase.substring(index + SEPARATOR.length).trim()
            val moment = MomentParser.parse(left, now)
            // The right half has to be words, and must not be a time itself, or
            // "ten to eight pm" would split into 10:00 labelled "eight pm".
            if (moment != null &&
                right.isNotEmpty() &&
                ALPHA.containsMatchIn(right) &&
                MomentParser.parse(right, now) == null
            ) {
                return moment to right
            }
            from = index + 1
        }
    }

    /**
     * Splits `<words> at|in|on|by <time>`, preferring the rightmost time phrase so
     * that "look at the oven in 10 minutes" keeps its "at".
     */
    private fun splitTrailingTime(phrase: String, now: LocalDateTime): Pair<Moment, String>? {
        val leads = TIME_LEAD.findAll(phrase).map { it.range.first }.toList()
        for (start in leads.asReversed()) {
            val moment = MomentParser.parse(phrase.substring(start), now) ?: continue
            val text = phrase.substring(0, start).trim()
            if (text.isNotEmpty() && ALPHA.containsMatchIn(text)) return moment to text
        }
        return null
    }
}
