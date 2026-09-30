package dev.doobs.act

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dev.doobs.R
import dev.doobs.logd
import dev.doobs.logw
import dev.doobs.parse.Command
import dev.doobs.parse.Moment
import java.time.Duration
import java.time.LocalTime

/**
 * Every command is handed to whichever app already owns that job — the clock app
 * for alarms, its timer for short reminders. doobs keeps no state of its own, so
 * there is nothing to survive reboots, no exact-alarm permission to beg for, and
 * the user edits everything in the UI they already know.
 */
object Actions {

    sealed interface Outcome {
        data class Done(val message: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun perform(context: Context, command: Command): Outcome {
        logd("performing $command")
        val (intent, message) = when (command) {
            is Command.SetAlarm -> alarm(context, command.at, command.label)
            is Command.Remind -> remind(context, command)
        }

        val handler = context.packageManager.resolveActivity(intent, 0)
        if (handler == null) {
            logw("nothing handles ${intent.action}")
            return Outcome.Failed(context.getString(R.string.err_no_clock_app))
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            logd("${intent.action} handed to ${handler.activityInfo?.packageName}")
            Outcome.Done(message)
        } catch (e: Exception) {
            logw("could not start ${intent.action}", e)
            Outcome.Failed(context.getString(R.string.err_no_clock_app))
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

    private fun remind(context: Context, command: Command.Remind): Pair<Intent, String> =
        when (val moment = command.moment) {
            // "in ten minutes" means a timer, not an alarm — and unlike an alarm it
            // still works said twice in a row.
            is Moment.After -> timer(context, moment.duration, command.text)
            is Moment.At -> alarm(context, moment.time, command.text)
        }

    private fun timer(context: Context, duration: Duration, label: String): Pair<Intent, String> {
        val seconds = duration.seconds.coerceIn(1, Int.MAX_VALUE.toLong())
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val message = context.getString(R.string.ok_timer, label, Times.describe(context, duration))
        return intent to message
    }
}
