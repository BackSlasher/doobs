package dev.doobs.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.doobs.logd
import dev.doobs.logw
import java.util.concurrent.Executor

/**
 * On-device speech models.
 *
 * API 33 turned "will offline recognition work?" from a guess into a question
 * the platform will answer: which models are installed, which could be, and
 * please fetch one. Before this, [Ears] could only try an offline attempt and
 * read the wreckage — and on a stock Pixel that wreckage was
 * ERROR_LANGUAGE_NOT_SUPPORTED on every rung, because no pack was installed.
 *
 * Older phones get null answers and fall back to the ladder, as before.
 */
class Models(private val context: Context) {

    data class Report(
        val installed: List<String>,
        val supported: List<String>,
        val pending: List<String>,
    ) {
        val hasAny: Boolean get() = installed.isNotEmpty()
    }

    private var recognizer: SpeechRecognizer? = null

    // Not Context.getMainExecutor(): that is API 28, and minSdk here is 26.
    private val main = Handler(Looper.getMainLooper())
    private val onMain = Executor { main.post(it) }

    val usable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Must be called on the main thread, like everything else about SpeechRecognizer. */
    fun query(language: String?, then: (Report?) -> Unit) {
        if (!usable) {
            then(null)
            return
        }
        val recognizer = obtain() ?: run { then(null); return }
        runCatching {
            recognizer.checkRecognitionSupport(
                intentFor(language),
                onMain,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val report = Report(
                            installed = support.installedOnDeviceLanguages,
                            supported = support.supportedOnDeviceLanguages,
                            pending = support.pendingOnDeviceLanguages,
                        )
                        logd("on-device models: $report")
                        installedCache = report.installed
                        then(report)
                    }

                    override fun onError(error: Int) {
                        logw("could not check on-device support: error $error")
                        then(null)
                    }
                },
            )
        }.onFailure {
            logw("checkRecognitionSupport threw", it)
            then(null)
        }
    }

    /**
     * Ask the system to fetch a model. Fire-and-forget on API 33; the download
     * happens in the background and a later summon picks it up.
     */
    fun download(language: String?) {
        if (!usable) return
        val recognizer = obtain() ?: return
        logd("requesting on-device model download for ${language ?: "the default language"}")
        runCatching { recognizer.triggerModelDownload(intentFor(language)) }
            .onFailure { logw("triggerModelDownload threw", it) }
    }

    /** Keep one instance: destroying it mid-download would cancel the request. */
    private fun obtain(): SpeechRecognizer? {
        // [usable] already covers this, but lint cannot follow a property getter
        // into the call, and an unguarded API-33 call is worth being loud about.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        recognizer?.let { return it }
        return runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            .onFailure { logw("no on-device recogniser", it) }
            .getOrNull()
            ?.also { recognizer = it }
    }

    fun close() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private fun intentFor(language: String?) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }

    companion object {
        /**
         * Survives the (deliberately short-lived) assist activity, so the second
         * summon can put a known-installed language at the top of the ladder.
         */
        @Volatile
        var installedCache: List<String> = emptyList()
            private set
    }
}
