package dji.sampleV5.aircraft.recorder

import android.content.Context
import android.os.SystemClock
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.v5.manager.KeyManager
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Records drone telemetry to a CSV every 100 ms, in exactly the same 18 columns
 * (same names, same order, same units) as the Airdata-based extracted CSV:
 *
 *   time_ms, latitude, longitude, height_above_takeoff, x_speed, y_speed, z_speed,
 *   heading, drone_pitch, drone_roll, pitch_cmd, roll_cmd, throttle_cmd, yaw_cmd,
 *   gimbal_heading, gimbal_pitch, gimbal_roll, flight_mode
 *
 * Output: <app files dir>/episodes/episode_XXXX_<date>_<time>/telemetry_<date>_<time>.csv
 *   = /sdcard/Android/data/dji.sampleV5.aircraft/files/episodes/...
 *
 * With RECORD_ONLY_WHILE_VIDEO = true a row is only written while the camera is
 * recording, which is what the isVideo == 1 filter did for the Airdata files. The
 * CSV then starts and ends exactly with the video, so frame 0 <-> row 0.
 *
 * With AIRDATA_UNITS = true the values use Airdata's units (feet, mph, stick percent).
 *
 * Usage:
 *   val rec = TelemetryRecorder(context)
 *   rec.start("Take off, locate the red landing pad, and land on it.")
 *   ...
 *   rec.stop()
 */
class TelemetryRecorder(private val context: Context) {

    companion object {
        const val PERIOD_MS = 100L
        const val AIRDATA_UNITS = true
        const val RECORD_ONLY_WHILE_VIDEO = true
        // Documented SDK stick range is [-660, 660]. stop() writes stick_check.txt with the
        // largest raw value seen, to verify this on the actual controller.
        const val STICK_MAX = 660.0
        /**
         * While a replay is running, VirtualStickVM puts the stick percentages it is sending
         * here [pitch, roll, throttle, yaw]; they are written instead of the (idle) physical sticks.
         * null = use the physical sticks.
         */
        @Volatile var replayCmd: IntArray? = null
        private const val M_TO_FT = 3.28084
        private const val MS_TO_MPH = 2.23694

        private const val HEADER =
            "time_ms,latitude,longitude,height_above_takeoff,x_speed,y_speed,z_speed," +
                    "heading,drone_pitch,drone_roll,pitch_cmd,roll_cmd,throttle_cmd,yaw_cmd," +
                    "gimbal_heading,gimbal_pitch,gimbal_roll,flight_mode"
    }

    // Latest values, updated by SDK listeners (any thread), read by the 100 ms timer.
    @Volatile private var attitude: Attitude? = null
    @Volatile private var velocity: Velocity3D? = null
    @Volatile private var location: LocationCoordinate3D? = null
    @Volatile private var altitude: Double? = null           // height above takeoff, m
    @Volatile private var flightMode: FlightMode? = null
    @Volatile private var gimbal: Attitude? = null
    @Volatile private var isRecording: Boolean? = null       // camera recording to SD card
    @Volatile private var stickLeftV = 0
    @Volatile private var stickLeftH = 0
    @Volatile private var stickRightV = 0
    @Volatile private var stickRightH = 0
    private var maxAbsStick = 0
    private var rowCount = 0L

    private var executor: ScheduledExecutorService? = null
    private var writer: BufferedWriter? = null
    /** Clock origin (SystemClock.elapsedRealtimeNanos at start); FrameRecorder uses the same one. */
    var t0Ns = 0L
        private set
    var episodeDir: File? = null
        private set
    /**
     * Called with the row's time_ms before each row is written (FrameRecorder hooks in here).
     * Returning false skips the row, so every written row has a frame.
     */
    @Volatile var rowGate: ((Long) -> Boolean)? = null
    private var firstRowSkipped = false
    private var epNumber = "0000"

    @Synchronized
    fun start(instruction: String = "") {
        if (executor != null) return
        // start() is now called by the app exactly when camera recording begins,
        // and stop() when it ends, so the recording state is tied to the lifecycle.
        isRecording = true
        val stamp = EpisodePlan.stamp()                       // yyyyMMdd_HHmmss
        episodeDir = nextEpisodeDir(stamp).also { it.mkdirs() }
        epNumber = EpisodePlan.numberOf(episodeDir!!)
        File(episodeDir, "${epNumber}_instruction.txt").writeText(instruction)
        EpisodePlan.writeSetup(episodeDir!!, stamp)
        EpisodePlan.lastDir = episodeDir
        EpisodePlan.recording = true

        registerListeners()

        writer = File(episodeDir, "${epNumber}_telemetry_$stamp.csv").bufferedWriter().apply {
            write(HEADER)
            newLine()
        }
        rowCount = 0L
        firstRowSkipped = false
        t0Ns = SystemClock.elapsedRealtimeNanos()
        executor = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate({ writeRow() }, 0, PERIOD_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun stop() {
        if (executor == null) return
        isRecording = false
        EpisodePlan.recording = false
        executor?.shutdown()
        executor?.awaitTermination(1, TimeUnit.SECONDS)
        executor = null
        KeyManager.getInstance().cancelListen(this)
        writer?.flush()
        writer?.close()
        writer = null
        // Stick-range check: full deflection should reach exactly STICK_MAX.
        episodeDir?.let {
            File(it, "${epNumber}_stick_check.txt").writeText(
                "max |raw stick| seen: $maxAbsStick (expected ${STICK_MAX.toInt()} at full deflection)\n" +
                        if (maxAbsStick > STICK_MAX) "WARNING: exceeds STICK_MAX, *_cmd percentages are wrong\n" else ""
            )
        }
    }

    private fun registerListeners() {
        val km = KeyManager.getInstance()
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude), this) { _, v -> attitude = v }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this) { _, v -> velocity = v }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), this) { _, v -> location = v }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyAltitude), this) { _, v -> altitude = v }
        km.listen(KeyTools.createKey(FlightControllerKey.KeyFlightMode), this) { _, v -> flightMode = v }
        km.listen(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.LEFT_OR_MAIN), this) { _, v -> gimbal = v }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickLeftVertical), this) { _, v -> stickLeftV = v ?: 0 }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickLeftHorizontal), this) { _, v -> stickLeftH = v ?: 0 }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickRightVertical), this) { _, v -> stickRightV = v ?: 0 }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickRightHorizontal), this) { _, v -> stickRightH = v ?: 0 }
    }

    private fun writeRow() {
        try {
            // Track the largest raw stick value over the whole session (also outside recording).
            maxAbsStick = maxOf(maxAbsStick, abs(stickRightV), abs(stickRightH), abs(stickLeftV), abs(stickLeftH))

            val w = writer ?: return

            // Same role as Airdata's isVideo == 1 filter: only log while the camera records.
            if (RECORD_ONLY_WHILE_VIDEO && isRecording != true) return

            val att = attitude; val vel = velocity; val gim = gimbal; val alt = altitude
            // Wait until the first value of every key has arrived, so no row has empty cells.
            if (att == null || vel == null || gim == null || alt == null) return

            val loc = location
            val timeMs = (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1_000_000

            // The very first row still holds old values from the previous flight: skip it.
            if (!firstRowSkipped) { firstRowSkipped = true; return }
            // Only write the row if a fresh frame was saved for it.
            if (rowGate?.invoke(timeMs) == false) return

            val h = if (AIRDATA_UNITS) M_TO_FT else 1.0
            val v = if (AIRDATA_UNITS) MS_TO_MPH else 1.0

            // Mode 2 sticks (DJI default): left = throttle / yaw, right = pitch / roll.
            // Airdata-style percent in [-100, 100].
            fun pct(x: Int) = (x / STICK_MAX * 100).roundToInt()

            // Indoors there is no GPS fix; Airdata wrote 0 in that case, so do the same.
            val lat = loc?.latitude?.takeIf { !it.isNaN() } ?: 0.0
            val lon = loc?.longitude?.takeIf { !it.isNaN() } ?: 0.0

            val rc = replayCmd
            val row = listOf(
                timeMs.toString(),
                f(lat, 8), f(lon, 8),
                f(alt * h),
                f(vel.x * v), f(vel.y * v), f(vel.z * v),            // NED: x=north, y=east, z=down
                f(heading360(att.yaw), 1), f(att.pitch, 1), f(att.roll, 1),
                (rc?.get(0) ?: pct(stickRightV)).toString(),   // pitch_cmd
                (rc?.get(1) ?: pct(stickRightH)).toString(),   // roll_cmd
                (rc?.get(2) ?: pct(stickLeftV)).toString(),    // throttle_cmd
                (rc?.get(3) ?: pct(stickLeftH)).toString(),    // yaw_cmd
                f(heading360(gim.yaw), 1), f(gim.pitch, 1), f(gim.roll, 1),
                flightMode?.name ?: ""
            ).joinToString(",")

            w.write(row)
            w.newLine()
            rowCount++
            if (rowCount % 10L == 0L) w.flush()   // at most ~1 s of data lost if the app dies
        } catch (_: Exception) {
            // Never let one bad sample kill the scheduled task.
        }
    }

    /** SDK yaw is [-180, 180] from north; Airdata uses compass heading [0, 360). */
    private fun heading360(yaw: Double): Double = ((yaw % 360.0) + 360.0) % 360.0

    private fun f(x: Double, digits: Int = 6): String =
        if (x.isNaN() || x.isInfinite()) "" else String.format(Locale.US, "%.${digits}f", x)

    /** episode_<running number>_<date>_<time>, e.g. episode_0037_20261008_103045 */
    private fun nextEpisodeDir(stamp: String): File {
        val root = File(context.getExternalFilesDir(null), "episodes").apply { mkdirs() }
        val last = root.listFiles()
            ?.mapNotNull { Regex("^episode_(\\d+)").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            ?.maxOrNull() ?: 0
        return File(root, String.format(Locale.US, "episode_%04d_%s", last + 1, stamp))
    }
}