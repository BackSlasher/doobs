package dev.doobs.parse

import dev.doobs.convert.Units
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

    /**
     * Remember where we are standing. The only command that stores anything —
     * see [dev.doobs.park.Parking] for why that exception is a small one.
     */
    data object ParkHere : Command

    /** Navigate back to whatever [ParkHere] remembered. */
    data object FindCar : Command

    /**
     * An alarm with no time said. Opens the clock app's own alarm editor rather
     * than guessing an hour — the one case where showing UI is the right answer.
     */
    data object NewAlarm : Command

    /** Just open Google Translate. No arguments — the app is the feature. */
    data object Translate : Command

    /**
     * The only command with an answer of its own rather than an app to hand to.
     * Worked out on the phone; see [dev.doobs.convert.Units].
     */
    data class Convert(
        val amount: Double,
        val from: Units.Unit,
        val to: Units.Unit,
    ) : Command
}

sealed interface Parsed {
    /** [heard] is the cleaned-up transcript, for showing back to the user. */
    data class Understood(val command: Command, val heard: String) : Parsed

    data class NotUnderstood(val heard: String) : Parsed
}
