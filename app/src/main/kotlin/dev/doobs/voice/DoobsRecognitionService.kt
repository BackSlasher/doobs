package dev.doobs.voice

import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.doobs.logw

/**
 * A stub, and deliberately so.
 *
 * The platform requires an assistant to name a [RecognitionService] inside its own
 * package (see `res/xml/interaction_service.xml`), but doobs does not implement
 * speech recognition — it asks the system recogniser for a transcript via
 * `SpeechRecognizer` like any other app. If something ever routes recognition
 * here, fail loudly rather than silently hanging.
 *
 * Do not pick doobs under Settings -> Voice input.
 */
class DoobsRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: android.content.Intent?, listener: Callback?) {
        logw("recognition requested from doobs, which has no recogniser of its own")
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
