package dji.sampleV5.aircraft.vla

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice in (ideas V1 and V7), with Android's own speech recognizer. No extra library.
 *
 *  - Voice STOP (V7): while the listener is on, any recognised "stop", "hover", "halt" or
 *    "abort" calls StickController.emergencyStop() at once, on the first partial result, so
 *    it does not wait for the sentence to end. The listener restarts itself after every
 *    phrase. It uses the on-device recognizer when the phone has it (Android 12+, language
 *    pack downloaded), so it keeps working when the Wi-Fi to the PC is gone.
 *  - Say the mission (V1): MIC button, then speak, e.g. "land on the red pad". The words go
 *    to the PC with every frame (BridgeClient.instruction) and are spoken back.
 *
 * Limits, honestly: between two phrases the recognizer is deaf for roughly half a second
 * while it restarts, and some phones beep at every restart. The RC pause button and the red
 * STOP button stay the primary stops; voice is the extra one for when the hands are busy.
 * A dedicated keyword spotter (e.g. Vosk or Porcupine) would remove the gap; it can replace
 * this class later without touching the rest.
 */
object VoiceCommands {
    private val STOP_WORDS = Regex("\\b(stop|hover|halt|abort)\\b", RegexOption.IGNORE_CASE)
    private const val RESTART_MS = 250L

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var appContext: Context? = null
    @Volatile var stopListenerOn = false; private set
    private var missionPending = false
    private var listening = false

    /** Every recognised phrase (partial or final), for the screen. */
    @Volatile var onHeard: ((String) -> Unit)? = null
    /** A new mission sentence was heard after MIC. */
    @Volatile var onMission: ((String) -> Unit)? = null
    /** Problems (no permission, no recognizer) for the screen. */
    @Volatile var onProblem: ((String) -> Unit)? = null

    /** Voice STOP on: listens continuously until stopStopListener(). */
    fun startStopListener(context: Context) {
        main.post {
            appContext = context.applicationContext
            stopListenerOn = true
            if (!listening) listen()
        }
    }

    fun stopStopListener() {
        main.post {
            stopListenerOn = false
            if (!missionPending) shutDown()
        }
    }

    /** MIC button: the next sentence is the mission. */
    fun listenForMission(context: Context) {
        main.post {
            appContext = context.applicationContext
            missionPending = true
            if (listening) recognizer?.cancel()
            listen()
        }
    }

    private fun listen() {
        val ctx = appContext ?: return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onProblem?.invoke("No speech recognizer on this phone")
            return
        }
        val r = recognizer ?: create(ctx).also { recognizer = it }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        listening = true
        r.startListening(intent)
    }

    private fun create(ctx: Context): SpeechRecognizer {
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partial: Bundle?) {
                texts(partial).firstOrNull { STOP_WORDS.containsMatchIn(it) }?.let { stopHeard(it) }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val all = texts(results)
                val best = all.firstOrNull().orEmpty()
                if (best.isNotBlank()) onHeard?.invoke(best)
                when {
                    all.any { STOP_WORDS.containsMatchIn(it) } -> stopHeard(best)
                    missionPending && best.isNotBlank() -> {
                        missionPending = false
                        onMission?.invoke(best)
                    }
                }
                again()
            }

            override fun onError(error: Int) {
                listening = false
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        onProblem?.invoke("Microphone permission missing (allow it in the app settings)")
                        stopListenerOn = false
                        missionPending = false
                        return
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                        recognizer?.destroy()     // a fresh recognizer gets out of a stuck state
                        recognizer = null
                    }
                }
                if (missionPending && error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    missionPending = false
                    onProblem?.invoke("Heard nothing, press MIC again")
                }
                again()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        return r
    }

    private fun stopHeard(text: String) {
        StickController.emergencyStop()
        missionPending = false
        onHeard?.invoke("STOP (\"$text\")")
    }

    private fun again() {
        if (stopListenerOn || missionPending) main.postDelayed({ if (!listening) listen() }, RESTART_MS)
        else shutDown()
    }

    private fun shutDown() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun texts(b: Bundle?): List<String> =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filterNotNull() ?: emptyList()
}
