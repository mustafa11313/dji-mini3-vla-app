package dji.sampleV5.aircraft.vla

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * The drone talks back (idea V4): short spoken status, so the pilot can keep the eyes on the
 * drone instead of the screen. "Model flying", "You have control", "No commands, hovering",
 * "Not sure, hovering", "Auto landing", "Episode OK" ...
 *
 * Android's built-in text-to-speech, no network needed. The same sentence is not repeated
 * within REPEAT_MS, so a flickering state cannot turn into a stream of words.
 * Type "voice off" / "voice on" in the command box to switch it.
 */
object Speaker {
    private const val REPEAT_MS = 4000L

    @Volatile var enabled = true
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val lastSaid = HashMap<String, Long>()

    @Synchronized
    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ready = true
            }
        }
    }

    /** urgent = cut off whatever is being said (takeover, STOP, watchdog). */
    fun say(text: String, urgent: Boolean = false) {
        if (!enabled || !ready) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lastSaid) {
            val last = lastSaid[text]
            if (last != null && now - last < REPEAT_MS) return
            lastSaid[text] = now
        }
        tts?.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text)
    }
}
