package dev.doobs.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import dev.doobs.AssistActivity
import dev.doobs.logd

/**
 * The assist gesture opens a session rather than an activity, so the session
 * immediately hands off to [AssistActivity] and closes. One UI, one code path,
 * whether doobs was reached by gesture, icon or ACTION_ASSIST.
 */
class DoobsSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        logd("session shown (flags=$showFlags), handing off to the assist UI")

        context.startActivity(
            Intent(context, AssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        hide()
    }

    /** Nothing to show of our own — suppress the default empty session window. */
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(false)
    }
}
