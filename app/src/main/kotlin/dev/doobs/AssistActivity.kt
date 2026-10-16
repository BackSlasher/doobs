package dev.doobs

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import dev.doobs.act.Actions
import dev.doobs.parse.Command
import dev.doobs.parse.CommandParser
import dev.doobs.parse.Parsed
import dev.doobs.speech.Ears

/**
 * The entire user interface: a sheet at the bottom of the screen that listens,
 * shows what it heard, does the thing, and gets out of the way.
 */
class AssistActivity : Activity(), Ears.Listener {

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var mic: ImageButton
    private lateinit var ears: Ears

    private val main = Handler(Looper.getMainLooper())
    private var dismissing = false

    /** Set once a transcript is in hand, so a permission dialog can't restart listening. */
    private var acting = false
    private var pending: Command? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assist)

        window.setGravity(Gravity.BOTTOM)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)

        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        mic = findViewById(R.id.mic)
        findViewById<TextView>(R.id.grammar).text = grammar()
        ears = Ears(this, this)

        mic.setOnClickListener { startListening() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // singleTask: a second summons arrives here, not in onCreate.
        intent?.let { setIntent(it) }
    }

    override fun onStart() {
        super.onStart()
        // `acting` matters because a permission dialog can stop and restart us
        // mid-command; without it we would start listening again on top of the
        // command we are still carrying out.
        if (dismissing || acting) return

        // Debug builds accept a typed transcript, which is the only way to exercise
        // the parser and the clock hand-off from a script:
        //   adb shell am start -n dev.doobs/.AssistActivity -e transcript "set an alarm for 7:30"
        injectedTranscript()?.let {
            logd("using injected transcript '$it'")
            onHeard(it)
            return
        }

        // Opened by the assist gesture or the launcher: start listening straight away,
        // the way the thing it replaces did.
        startListening()
    }

    /** The grammar, verbatim, so the sheet and the parser can't drift apart. */
    private fun grammar(): String {
        val patterns = resources.getStringArray(R.array.grammar).joinToString("\n")
        val notes = resources.getStringArray(R.array.grammar_notes).joinToString("\n")
        return "$patterns\n\n$notes"
    }

    private fun injectedTranscript(): String? {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return intent?.getStringExtra(EXTRA_TRANSCRIPT)?.takeIf { it.isNotBlank() }
    }

    override fun onStop() {
        super.onStop()
        ears.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        ears.close()
    }

    private fun startListening() {
        // The keyguard draws above us, so summoning doobs from a locked phone
        // would open the microphone behind a screen showing no sign of it.
        // Refuse, and say so with a toast, which does draw over the keyguard.
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            logd("keyguard is up; refusing to listen")
            Toast.makeText(this, R.string.err_locked, Toast.LENGTH_SHORT).show()
            dismissLater(0)
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        transcript.text = ""
        ears.listen()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQUEST_MIC ->
                if (granted) startListening() else onTrouble(getString(R.string.err_no_mic_permission))

            REQUEST_LOCATION -> {
                val command = pending
                pending = null
                if (granted && command != null) {
                    carryOut(command)
                } else {
                    onTrouble(getString(R.string.err_no_location_permission))
                }
            }
        }
    }

    // --- Ears.Listener -----------------------------------------------------

    override fun onListening() {
        status.setText(R.string.prompt_listening)
    }

    override fun onPartial(text: String) {
        transcript.text = text
    }

    override fun onHeard(text: String) {
        transcript.text = text
        status.setText(R.string.prompt_thinking)
        handle(text)
    }

    override fun onTrouble(message: String) {
        // Leave the transcript alone: seeing what it thought you said is half
        // the explanation for why it failed.
        status.text = message
        dismissLater(TROUBLE_LINGER_MS)
    }

    // ----------------------------------------------------------------------

    private fun handle(heard: String) {
        when (val parsed = CommandParser.parse(heard)) {
            is Parsed.NotUnderstood -> {
                logd("not understood: '${parsed.heard}'")
                status.setText(R.string.unparsed)
                dismissLater(TROUBLE_LINGER_MS)
            }

            is Parsed.Understood -> carryOut(parsed.command)
        }
    }

    private fun carryOut(command: Command) {
        acting = true

        // Location is asked for the first time someone parks, not at install:
        // the other commands have no business knowing where you are.
        if (command == Command.ParkHere && !hasLocationPermission()) {
            pending = command
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION,
            )
            return
        }

        // A fix can take a few seconds, so say something rather than sit blank.
        if (command == Command.ParkHere) status.setText(R.string.prompt_locating)

        Actions.perform(this, command) { outcome ->
            when (outcome) {
                is Actions.Outcome.Done -> {
                    status.text = outcome.message
                    dismissLater(DONE_LINGER_MS)
                }

                is Actions.Outcome.Failed -> {
                    status.text = outcome.message
                    dismissLater(TROUBLE_LINGER_MS)
                }
            }
        }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun dismissLater(delayMs: Long) {
        dismissing = true
        ears.stop()
        main.postDelayed({ finish() }, delayMs)
    }

    private companion object {
        const val EXTRA_TRANSCRIPT = "transcript"
        const val REQUEST_MIC = 1
        const val REQUEST_LOCATION = 2
        const val DONE_LINGER_MS = 1_400L
        const val TROUBLE_LINGER_MS = 2_400L
    }
}
