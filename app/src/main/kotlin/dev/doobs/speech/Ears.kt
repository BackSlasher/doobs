package dev.doobs.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.doobs.R
import dev.doobs.logd
import dev.doobs.logw
import java.util.Locale

/**
 * Thin wrapper over the platform recogniser. Must be built and driven from the
 * main thread — [SpeechRecognizer] insists on it.
 */
class Ears(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onListening()
        fun onPartial(text: String)
        fun onHeard(text: String)
        /** [message] is already localised and safe to show. */
        fun onTrouble(message: String)
    }

    /** One way of asking. Tried in order until the recogniser stops complaining. */
    private data class Attempt(val language: String?, val offline: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var attempts: List<Attempt> = emptyList()
    private var attempt = 0
    private var gotResult = false
    private var sawLanguageProblem = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen() {
        if (!available) {
            // Was silent once, which made a dead recogniser look identical to a
            // phone that had simply locked itself. Never again.
            logw("no speech recogniser available on this device")
            listener.onTrouble(context.getString(R.string.err_no_recognizer))
            return
        }
        attempts = planAttempts()
        attempt = 0
        sawLanguageProblem = false
        start()
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    /**
     * Locales carry things a recogniser cannot parse: this phone's default is
     * `en-IL-u-mu-celsius`, a Unicode extension saying "show temperatures in
     * Celsius", which was being handed over as if it were a language and
     * rejected with ERROR_LANGUAGE_NOT_SUPPORTED. So: strip the extensions,
     * then widen — region-specific tag, bare language, the recogniser's own
     * default — and finally allow the network, since a missing offline model
     * is the likeliest reason a language is unavailable.
     */
    private fun planAttempts(): List<Attempt> {
        val locale = Locale.getDefault().stripExtensions()
        val languages: List<String?> = buildList {
            locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" }?.let { add(it) }
            locale.language.takeIf { it.isNotBlank() }?.let { add(it) }
            add(null) // whatever the recogniser considers its own default
        }.distinct()

        // Everything offline first — fast and private — then the same ladder
        // with the network allowed, because a missing on-device language pack
        // is the usual reason offline fails.
        return listOf(true, false).flatMap { offline ->
            languages.map { Attempt(it, offline) }
        }
    }

    private fun start() {
        val current = attempts.getOrNull(attempt) ?: return
        stop()
        gotResult = false
        logd("listening (language=${current.language ?: "recogniser default"}, offline=${current.offline})")

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { this.recognizer = it }
        recognizer.setRecognitionListener(Callbacks())
        recognizer.startListening(request(current))
    }

    private fun request(current: Attempt) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Offline when the device can manage it; the commands are short and local.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, current.offline)
        current.language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
    }

    private inner class Callbacks : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onListening()

        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let(listener::onPartial)
        }

        override fun onResults(results: Bundle?) {
            val text = best(results)
            if (text.isNullOrBlank()) {
                fail(SpeechRecognizer.ERROR_NO_MATCH)
            } else {
                gotResult = true
                listener.onHeard(text)
            }
        }

        override fun onError(error: Int) {
            if (gotResult) return // a late error after a good result is noise

            if (error.isAboutSetup()) {
                if (error.isAboutLanguage()) sawLanguageProblem = true
                if (attempt + 1 < attempts.size) {
                    logw("recognizer rejected ${attempts[attempt]} (error $error); widening")
                    attempt++
                    // Don't tear down the recogniser from inside its own callback.
                    main.post { start() }
                    return
                }
            }
            fail(error)
        }

        /**
         * "This configuration cannot work", as opposed to "you did not speak".
         * Worth retrying with a different language or transport — and note that
         * a dead offline recogniser reports ERROR_SERVER_DISCONNECTED, not a
         * language error, which once made the ladder stop one rung early.
         */
        private fun Int.isAboutSetup() = isAboutLanguage() ||
            this == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
            this == SpeechRecognizer.ERROR_SERVER ||
            this == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT

        private fun Int.isAboutLanguage() =
            this == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                this == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

        private fun fail(error: Int) {
            logw("recognizer error $error, after ${attempt + 1} of ${attempts.size} attempts")
            // A chain that died of missing language packs ends on whatever the
            // last rung complained about — usually the network. Say the thing
            // the user can actually act on instead.
            val message = if (sawLanguageProblem && error.isAboutSetup()) {
                R.string.err_language
            } else {
                messageFor(error)
            }
            listener.onTrouble(context.getString(message))
        }

        private fun messageFor(error: Int) = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> R.string.err_heard_nothing

            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            -> R.string.err_audio

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            -> R.string.err_network

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            -> R.string.err_language

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.err_no_mic_permission

            else -> R.string.err_unknown
        }

        private fun best(bundle: Bundle?): String? = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()

        override fun onBeginningOfSpeech() = Unit
        override fun onEndOfSpeech() = logd("end of speech")
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
