package dji.sampleV5.aircraft.vla

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager

/**
 * The ONLY way a model command reaches the drone. Every safety rule of the model loop lives here,
 * on the phone, so it still works when the PC or the network fails.
 *
 *  - Two keys: the PC must be in "active" mode AND the pilot must press MODEL FLY.
 *  - Limits: every stick is clamped to the phone's own caps and to the PC's caps (the smaller wins).
 *    Throttle may only go down (the pilot data never climbs); yaw is off by default.
 *  - Watchdog: no fresh command for 0.5 s -> hover. For 3 s -> hand back to the pilot.
 *  - Takeover: any physical stick moved beyond 10 % -> model off, control back to the pilot at once.
 *  - STOP: emergencyStop() -> hover, model off, virtual stick off.
 *  - Landing: below the landing height while the model descends -> DJI auto-landing
 *    (the training episodes end where auto-landing starts, so the model never learned touchdown).
 *
 * Commands are re-sent every 50 ms (20 Hz) on the main thread; DJI recommends 5-25 Hz.
 * Stick percent -> drone values uses the same curve and constants as replay.py.
 */
object StickController {

    // ---- phone-side limits, stick percent [pitch, roll, throttle, yaw]; never looser than these ----
    private val PHONE_MAX_PCT = doubleArrayOf(50.0, 50.0, 100.0, 0.0)   // yaw 0: not learned yet
    private const val THROTTLE_MAX_UP_PCT = 0.0                         // never climb
    const val WATCHDOG_MS = 500L
    const val HANDBACK_MS = 3000L
    private const val STALE_MS = 1000L          // commands answering a frame older than this are ignored
    private const val TAKEOVER_PCT = 10.0
    private const val SEND_PERIOD_MS = 50L

    // ---- same curve as replay.py / VirtualStickVM: value = max * (0.12 x + 0.88 x^3) ----
    private const val TILT_MAX_DEG = 37.0
    private const val YAW_MAX_DPS = 60.0
    private const val THROTTLE_GAIN = 0.003     // m/s per stick percent
    private const val PITCH_SIGN = -1.0

    enum class Mode { OFF, SHADOW, ACTIVE }

    /** What Speaker says when the model is switched off (idea V4). */
    private val SPOKEN = mapOf(
        "takeover" to "You have control",
        "stop" to "Stopped. You have control",   // not "stop": the voice listener would hear it
        "watchdog_handback" to "Link lost, you have control",
        "mode_change" to "PC left active mode, you have control",
        "auto_landing" to "Auto landing"
    )

    class Status(
        val mode: Mode, val armed: Boolean, val connected: Boolean,
        val delayMs: Long, val modelPct: DoubleArray, val pilotPct: IntArray, val note: String,
        /** Model's spread per stick in stick percent (small = sure), null if the model gives none. */
        val spreadPct: DoubleArray? = null,
        /** The PC hovered instead of obeying because the model was unsure (bridge --max-spread). */
        val unsure: Boolean = false
    )

    @Volatile var mode = Mode.OFF; private set
    @Volatile var armed = false; private set
    @Volatile var connected = false
    @Volatile var landHeightM = 0.6; private set
    private var pcMaxPct = doubleArrayOf(0.0, 0.0, 0.0, 0.0)   // until the PC sends its limits
    private var target = DoubleArray(4)                         // clamped model command, percent
    private var lastCmdAt = 0L
    private var lastDelay = -1L
    private var note = "model off"
    private var landingStarted = false
    private var spread: DoubleArray? = null
    private var unsure = false

    /** Status for the screen; called on the main thread. */
    @Volatile var onStatus: ((Status) -> Unit)? = null
    /** Events for the PC log (takeover, stop, watchdog, auto_landing, ...). */
    @Volatile var onEvent: ((String, String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        LiveTelemetry.start()
        LiveTelemetry.onPilotStick = {
            if (armed && LiveTelemetry.maxAbsStick() > TAKEOVER_PCT / 100.0 * LiveTelemetry.STICK_MAX) {
                main.post { handBack("takeover", "pilot moved the sticks") }
            }
        }
        main.post(object : Runnable {
            override fun run() {
                tick()
                main.postDelayed(this, SEND_PERIOD_MS)
            }
        })
    }

    /** From the PC: {"type":"config", ...}. */
    fun onConfig(modeName: String, limitsPct: DoubleArray?, landHeight: Double?) {
        main.post {
            setMode(modeName)
            limitsPct?.takeIf { it.size == 4 }?.let { pcMaxPct = it }
            landHeight?.let { landHeightM = it }
            publish()
        }
    }

    /** From the PC: {"type":"cmd", ...}. tMs = phone clock of the frame this command answers. */
    fun onCommand(sticksPct: DoubleArray, modeName: String, tMs: Long,
                  spreadPct: DoubleArray? = null, unsureFlag: Boolean = false) {
        main.post {
            setMode(modeName)
            val delay = SystemClock.elapsedRealtime() - tMs
            lastDelay = delay
            if (sticksPct.size != 4 || delay > STALE_MS) { publish(); return@post }
            spread = spreadPct?.takeIf { it.size == 4 }
            unsure = unsureFlag
            if (armed && unsure) Speaker.say("Not sure, hovering")
            target = clamp(sticksPct)
            lastCmdAt = SystemClock.elapsedRealtime()
            if (armed && !landingStarted && LiveTelemetry.heightM < landHeightM && target[2] < 0) {
                autoLand()
            }
            publish()
        }
    }

    /** MODEL FLY button. Runs the pre-flight checklist, then enables virtual stick. */
    fun arm(onResult: (Boolean, String) -> Unit) {
        main.post {
            val failed = PreflightCheck.run().filter { !it.ok }
            if (failed.isNotEmpty()) {
                onResult(false, "Not ready: " + failed.joinToString("; ") { "${it.name} (${it.detail})" })
                return@post
            }
            val vsm = VirtualStickManager.getInstance()
            vsm.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    main.post {
                        vsm.setVirtualStickAdvancedModeEnabled(true)
                        target = DoubleArray(4)
                        lastCmdAt = SystemClock.elapsedRealtime()     // grace period: hover until a command
                        landingStarted = false
                        armed = true
                        note = "MODEL FLYING"
                        Speaker.say("Model flying", urgent = true)
                        onEvent?.invoke("armed", "")
                        publish()
                        onResult(true, "Model flying. Move any stick to take over.")
                    }
                }

                override fun onFailure(error: IDJIError) {
                    onResult(false, "Virtual stick could not be enabled: $error")
                }
            })
        }
    }

    /** STOP button (and later the voice word). Safe to call any time, from any thread. */
    fun emergencyStop() {
        main.post { handBack("stop", "STOP pressed") }
    }

    private fun setMode(name: String) {
        val m = when (name) { "active" -> Mode.ACTIVE; "shadow" -> Mode.SHADOW; else -> Mode.OFF }
        if (m != Mode.ACTIVE && armed) handBack("mode_change", "PC left active mode")
        mode = m
    }

    private fun tick() {
        if (!armed) return
        val age = SystemClock.elapsedRealtime() - lastCmdAt
        when {
            !connected || age > HANDBACK_MS -> handBack("watchdog_handback", "no commands for ${age} ms")
            age > WATCHDOG_MS -> {
                send(DoubleArray(4))
                if (!note.startsWith("WATCHDOG")) Speaker.say("No commands, hovering", urgent = true)
                note = "WATCHDOG: hovering"
            }
            else -> { send(target); note = "MODEL FLYING" }
        }
    }

    private fun handBack(event: String, reason: String) {
        val wasArmed = armed
        armed = false
        target = DoubleArray(4)
        if (wasArmed) {
            send(DoubleArray(4))   // one hover command, then give the sticks back
            VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {}
                override fun onFailure(error: IDJIError) {
                    note = "COULD NOT DISABLE VIRTUAL STICK: $error -- use the RC pause button"
                    publish()
                }
            })
        }
        note = "model off: $reason"
        if (wasArmed) Speaker.say(SPOKEN[event] ?: "Model off, you have control", urgent = true)
        onEvent?.invoke(event, reason)
        publish()
    }

    private fun autoLand() {
        landingStarted = true
        handBack("auto_landing", "height ${"%.2f".format(LiveTelemetry.heightM)} m < $landHeightM m, DJI auto-landing")
        FlightControllerKey.KeyStartAutoLanding.create().action({}, { e: IDJIError ->
            note = "AUTO-LANDING FAILED: $e -- land by hand"
            publish()
        })
    }

    private fun clamp(pct: DoubleArray): DoubleArray = DoubleArray(4) { i ->
        val cap = minOf(PHONE_MAX_PCT[i], pcMaxPct[i])
        val hi = if (i == 2) minOf(cap, THROTTLE_MAX_UP_PCT) else cap
        pct[i].coerceIn(-cap, hi)
    }

    private fun send(pct: DoubleArray) {
        val param = VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            rollPitchControlMode = RollPitchControlMode.ANGLE
            pitch = PITCH_SIGN * curve(pct[0]) * TILT_MAX_DEG
            roll = curve(pct[1]) * TILT_MAX_DEG
            verticalThrottle = pct[2] * THROTTLE_GAIN
            yaw = curve(pct[3]) * YAW_MAX_DPS
        }
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    private fun curve(pct: Double): Double {
        val x = pct / 100.0
        return 0.12 * x + 0.88 * x * x * x
    }

    private fun publish() {
        val s = Status(mode, armed, connected, lastDelay, target.copyOf(), LiveTelemetry.pilotPct(), note,
            spread?.copyOf(), unsure)
        main.post { onStatus?.invoke(s) }
    }

    /** The last command from the model, shown in shadow mode next to the pilot's sticks. */
    fun lastModelPct(): DoubleArray = target.copyOf()
}
