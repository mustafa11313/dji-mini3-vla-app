package dji.sampleV5.aircraft.models

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import dji.sampleV5.aircraft.recorder.TelemetryRecorder
import org.json.JSONObject
import kotlin.concurrent.thread
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import dji.v5.et.get

class VirtualStickVM : DJIViewModel() {

    val currentSpeedLevel = MutableLiveData(0.0)
    var useRcStick = MutableLiveData(false)
    val currentVirtualStickStateInfo = MutableLiveData(VirtualStickStateInfo())

    val virtualStickAdvancedParam = MutableLiveData(VirtualStickFlightControlParam()).apply {
        value?.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        value?.verticalControlMode = VerticalControlMode.VELOCITY
        value?.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        value?.rollPitchControlMode = RollPitchControlMode.ANGLE
    }

    var stickValue = MutableLiveData(RCStickValue(0, 0, 0, 0))

    // --- Automatic replay state (full-speed looping, use later once
    // step-by-step debugging has found and fixed any issues) ---
    private var replayGeneration = 0
    private var replayRunning = false

    // --- Manual step-through state (debugging: one CSV row per click) ---
    // Publishes the raw JSON of the last command received, so the UI can
    // display exactly what was sent to the aircraft.
    val lastStepInfo = MutableLiveData("")

    private var logTimer: Timer? = null
    private var logWriter: FileWriter? = null

    private var aircraftLocationKey: DJIKey<LocationCoordinate2D> =
        FlightControllerKey.KeyAircraftLocation.create()

    private var compassHeadKey: DJIKey<Double> =
        FlightControllerKey.KeyCompassHeading.create()

    private var altitudeKey: DJIKey<Double> =
        FlightControllerKey.KeyAltitude.create()

    private var flightSpeed: DJIKey<Velocity3D> =
        FlightControllerKey.KeyAircraftVelocity.create()

    // Change this to match your server's current LAN IP (check with
    // `hostname -I` on the machine running replay.py -- it can change
    // between sessions).
    private val SERVER_BASE_URL = "http://172.24.14.216:5000"

    init {
        currentSpeedLevel.value = VirtualStickManager.getInstance().speedLevel
        VirtualStickManager.getInstance().setVirtualStickStateListener(object : VirtualStickStateListener {
            override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
                currentVirtualStickStateInfo.postValue(currentVirtualStickStateInfo.value?.apply {
                    this.state = stickState
                })
            }

            override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
                currentVirtualStickStateInfo.postValue(currentVirtualStickStateInfo.value?.apply {
                    this.reason = reason
                })
            }
        })
    }

    fun enableVirtualStick(callback: CommonCallbacks.CompletionCallback) {
        VirtualStickManager.getInstance().enableVirtualStick(callback)
    }

    fun disableVirtualStick(callback: CommonCallbacks.CompletionCallback) {
        VirtualStickManager.getInstance().disableVirtualStick(callback)
    }

    fun setSpeedLevel(speedLevel: Double) {
        VirtualStickManager.getInstance().speedLevel = speedLevel
        currentSpeedLevel.value = speedLevel
    }

    fun setLeftPosition(horizontal: Int, vertical: Int) {
        VirtualStickManager.getInstance().leftStick.horizontalPosition = horizontal
        VirtualStickManager.getInstance().leftStick.verticalPosition = vertical
    }

    fun setRightPosition(horizontal: Int, vertical: Int) {
        VirtualStickManager.getInstance().rightStick.horizontalPosition = horizontal
        VirtualStickManager.getInstance().rightStick.verticalPosition = vertical
    }

    fun sendVirtualStickAdvancedParam(param: VirtualStickFlightControlParam) {
        virtualStickAdvancedParam.postValue(param)
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    // =========================================================================
    // MANUAL STEP-THROUGH (debugging) -- one CSV row per call, no looping,
    // no discard logic. Every response is applied immediately, and the raw
    // JSON is published to lastStepInfo so you can see exactly what was sent.
    // =========================================================================

    // Flips to true when the replay reaches the end of the CSV (either via
    // manual stepReplay clicks or the automatic loop), so the Fragment can
    // trigger landing automatically. Call resetReplayFinishedFlag() after
    // handling it, so it doesn't re-trigger repeatedly.
    val replayFinished = MutableLiveData(false)

    fun resetReplayFinishedFlag() {
        replayFinished.postValue(false)
    }

    fun resetReplay() {
        thread {
            try {
                val connection = URL("$SERVER_BASE_URL/command?cmd=reset")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 500
                connection.readTimeout = 500
                connection.inputStream.bufferedReader().use { it.readText() }
                lastStepInfo.postValue("Reset OK -- current_index back to 0")
            } catch (e: Exception) {
                e.printStackTrace()
                lastStepInfo.postValue("Reset FAILED: ${e.message}")
            }
        }
    }

    // Tracks whatever pitch/roll/yaw/throttle is currently being held
    // (either by stepReplay or the automatic loop), so each new manual step
    // can ramp smoothly from wherever the aircraft actually is now, instead
    // of jumping straight to the new target.
    private var lastPitch = 0.0
    private var lastRoll = 0.0
    private var lastYaw = 0.0
    private var lastThrottle = 0.0

    // Bumped on every stepReplay() call, so if you click again before the
    // previous click's interpolation has finished, the old one is cancelled
    // instead of both fighting over the aircraft at once.
    private var manualStepGeneration = 0

    fun stepReplay() {
        manualStepGeneration++
        val myStepGeneration = manualStepGeneration

        thread {
            try {
                val connection = URL("$SERVER_BASE_URL/command?cmd=replay")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 500
                connection.readTimeout = 500

                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonText)
                lastStepInfo.postValue(jsonText)

                val status = json.optString("status", "")

                if (status == "finished") {
                    hoverOnly()
                    lastPitch = 0.0; lastRoll = 0.0; lastYaw = 0.0; lastThrottle = 0.0
                    replayFinished.postValue(true)
                    return@thread
                }

                if (status != "ok") {
                    hoverOnly()
                    lastPitch = 0.0; lastRoll = 0.0; lastYaw = 0.0; lastThrottle = 0.0
                    return@thread
                }

                val targetPitch = json.getDouble("pitch")
                val targetRoll = json.getDouble("roll")
                val targetYaw = json.getDouble("yaw")
                val targetThrottle = json.getDouble("throttle")
                val durationMs = json.optInt("duration_ms", 100).coerceAtLeast(1)

                // Ramp from wherever we currently are (lastPitch etc, i.e.
                // whatever was held after the PREVIOUS click) toward this
                // row's target, over duration_ms -- instead of snapping
                // straight to it. This smooths out the abrupt jump between
                // manual clicks, similar to the automatic loop's
                // current->next interpolation, but anchored on the actual
                // last-held value rather than a lookahead.
                interpolateAndSend(
                    { myStepGeneration == manualStepGeneration },
                    lastPitch, lastRoll, lastYaw, lastThrottle,
                    targetPitch, targetRoll, targetYaw, targetThrottle,
                    durationMs
                ) {
                    lastPitch = targetPitch
                    lastRoll = targetRoll
                    lastYaw = targetYaw
                    lastThrottle = targetThrottle
                }

            } catch (e: Exception) {
                e.printStackTrace()
                lastStepInfo.postValue("Step FAILED: ${e.message}")
                hoverOnly()
                lastPitch = 0.0; lastRoll = 0.0; lastYaw = 0.0; lastThrottle = 0.0
            }
        }
    }

    // =========================================================================
    // AUTOMATIC REPLAY (full-speed looping) -- use once step-through debugging
    // is done and the sequence has been verified row by row.
    // =========================================================================

    // How often to send an intermediate command while interpolating between
    // one row's target and the next -- smaller = smoother, but more network
    // traffic isn't involved here since interpolation happens LOCALLY on the
    // phone between the 100ms-apart server polls, not via extra HTTP calls.
    private val INTERPOLATION_STEP_MS = 25L

    private fun getCommandFromServerInternal(command: String, generation: Int, onDone: () -> Unit) {
        thread {
            var handled = false
            try {
                val connection = URL("$SERVER_BASE_URL/command?cmd=$command")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 300
                connection.readTimeout = 300

                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonText)

                if (generation != replayGeneration || !replayRunning) {
                    return@thread
                }

                if (json.optString("status") == "finished") {
                    handled = true
                    stopReplay()
                    hoverOnly()
                    replayFinished.postValue(true)
                    return@thread
                }

                val startPitch = json.getDouble("pitch")
                val startRoll = json.getDouble("roll")
                val startYaw = json.getDouble("yaw")
                val startThrottle = json.getDouble("throttle")

                // Fall back to holding the same value (old zero-order-hold
                // behavior) if the server response doesn't include next_*
                // fields for some reason (e.g. an older/different server).
                val endPitch = json.optDouble("next_pitch", startPitch)
                val endRoll = json.optDouble("next_roll", startRoll)
                val endYaw = json.optDouble("next_yaw", startYaw)
                val endThrottle = json.optDouble("next_throttle", startThrottle)

                val durationMs = json.optInt("duration_ms", 100).coerceAtLeast(1)

                handled = true
                interpolateAndSend(
                    { generation == replayGeneration && replayRunning },
                    startPitch, startRoll, startYaw, startThrottle,
                    endPitch, endRoll, endYaw, endThrottle,
                    durationMs,
                    onDone
                )

            } catch (e: Exception) {
                e.printStackTrace()
                if (generation == replayGeneration && replayRunning) {
                    hoverOnly()
                }
                if (generation == replayGeneration && replayRunning) {
                    onDone()
                }
            }
        }
    }

    /**
     * Sends a series of intermediate VirtualStickFlightControlParam commands,
     * linearly interpolating from (startPitch..startThrottle) toward
     * (endPitch..endThrottle) over durationMs, instead of jumping straight
     * to the end value and holding it (zero-order hold). This smooths out
     * the "staircase" effect where a smooth manual stick sweep, once
     * sampled into discrete 100ms CSV rows, gets replayed as a series of
     * abrupt held plateaus rather than a continuous motion.
     *
     * Calls onDone() once the full durationMs window has elapsed, so the
     * next server poll can be scheduled.
     */
    private fun interpolateAndSend(
        isActive: () -> Boolean,
        startPitch: Double, startRoll: Double, startYaw: Double, startThrottle: Double,
        endPitch: Double, endRoll: Double, endYaw: Double, endThrottle: Double,
        durationMs: Int,
        onDone: () -> Unit
    ) {
        val steps = (durationMs / INTERPOLATION_STEP_MS).toInt().coerceAtLeast(1)
        val handler = Handler(Looper.getMainLooper())

        fun sendStep(stepIndex: Int) {
            if (!isActive()) return

            val t = stepIndex.toDouble() / steps.toDouble()  // 0.0 .. 1.0

            val param = VirtualStickFlightControlParam().apply {
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                verticalControlMode = VerticalControlMode.VELOCITY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                rollPitchControlMode = RollPitchControlMode.ANGLE

                pitch = startPitch + (endPitch - startPitch) * t
                roll = startRoll + (endRoll - startRoll) * t
                yaw = startYaw + (endYaw - startYaw) * t
                verticalThrottle = startThrottle + (endThrottle - startThrottle) * t
            }
            sendVirtualStickAdvancedParam(param)

            if (stepIndex < steps) {
                handler.postDelayed({ sendStep(stepIndex + 1) }, INTERPOLATION_STEP_MS)
            } else {
                if (isActive()) {
                    onDone()
                }
            }
        }

        sendStep(0)
    }

    private fun pollLoop(generation: Int) {
        if (generation != replayGeneration || !replayRunning) return

        getCommandFromServerInternal("replay", generation) {
            pollLoop(generation)
        }
    }


    fun startReplay() {
        stopReplay()
        replayGeneration++
        val myGeneration = replayGeneration

        thread {
            try {
                val connection = URL("$SERVER_BASE_URL/command?cmd=reset")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 300
                connection.readTimeout = 300
                connection.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Fetch the WHOLE episode once and play it from a local clock, so the replay runs
            // at the recorded speed (the old row-by-row polling added ~0.28 s of network wait
            // to every 0.1 s row and ran about 3.6 times too slow).
            var rows: List<DoubleArray>? = null
            try {
                val connection = URL("$SERVER_BASE_URL/command?cmd=plan")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 5000
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (json.has("rows")) {
                    val arr = json.getJSONArray("rows")
                    rows = List(arr.length()) { i ->
                        val a = arr.getJSONArray(i)
                        doubleArrayOf(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4),
                            if (a.length() > 5) a.getDouble(5) else Double.NaN)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                lastStepInfo.postValue("Plan request FAILED: ${e.message}")
            }

            if (myGeneration != replayGeneration) return@thread
            replayRunning = true
            val plan = rows
            if (plan != null && plan.isNotEmpty()) {
                lastStepInfo.postValue("Replay plan: ${plan.size} rows, ${plan.sumOf { it[4] } / 1000.0} s")
                Handler(Looper.getMainLooper()).post { playPlan(plan, myGeneration) }
            } else {
                // Server without the "plan" command (old replay.py): fall back to row-by-row polling.
                pollLoop(myGeneration)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Recording from the app: "autoplay" starts it, landing stops it. Starting / stopping the
    // camera video also starts / stops the telemetry + frame recorder (they listen to it).
    // ---------------------------------------------------------------------
    @Volatile private var stopRecordingArmed = false
    private var landedListenerRegistered = false

    fun startRecording() {
        CameraKey.KeyStartRecord.create(ComponentIndexType.LEFT_OR_MAIN).action({
            lastStepInfo.postValue("Recording started")
        }, { e: IDJIError ->
            lastStepInfo.postValue("Start recording FAILED: $e -- press record by hand")
        })
    }

    fun stopRecording() {
        CameraKey.KeyStopRecord.create(ComponentIndexType.LEFT_OR_MAIN).action({
            lastStepInfo.postValue("Recording stopped")
        }, { e: IDJIError ->
            lastStepInfo.postValue("Stop recording FAILED: $e -- stop it by hand")
        })
    }

    /** Waits until the aircraft reports it is no longer flying (touched down), then stops recording. */
    private fun stopRecordingWhenLanded() {
        stopRecordingArmed = true
        if (!landedListenerRegistered) {
            landedListenerRegistered = true
            FlightControllerKey.KeyIsFlying.create().listen(this) { flying ->
                if (flying == false && stopRecordingArmed) {
                    stopRecordingArmed = false
                    stopRecording()
                }
            }
        }
    }

    /** Tilts the camera to an absolute pitch angle (degrees, 0 = straight ahead, negative = down). */
    fun rotateGimbalTo(pitchDeg: Double) {
        val rotation = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            pitch = pitchDeg
            roll = 0.0
            yaw = 0.0
            rollIgnored = true
            yawIgnored = true
            duration = 0.3
        }
        KeyManager.getInstance().performAction(
            KeyTools.createKey(GimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN),
            rotation,
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {}
                override fun onFailure(error: IDJIError) {}
            }
        )
    }

    /**
     * Plays a whole episode from a local clock. Every row is [pitch, roll, yaw, throttle, duration_ms].
     * The current row is re-sent every INTERPOLATION_STEP_MS (keeps the virtual stick alive, same
     * as before) and the schedule uses absolute times, so errors do not add up.
     */
    private fun playPlan(rows: List<DoubleArray>, generation: Int) {
        val handler = Handler(Looper.getMainLooper())
        val ends = LongArray(rows.size)
        var acc = 0L
        for (i in rows.indices) {
            acc += rows[i][4].toLong().coerceAtLeast(1L)
            ends[i] = acc
        }
        val total = acc
        val t0 = SystemClock.uptimeMillis()
        var idx = 0
        var k = 0L
        var lastGimbalSent = Double.NaN
        var lastGimbalTime = -1000L

        fun tick() {
            if (generation != replayGeneration || !replayRunning) return
            val elapsed = SystemClock.uptimeMillis() - t0
            if (elapsed >= total) {
                stopReplay()
                hoverOnly()
                // Land right here, so it works even when the Virtual Stick page is not open.
                FlightControllerKey.KeyStartAutoLanding.create().action({
                    stopCsvLogging()
                }, { e: IDJIError -> })
                // Stop the video / telemetry recording as soon as the aircraft is on the ground.
                stopRecordingWhenLanded()
                return
            }
            while (idx < rows.size - 1 && elapsed >= ends[idx]) idx++
            // Ramp from the previous row's values to this row's values, like the original step replay.
            val cur = rows[idx]
            val prev = if (idx > 0) rows[idx - 1] else doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
            val start = if (idx > 0) ends[idx - 1] else 0L
            val frac = ((elapsed - start).toDouble() / cur[4].coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            val r = DoubleArray(4) { j -> prev[j] + (cur[j] - prev[j]) * frac }
            val param = VirtualStickFlightControlParam().apply {
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                verticalControlMode = VerticalControlMode.VELOCITY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                rollPitchControlMode = RollPitchControlMode.ANGLE
                pitch = r[0]
                roll = r[1]
                yaw = r[2]
                verticalThrottle = r[3]
            }
            sendVirtualStickAdvancedParam(param)
            // Log what is being sent as stick percent (same columns as the physical sticks).
            TelemetryRecorder.replayCmd = intArrayOf(
                pctFromTilt(r[0] * -1.0), pctFromTilt(r[1]), pctFromThrottle(r[3]), pctFromTurn(r[2])
            )

            // Camera tilt: follow the recorded gimbal angle (send only when it changed, at most every 200 ms).
            val g = cur[5]
            if (!g.isNaN() && elapsed - lastGimbalTime >= 200L &&
                (lastGimbalSent.isNaN() || Math.abs(g - lastGimbalSent) >= 0.5)) {
                rotateGimbalTo(g)
                lastGimbalSent = g
                lastGimbalTime = elapsed
            }
            k++
            handler.postAtTime({ tick() }, t0 + k * INTERPOLATION_STEP_MS)
        }
        tick()
    }

    // Reverse of replay.py: stick percent -> drone value. These constants must match replay.py.
    private val REPLAY_TILT_MAX_DEG = 37.0
    private val REPLAY_YAW_MAX_DPS = 60.0
    private val REPLAY_THROTTLE_GAIN = 0.003

    /** Inverse of value = MAX * (0.12x + 0.88x^3), returns the stick percent (-100..100). */
    private fun pctFromCurve(value: Double, max: Double): Int {
        val y = (value / max).coerceIn(-1.0, 1.0)
        var lo = 0.0
        var hi = 1.0
        val a = Math.abs(y)
        repeat(40) {
            val mid = (lo + hi) / 2
            if (0.12 * mid + 0.88 * mid * mid * mid < a) lo = mid else hi = mid
        }
        return Math.round(Math.signum(y) * lo * 100.0).toInt()
    }
    private fun pctFromTilt(deg: Double) = pctFromCurve(deg, REPLAY_TILT_MAX_DEG)
    private fun pctFromTurn(dps: Double) = pctFromCurve(dps, REPLAY_YAW_MAX_DPS)
    private fun pctFromThrottle(ms: Double) =
        Math.round(ms / REPLAY_THROTTLE_GAIN).toInt().coerceIn(-100, 100)

    fun stopReplay() {
        TelemetryRecorder.replayCmd = null
        replayRunning = false
        replayGeneration++
    }

    private fun hoverOnly() {
        val param = VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            rollPitchControlMode = RollPitchControlMode.ANGLE
            pitch = 0.0
            roll = 0.0
            yaw = 0.0
            verticalThrottle = 0.0
        }
        sendVirtualStickAdvancedParam(param)
    }

    fun stopFromServerCommand() {
        stopReplay()
        hoverOnly()
    }

    // Public single-argument version -- used by the manual "Execute Command"
    // button for forward/backward/left/right/stop (fixed test values from
    // test_server.py, or hover fallback from replay.py).
    fun getCommandFromServer(command: String) {
        thread {
            try {
                val connection = URL("$SERVER_BASE_URL/command?cmd=$command")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 500
                connection.readTimeout = 500

                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonText)
                lastStepInfo.postValue(jsonText)

                if (json.optString("status") !in listOf("ok", "reset")) {
                    hoverOnly()
                    return@thread
                }
                if (!json.has("pitch")) {
                    return@thread
                }

                val param = VirtualStickFlightControlParam().apply {
                    rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                    verticalControlMode = VerticalControlMode.VELOCITY
                    yawControlMode = YawControlMode.ANGULAR_VELOCITY
                    rollPitchControlMode = RollPitchControlMode.ANGLE

                    pitch = json.getDouble("pitch")
                    roll = json.getDouble("roll")
                    yaw = json.getDouble("yaw")
                    verticalThrottle = json.getDouble("throttle")
                }

                sendVirtualStickAdvancedParam(param)

            } catch (e: Exception) {
                e.printStackTrace()
                lastStepInfo.postValue("Command FAILED: ${e.message}")
            }
        }
    }

    // --- CSV logging ------------------------------------------------------

    fun startCsvLogging(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "flight_logs")
        if (!dir.exists()) dir.mkdirs()

        val logFile = File(dir, "flight_log_${System.currentTimeMillis()}.csv")
        logWriter = FileWriter(logFile, true)

        logWriter?.append(
            "timestamp_ms,pitch,roll,yaw,throttle,leftH,leftV,rightH,rightV,latitude,longitude,altitude,speed,heading\n"
        )
        logWriter?.flush()

        logTimer?.cancel()
        logTimer = Timer()

        logTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val time = System.currentTimeMillis()

                val param = virtualStickAdvancedParam.value

                val pitch = param?.pitch ?: 0.0
                val roll = param?.roll ?: 0.0
                val yaw = param?.yaw ?: 0.0
                val throttle = param?.verticalThrottle ?: 0.0

                val leftH = stickValue.value?.leftHorizontal ?: 0
                val leftV = stickValue.value?.leftVertical ?: 0
                val rightH = stickValue.value?.rightHorizontal ?: 0
                val rightV = stickValue.value?.rightVertical ?: 0

                val location = aircraftLocationKey.get(LocationCoordinate2D(0.0, 0.0))
                val latitude = location.latitude
                val longitude = location.longitude

                val altitude = altitudeKey.get(0.0)

                val velocity = flightSpeed.get(Velocity3D(0.0, 0.0, 0.0))
                val speed = Math.sqrt(
                    velocity.getX() * velocity.getX() +
                            velocity.getY() * velocity.getY()
                )

                val heading = compassHeadKey.get(0.0)

                logWriter?.append(
                    "$time,$pitch,$roll,$yaw,$throttle,$leftH,$leftV,$rightH,$rightV,$latitude,$longitude,$altitude,$speed,$heading\n"
                )
                logWriter?.flush()
            }
        }, 0, 100)
    }

    fun stopCsvLogging() {
        logTimer?.cancel()
        logTimer = null

        logWriter?.flush()
        logWriter?.close()
        logWriter = null
    }

    fun disableVirtualStickAdvancedMode() {
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
    }

    fun enableVirtualStickAdvancedMode() {
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
    }

    fun listenRCStick() {
        RemoteControllerKey.KeyStickLeftHorizontal.create().listen(this) {
            it?.let { stickValue.value?.leftHorizontal = it }
            tryUpdateVirtualStickByRc()
        }

        RemoteControllerKey.KeyStickLeftVertical.create().listen(this) {
            it?.let { stickValue.value?.leftVertical = it }
            tryUpdateVirtualStickByRc()
        }

        RemoteControllerKey.KeyStickRightHorizontal.create().listen(this) {
            it?.let { stickValue.value?.rightHorizontal = it }
            tryUpdateVirtualStickByRc()
        }

        RemoteControllerKey.KeyStickRightVertical.create().listen(this) {
            it?.let { stickValue.value?.rightVertical = it }
            tryUpdateVirtualStickByRc()
        }
    }

    private fun tryUpdateVirtualStickByRc() {
        stickValue.postValue(stickValue.value)

        if (useRcStick.value == true) {
            stickValue.value?.apply {
                setLeftPosition(leftHorizontal, leftVertical)
                setRightPosition(rightHorizontal, rightVertical)
            }
        }
    }

    override fun onCleared() {
        stopReplay()
        stopCsvLogging()
        KeyManager.getInstance().cancelListen(this)
        VirtualStickManager.getInstance().clearAllVirtualStickStateListener()
    }

    data class VirtualStickStateInfo(
        var state: VirtualStickState =
            VirtualStickState(false, FlightControlAuthority.UNKNOWN, false),
        var reason: FlightControlAuthorityChangeReason =
            FlightControlAuthorityChangeReason.UNKNOWN
    )

    data class RCStickValue(
        var leftHorizontal: Int,
        var leftVertical: Int,
        var rightHorizontal: Int,
        var rightVertical: Int
    ) {
        override fun toString(): String {
            return "leftHorizontal=$leftHorizontal,leftVertical=$leftVertical,\n" +
                    "rightHorizontal=$rightHorizontal,rightVertical=$rightVertical"
        }
    }
}