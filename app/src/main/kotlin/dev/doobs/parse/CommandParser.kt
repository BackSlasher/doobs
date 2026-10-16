package dev.doobs.parse

import dev.doobs.convert.Units
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

    /**
     * Parking has no arguments, so these match whole utterances rather than
     * capturing anything. Kept as a list of plain alternatives instead of one
     * heroic regex, because every one of them is a phrasing someone actually
     * says and they should stay individually readable.
     */
    private val PARKED = listOf(
        Regex("^(?:i\\s+)?(?:just\\s+)?parked(?:\\s+(?:here|my\\s+car|the\\s+car))?$"),
        Regex("^(?:i'?m|im)\\s+parked(?:\\s+here)?$"),
        Regex("^this\\s+is\\s+where\\s+i\\s+parked$"),
        Regex("^(?:remember|save|mark|note|store)\\s+(?:where\\s+i\\s+parked|my\\s+(?:car|parking(?:\\s+spot)?)|the\\s+car|parking(?:\\s+spot)?)$"),
        Regex("^park(?:ing)?\\s+spot$"),
    )

    private val FIND_CAR = listOf(
        Regex("^(?:take|get|bring|lead|guide|walk)\\s+me\\s+(?:back\\s+)?to\\s+(?:my\\s+|the\\s+)?car$"),
        Regex("^(?:navigate|directions?|take\\s+me)\\s+to\\s+(?:my\\s+|the\\s+)?car$"),
        Regex("^where(?:'?s|\\s+is)\\s+(?:my\\s+|the\\s+)?car$"),
        Regex("^where\\s+did\\s+i\\s+park(?:\\s+(?:my\\s+car|the\\s+car))?$"),
        Regex("^where\\s+(?:am\\s+i|i'?m)\\s+parked$"),
        Regex("^find\\s+(?:my\\s+|the\\s+)?car$"),
    )

    /**
     * "convert 11 cm to inches", "11 cm in inches", "what's 1.5 kg in pounds".
     * These are matched before the time grammars but only *accepted* when both
     * units resolve, so "remind me at 6 to take the bins out" can never be
     * mistaken for a conversion.
     */
    private val CONVERT = listOf(
        Regex("^convert\\s+(.+?)\\s+(?:to|into|in)\\s+(.+)$"),
        Regex("^(?:what(?:'s| is)\\s+)?(\\d.*?)\\s+(?:to|into|in)\\s+(.+)$"),
    )

    /** Reversed: "how many inches in 11 cm". */
    private val CONVERT_REVERSED = listOf(
        Regex("^how\\s+many\\s+(.+?)\\s+(?:in|is|are|per)\\s+(.+)$"),
        Regex("^how\\s+much\\s+is\\s+(.+?)\\s+in\\s+(.+)$"),
    )

    private val AMOUNT = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*(.+)$")

    private val TRANSLATE = listOf(
        Regex("^(?:open\\s+|start\\s+|launch\\s+)?(?:google\\s+)?translate$"),
        Regex("^(?:open\\s+|start\\s+|launch\\s+)?(?:google\\s+)?translator$"),
    )

    /** Words that can introduce a time phrase. "for" is left out: too many false hits in labels. */
    private val TIME_LEAD = Regex("\\b(?:at|in|on|by)\\b")
    private val SEPARATOR = " to "
    private val ALPHA = Regex("[a-z]")

    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): Parsed {
        val heard = Words.normalize(raw)
        if (heard.isEmpty()) return Parsed.NotUnderstood(heard)

        val text = heard.replaceFirst(POLITENESS, "").trim()

        // Argument-free, and matched whole, so they can't be confused with
        // anything the time-bearing grammars below would accept.
        if (PARKED.any { it.matches(text) }) return Parsed.Understood(Command.ParkHere, heard)
        if (FIND_CAR.any { it.matches(text) }) return Parsed.Understood(Command.FindCar, heard)
        if (TRANSLATE.any { it.matches(text) }) return Parsed.Understood(Command.Translate, heard)
        conversion(text)?.let { return Parsed.Understood(it, heard) }

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

    private fun conversion(text: String): Command.Convert? {
        for (pattern in CONVERT) {
            val match = pattern.find(text) ?: continue
            build(match.groupValues[1], match.groupValues[2])?.let { return it }
        }
        for (pattern in CONVERT_REVERSED) {
            val match = pattern.find(text) ?: continue
            build(match.groupValues[2], match.groupValues[1])?.let { return it }
        }
        return null
    }

    /** Returns null unless this really is a conversion, so other grammars still get a turn. */
    private fun build(amountAndUnit: String, target: String): Command.Convert? {
        val match = AMOUNT.find(Words.digitize(amountAndUnit.trim())) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val from = Units.find(match.groupValues[2]) ?: return null
        val to = Units.find(target) ?: return null
        if (from.family != to.family) return null
        return Command.Convert(amount, from, to)
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
