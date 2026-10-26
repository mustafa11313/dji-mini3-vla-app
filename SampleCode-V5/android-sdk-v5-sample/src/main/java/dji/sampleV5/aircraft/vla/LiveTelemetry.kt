package dji.sampleV5.aircraft.vla

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.v5.manager.KeyManager

/**
 * Latest drone state for the model loop, listened to ONCE per app process
 * (same reason as RecordingController: view models come and go, listeners must not pile up).
 * Units here are SI (metres, m/s) -- the telemetry CSV keeps its Airdata units.
 */
object LiveTelemetry {
    /** Raw RC stick range reported by the SDK (checked with stick_check.txt: 660). */
    const val STICK_MAX = 660.0

    @Volatile var heightM = 0.0; private set
    @Volatile var velocity: Velocity3D? = null; private set
    @Volatile var attitude: Attitude? = null; private set
    @Volatile var flying = false; private set

    // Physical sticks, raw -660..660. Mode 2: left = throttle / yaw, right = pitch / roll.
    @Volatile var leftV = 0; private set
    @Volatile var leftH = 0; private set
    @Volatile var rightV = 0; private set
    @Volatile var rightH = 0; private set

    /** Called whenever a physical stick moves (StickController uses it for the takeover). */
    @Volatile var onPilotStick: (() -> Unit)? = null

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val km = KeyManager.getInstance()
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAltitude), this) { _, v -> heightM = v ?: heightM }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this) { _, v -> velocity = v }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude), this) { _, v -> attitude = v }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyIsFlying), this) { _, v -> flying = v == true }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickLeftVertical), this) { _, v -> leftV = v ?: 0; onPilotStick?.invoke() }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickLeftHorizontal), this) { _, v -> leftH = v ?: 0; onPilotStick?.invoke() }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickRightVertical), this) { _, v -> rightV = v ?: 0; onPilotStick?.invoke() }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickRightHorizontal), this) { _, v -> rightH = v ?: 0; onPilotStick?.invoke() }
    }

    /** Pilot sticks in percent, in the dataset order [pitch, roll, throttle, yaw]. */
    fun pilotPct(): IntArray = intArrayOf(pct(rightV), pct(rightH), pct(leftV), pct(leftH))

    /** Largest physical stick deflection right now, raw units. */
    fun maxAbsStick(): Int = maxOf(Math.abs(leftV), Math.abs(leftH), Math.abs(rightV), Math.abs(rightH))

    private fun pct(raw: Int) = Math.round(raw / STICK_MAX * 100).toInt()

    /** Compass heading 0..360 from the SDK's -180..180 yaw. */
    fun headingDeg(): Double = ((attitude?.yaw ?: 0.0) % 360.0 + 360.0) % 360.0
}
