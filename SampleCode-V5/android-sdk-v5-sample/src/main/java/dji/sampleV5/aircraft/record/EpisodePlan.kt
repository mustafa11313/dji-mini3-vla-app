package dji.sampleV5.aircraft.recorder

import android.content.Context
import dji.v5.utils.common.ContextUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * Recording plan: 3x3 grid, 9 pad positions x 8 drone positions (any spot except the pad's)
 * = 72 layouts, 3 repeats = 216 episodes. Every block of 72 contains each layout once,
 * shuffled, so the last episodes (validation split) also mix all layouts.
 *
 * Grid, seen from above, north up:      NW  N  NE
 *                                       W   C  E
 *                                       SW  S  SE
 *
 * The plan is generated with a fixed seed, so it is the same after every app restart.
 * Only the progress number is stored (SharedPreferences).
 */
object EpisodePlan {

    // ---- settings you may change (keep them the same for ALL episodes) ----
    const val GIMBAL_PITCH_DEG = -50.0          // camera angle, set before takeoff
    const val SPEED_MODE = "Normal"             // Normal / Cine / Sport, whatever you fly in
    const val START_HEADING = "north"           // direction the drone nose points at the start
    val SERVER_BASE_URL get() = dji.sampleV5.aircraft.vla.LabSettings.httpBase   // set in the app: "pc <ip>"
    // -----------------------------------------------------------------------

    /** Number of episodes in the current round (the first N setups of the plan). */
    const val PLAN_SIZE = 50

    private const val PREFS = "episode_plan"
    private const val KEY_NEXT = "next_index"
    private const val SEED = 2026L
    private val NAMES = arrayOf("NW", "N", "NE", "W", "C", "E", "SW", "S", "SE")

    class Setup(val number: Int, val pad: Int, val drone: Int, val repeat: Int) {
        val padName get() = NAMES[pad]
        val droneName get() = NAMES[drone]
        /** Pad position relative to the drone, in grid steps (east +, north +). */
        val east get() = (pad % 3) - (drone % 3)
        val north get() = (drone / 3) - (pad / 3)
    }

    val setups: List<Setup> by lazy {
        val rnd = Random(SEED)
        val out = ArrayList<Setup>()
        for (rep in 1..3) {
            val block = ArrayList<IntArray>()
            for (p in 0..8) for (d in 0..8) if (p != d) block.add(intArrayOf(p, d))
            block.shuffle(rnd)
            for (b in block) out.add(Setup(out.size + 1, b[0], b[1], rep))
        }
        out
    }

    /** True while an episode is being recorded (set by TelemetryRecorder). */
    @Volatile var recording = false
    /** Folder of the most recent episode (set when its recording starts). */
    @Volatile var lastDir: File? = null

    private fun prefs() = ContextUtil.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var nextIndex: Int
        get() = prefs().getInt(KEY_NEXT, 0)
        set(v) { prefs().edit().putInt(KEY_NEXT, v).apply() }

    /** Back to episode 1. Does not delete any recorded folders. */
    fun reset() {
        nextIndex = 0
        lastDir = null
    }

    fun current(): Setup? = if (nextIndex < PLAN_SIZE) setups.getOrNull(nextIndex) else null

    fun describe(): String {
        val s = current() ?: return "Plan finished: all $PLAN_SIZE episodes done"
        return "Episode ${s.number}/$PLAN_SIZE (repeat ${s.repeat})\n" +
                "PAD ${s.padName}   DRONE ${s.droneName}\n" +
                "Pad: ${dirText(s.east, s.north)}"
    }

    private fun dirText(e: Int, n: Int): String {
        val parts = ArrayList<String>()
        if (n != 0) parts.add("${Math.abs(n)} ${if (n > 0) "north" else "south"}")
        if (e != 0) parts.add("${Math.abs(e)} ${if (e > 0) "east" else "west"}")
        return parts.joinToString(", ")
    }

    /** Running number of an episode folder (episode_0037_...) as text, e.g. "0037". */
    fun numberOf(dir: File): String =
        Regex("^episode_(\\d+)").find(dir.name)?.groupValues?.get(1) ?: "0000"

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Written when recording starts. */
    fun writeSetup(dir: File, stamp: String) {
        val s = current()
        val json = StringBuilder("{\n")
        json.append("  \"started\": \"$stamp\",\n")
        if (s != null) {
            json.append("  \"plan_number\": ${s.number},\n")
            json.append("  \"repeat\": ${s.repeat},\n")
            json.append("  \"pad\": \"${s.padName}\",\n")
            json.append("  \"drone\": \"${s.droneName}\",\n")
            json.append("  \"pad_east_grid\": ${s.east},\n")
            json.append("  \"pad_north_grid\": ${s.north},\n")
        }
        json.append("  \"gimbal_pitch_target\": $GIMBAL_PITCH_DEG,\n")
        json.append("  \"speed_mode\": \"$SPEED_MODE\",\n")
        json.append("  \"start_heading\": \"$START_HEADING\"\n}\n")
        File(dir, "${numberOf(dir)}_setup.json").writeText(json.toString())
    }

    /**
     * Called by the OK / BAD buttons. Writes result.txt into the last episode folder.
     * OK moves on to the next setup, BAD keeps the same setup so you fly it again.
     * Returns the folder, or null if nothing was recorded or recording is still running.
     */
    fun finish(ok: Boolean): File? {
        val dir = lastDir ?: return null
        if (recording) return null
        File(dir, "${numberOf(dir)}_result.txt").writeText(if (ok) "success\n" else "fail\n")
        if (ok) nextIndex = nextIndex + 1
        lastDir = null
        return dir
    }
}