package dev.doobs.parse

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** When something should happen. */
sealed interface Moment {
    /** A wall-clock time, already disambiguated to 24h. */
    data class At(val time: LocalTime) : Moment

    /** A delay from now. */
    data class After(val duration: Duration) : Moment

    fun toAt(now: LocalDateTime): At = when (this) {
        is At -> this
        is After -> At(now.plus(duration).toLocalTime().withSecond(0).withNano(0))
    }
}

sealed interface Command {
    /** Alarms always land on a wall-clock time; the clock app owns them from there. */
    data class SetAlarm(val at: LocalTime, val label: String? = null) : Command

    /**
     * A reminder. An absolute [moment] becomes a labelled alarm; a relative one
     * becomes a labelled timer, which is what people actually mean by
     * "remind me in ten minutes".
     */
    data class Remind(val moment: Moment, val text: String) : Command
}

sealed interface Parsed {
    /** [heard] is the cleaned-up transcript, for showing back to the user. */
    data class Understood(val command: Command, val heard: String) : Parsed

    data class NotUnderstood(val heard: String) : Parsed
}
