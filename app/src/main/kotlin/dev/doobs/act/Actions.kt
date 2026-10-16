package dev.doobs.act

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import dev.doobs.R
import dev.doobs.convert.Units
import dev.doobs.logd
import dev.doobs.logw
import dev.doobs.park.Locator
import dev.doobs.park.Parking
import dev.doobs.parse.Command
import dev.doobs.parse.Moment
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Commands are handed to whichever app already owns that job — the clock app
 * for alarms and timers, a maps app for navigation. doobs keeps no state of its
 * own except the parking spot, which nothing else will hold for us.
 *
 * [perform] is asynchronous because one command — finding out where you are —
 * genuinely is. Everything else answers immediately.
 */
object Actions {

    /** Both Calendar and Tasks claim CREATE_REMINDER; aiming avoids the chooser. */
    private const val CALENDAR = "com.google.android.calendar"

    /** Waze also takes google.navigation:, and a chooser is a poor answer to a spoken question. */
    private const val MAPS = "com.google.android.apps.maps"

    private const val TRANSLATE = "com.google.android.apps.translate"

    sealed interface Outcome {
        /**
         * Not finished. May arrive several times before a terminal outcome, each
         * with a better answer than the last — a spinner alone says "something is
         * happening", this says what.
         */
        data class Working(val message: String) : Outcome

        /** Handed off to another app; the sheet can get out of the way. */
        data class Done(val message: String) : Outcome

        /** An answer to read, with no app to go to. The sheet waits to be dismissed. */
        data class Shown(val message: String) : Outcome

        data class Failed(val message: String) : Outcome
    }

    fun perform(context: Context, command: Command, done: (Outcome) -> Unit) {
        logd("performing $command")
        when (command) {
            is Command.SetAlarm -> {
                val (intent, message) = alarm(context, command.at, command.label)
                done(launch(context, intent, message, R.string.err_no_clock_app))
            }

            is Command.Remind -> done(remind(context, command))

            Command.ParkHere -> parkHere(context, done)
            Command.FindCar -> done(findCar(context))
            Command.NewAlarm -> done(newAlarm(context))
            Command.Translate -> done(translate(context))
            is Command.Convert -> done(convert(context, command))
        }
    }

    private fun launch(context: Context, intent: Intent, message: String, whenMissing: Int): Outcome {
        val handler = context.packageManager.resolveActivity(intent, 0)
        if (handler == null) {
            logw("nothing handles ${intent.action} ${intent.data ?: ""}")
            return Outcome.Failed(context.getString(whenMissing))
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            logd("${intent.action} handed to ${handler.activityInfo?.packageName}")
            Outcome.Done(message)
        } catch (e: Exception) {
            logw("could not start ${intent.action}", e)
            Outcome.Failed(context.getString(whenMissing))
        }
    }

    private fun alarm(context: Context, at: LocalTime, label: String?): Pair<Intent, String> {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, at.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)

        val clock = Times.format(context, at)
        val message = if (label.isNullOrBlank()) {
            context.getString(R.string.ok_alarm, clock)
        } else {
            context.getString(R.string.ok_reminder, label, clock)
        }
        return intent to message
    }

    /**
     * Reminders are Google Tasks, via [Intent.ACTION_CREATE_REMINDER].
     *
     * Aimed at Calendar rather than Tasks: both register for the action, so an
     * unaimed intent raises a chooser, and Calendar's composer opens straight
     * onto its Task tab with the title, the account and the due time already
     * filled in. There is no EXTRA_SKIP_UI for this action the way there is for
     * alarms, so it always costs one Save tap — which doubles as a last look
     * at what doobs thought you said.
     */
    /**
     * No time was said, so don't invent one. ACTION_SET_ALARM with no hour and
     * no EXTRA_SKIP_UI is the documented way to ask the clock app to show its
     * own editor — the one place where handing over a dialog is the point
     * rather than a failure.
     */
    private fun newAlarm(context: Context): Outcome {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        return launch(context, intent, context.getString(R.string.ok_new_alarm), R.string.err_no_clock_app)
    }

    private fun remind(context: Context, command: Command.Remind): Outcome {
        val due = dueAt(command.moment, LocalDateTime.now())
        val task = Intent(Intent.ACTION_CREATE_REMINDER)
            .putExtra(Intent.EXTRA_TITLE, command.text)
            .putExtra(Intent.EXTRA_TIME, due.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())

        val message = context.getString(R.string.ok_task, command.text, Times.format(context, due.toLocalTime()))

        val viaCalendar = Intent(task).setPackage(CALENDAR)
        if (handled(context, viaCalendar)) {
            return launch(context, viaCalendar, message, R.string.err_no_tasks)
        }
        if (handled(context, task)) {
            return launch(context, task, message, R.string.err_no_tasks)
        }

        // No Calendar and no Tasks: fall back to the clock, which every phone has.
        logw("nothing handles CREATE_REMINDER; falling back to the clock app")
        val (intent, fallback) = when (val moment = command.moment) {
            is Moment.After -> timer(context, moment.duration, command.text)
            is Moment.At -> alarm(context, moment.time, command.text)
        }
        return launch(context, intent, fallback, R.string.err_no_clock_app)
    }

    /**
     * A task needs a real date, which an alarm never did — the clock app just
     * picks the next occurrence of a time. So a time already gone today means
     * tomorrow.
     */
    private fun dueAt(moment: Moment, now: LocalDateTime): LocalDateTime = when (moment) {
        is Moment.After -> now.plus(moment.duration)
        is Moment.At -> now.toLocalDate().atTime(moment.time)
            .let { if (it.isAfter(now)) it else it.plusDays(1) }
    }.withSecond(0).withNano(0)

    /**
     * No arguments: the app itself is the feature, and its camera and
     * conversation modes are things doobs could never express in a sentence.
     */
    private fun translate(context: Context): Outcome {
        val message = context.getString(R.string.ok_translate)
        context.packageManager.getLaunchIntentForPackage(TRANSLATE)
            ?.let { return launch(context, it, message, R.string.err_no_translate) }

        // Either absent or hidden from us by package-visibility filtering — the
        // two are indistinguishable from here, so don't claim to know which.
        logw("$TRANSLATE not visible (installed? check <queries>); falling back to the web")
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com"))
        return launch(context, web, message, R.string.err_no_translate)
    }

    /** The one command that answers rather than delegates, so nothing is launched. */
    private fun convert(context: Context, command: Command.Convert): Outcome {
        val result = Units.convert(command.amount, command.from, command.to)
            ?: return Outcome.Failed(context.getString(R.string.err_cannot_convert))
        return Outcome.Shown(
            context.getString(
                R.string.ok_convert,
                Units.format(command.amount),
                command.from.symbol,
                Units.format(result),
                command.to.symbol,
            ),
        )
    }

    private fun handled(context: Context, intent: Intent) =
        context.packageManager.resolveActivity(intent, 0) != null

    private fun timer(context: Context, duration: Duration, label: String): Pair<Intent, String> {
        val seconds = duration.seconds.coerceIn(1, Int.MAX_VALUE.toLong())
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val message = context.getString(R.string.ok_timer, label, Times.describe(context, duration))
        return intent to message
    }

    private fun parkHere(context: Context, done: (Outcome) -> Unit) {
        Locator(context).locate(
            onImproved = { location ->
                // Narrowing from ±100m to ±11m takes a second or two, and showing
                // the number makes that wait legible instead of just long.
                done(
                    Outcome.Working(
                        if (location.hasAccuracy()) {
                            context.getString(R.string.prompt_locating_within, location.accuracy.roundToInt())
                        } else {
                            context.getString(R.string.prompt_locating)
                        },
                    ),
                )
            },
        ) { location ->
            if (location == null) {
                logw("no location fix for a parking spot")
                done(Outcome.Failed(context.getString(R.string.err_no_location)))
                return@locate
            }
            Parking(context).save(location)
            logd("parked at ${location.latitude},${location.longitude} (±${location.accuracy}m)")
            val message = if (location.hasAccuracy()) {
                context.getString(R.string.ok_parked_accuracy, location.accuracy.roundToInt())
            } else {
                context.getString(R.string.ok_parked)
            }
            done(Outcome.Done(message))
        }
    }

    private fun findCar(context: Context): Outcome {
        val spot = Parking(context).recall()
            ?: return Outcome.Failed(context.getString(R.string.err_no_spot))

        // Double.toString is locale-independent, so the decimal point is always
        // a point — a comma here would silently truncate the coordinate.
        val point = "${spot.latitude},${spot.longitude}"
        val label = context.getString(R.string.map_pin_label)

        // Walking, because you are on foot looking for a car you left somewhere.
        // Aimed at Maps: Waze and friends also take google.navigation:, and an
        // unaimed intent stops at a chooser, which is a poor answer to a
        // question you asked out loud.
        val navigate = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$point&mode=w"))
        val toCar = context.getString(R.string.ok_to_car)
        Intent(navigate).setPackage(MAPS).takeIf { handled(context, it) }
            ?.let { return launch(context, it, toCar, R.string.err_no_maps) }
        if (handled(context, navigate)) return launch(context, navigate, toCar, R.string.err_no_maps)

        // Nothing offers navigation: drop a pin instead, which any maps app takes.
        val pin = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point(${Uri.encode(label)})"))
        val onMap = context.getString(R.string.ok_car_on_map)
        Intent(pin).setPackage(MAPS).takeIf { handled(context, it) }
            ?.let { return launch(context, it, onMap, R.string.err_no_maps) }
        return launch(context, pin, onMap, R.string.err_no_maps)
    }
}
