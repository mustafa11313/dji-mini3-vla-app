package dji.sampleV5.aircraft.recorder

import dji.sampleV5.aircraft.models.LiveStreamHelper
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.manager.KeyManager
import dji.v5.utils.common.ContextUtil
import dji.sampleV5.aircraft.vla.Speaker
import java.io.File

/**
 * ONE recording controller for the whole app process.
 *
 * Why this exists: the listeners used to be registered by BasicAircraftControlVM. That view model
 * is created again every time the page is opened from the main screen, and the old ones keep
 * their listeners alive, so every earlier visit started its own recorder (extra episode folders,
 * half-empty files, dropped rows). Here every listener is registered exactly once per process,
 * with this object as the owner, no matter how many view models exist.
 *
 * - Recording starts when the camera starts recording (record button on the controller).
 * - Recording ends with the landing: when the aircraft touches down after it was in the air
 *   during this recording, the camera recording is stopped, which stops telemetry + frames.
 */
object RecordingController {

    private val telemetry by lazy { TelemetryRecorder(ContextUtil.getContext()) }
    private val frames = FrameRecorder()

    private var recordingRegistered = false
    private var streamRegistered = false
    private var landingRegistered = false
    @Volatile private var sawFlying = false

    /** Result of the on-phone episode check (EpisodeCheck), called on a background thread. */
    @Volatile var onEpisodeChecked: ((File, EpisodeCheck.Result) -> Unit)? = null

    @Synchronized
    fun ensureRecording(onStatus: (String) -> Unit = {}) {
        if (recordingRegistered) return
        recordingRegistered = true
        val km = KeyManager.getInstance()

        km.listen(KeyTools.createKey(CameraKey.KeyIsRecording, ComponentIndexType.LEFT_OR_MAIN), this) { _, isRecording ->
            android.util.Log.d("TelemetryRec", "KeyIsRecording = $isRecording")
            if (isRecording == true) {
                sawFlying = false
                telemetry.rowGate = { t -> frames.onTick(t) }      // one frame per telemetry row
                telemetry.start("Take off, locate the red landing pad, and land on it.")
                telemetry.episodeDir?.let { frames.start(it) }
                onStatus("Telemetry + frames started: ${telemetry.episodeDir}")
            } else if (isRecording == false) {
                val wasRecording = EpisodePlan.recording
                frames.stop()
                telemetry.stop()
                onStatus("Telemetry + frames stopped")
                if (wasRecording) telemetry.episodeDir?.let { dir -> checkLater(dir) }
            }
        }

        // The episode ends with the landing.
        km.listen(KeyTools.createKey(FlightControllerKey.KeyIsFlying), this) { _, flying ->
            if (flying == true) {
                sawFlying = true
            } else if (flying == false && sawFlying && EpisodePlan.recording) {
                sawFlying = false
                CameraKey.KeyStopRecord.create(ComponentIndexType.LEFT_OR_MAIN).action({}, {})
            }
        }
    }

    /** D2: check the finished episode right away, off the SDK's thread. */
    private fun checkLater(dir: File) {
        Thread {
            val r = EpisodeCheck.run(dir)
            Speaker.say(if (r.ok) "Episode OK" else "Episode check failed, see the screen")
            onEpisodeChecked?.invoke(dir, r)
        }.start()
    }

    @Synchronized
    fun ensureStream(rtmpUrl: String, onStatus: (String) -> Unit = {}) {
        if (streamRegistered) return
        streamRegistered = true
        KeyManager.getInstance().listen(
            KeyTools.createKey(CameraKey.KeyIsRecording, ComponentIndexType.LEFT_OR_MAIN), this
        ) { _, isRecording ->
            onStatus("Recording state changed: $isRecording")
            if (isRecording == true) {
                onStatus("Recording detected -- starting live stream")
                LiveStreamHelper.start(rtmpUrl, onStatus)
            }
        }
    }

    /** Confirms the landing automatically when the SDK asks for it. */
    @Synchronized
    fun ensureLandingConfirmation() {
        if (landingRegistered) return
        landingRegistered = true
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyIsLandingConfirmationNeeded), this) { _, needed ->
            if (needed == true) {
                FlightControllerKey.KeyConfirmLanding.create().action({ }, { _: IDJIError -> })
            }
        }
    }
}
