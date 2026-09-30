package dev.doobs.voice

import android.service.voice.VoiceInteractionService
import dev.doobs.logd

/**
 * Presence of this service is what makes doobs eligible for
 * Settings -> Apps -> Default apps -> Digital assistant app.
 *
 * There is no hotword here on purpose: doobs only listens when summoned.
 */
class DoobsVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        logd("voice interaction service ready")
    }
}
