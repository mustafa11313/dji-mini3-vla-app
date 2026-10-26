package dji.sampleV5.aircraft.recorder

import java.io.File
import kotlin.math.abs

/**
 * Check before you pack up (idea D2): right after the landing, the phone runs the same checks
 * as the PC's process_episode.py, so a bad episode is flown again while the pad is still in
 * place, not found days later on the PC.
 *
 *  - telemetry rows == saved frames
 *  - no gaps: rows are 100 ms apart; a gap above 150 ms is counted, above 500 ms fails
 *  - flown part (first stick input .. AUTO_LANDING, same as csv_to_hdf5.trim) at least 3 s
 *  - camera angle: moved at most 1 deg in the flown part, and within 3 deg of the plan
 *  - stick values within -100..100 %
 *
 * Result: <nr>_phone_check.txt in the episode folder and a one-line summary (OK / CHECK: ...).
 * It is advice only: SUCCESS / FAIL stay the pilot's decision.
 */
object EpisodeCheck {
    private const val GAP_MS = 150L
    private const val BAD_GAP_MS = 500L
    private const val MIN_FLOWN_ROWS = 30
    private const val GIMBAL_SPREAD_DEG = 1.0
    private const val GIMBAL_TARGET_TOL_DEG = 3.0

    class Result(val ok: Boolean, val summary: String)

    /** Reads the episode folder; safe to call from any background thread. */
    fun run(dir: File): Result = try {
        check(dir)
    } catch (e: Exception) {
        finish(dir, listOf("check could not run: ${e.message}"), emptyList())
    }

    private fun check(dir: File): Result {
        val problems = ArrayList<String>()
        val notes = ArrayList<String>()
        val csv = dir.listFiles()?.firstOrNull { it.name.contains("_telemetry_") && it.name.endsWith(".csv") }
            ?: return finish(dir, listOf("no telemetry CSV"), notes)
        val lines = csv.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return finish(dir, listOf("telemetry CSV is empty"), notes)
        val head = lines[0].split(",")
        val col = head.withIndex().associate { it.value to it.index }
        val rows = lines.drop(1).map { it.split(",") }.filter { it.size == head.size }

        val frames = File(dir, "frames").listFiles()?.count { it.name.endsWith(".jpg") } ?: 0
        if (frames != rows.size) problems.add("rows ${rows.size} != frames $frames")

        val t = rows.mapNotNull { it[col.getValue("time_ms")].toLongOrNull() }
        val gaps = t.zipWithNext { a, b -> b - a }
        val maxGap = gaps.maxOrNull() ?: 0L
        val nGaps = gaps.count { it > GAP_MS }
        if (maxGap > BAD_GAP_MS) problems.add("gap of $maxGap ms")
        else if (nGaps > 0) notes.add("$nGaps small gaps (max $maxGap ms)")

        val cmd = listOf("pitch_cmd", "roll_cmd", "throttle_cmd", "yaw_cmd").map { col.getValue(it) }
        val sticks = rows.map { r -> cmd.map { r[it].toIntOrNull() ?: 0 } }
        if (sticks.any { s -> s.any { abs(it) > 100 } }) problems.add("stick value above 100 %")

        // flown part, as csv_to_hdf5.trim: first stick input .. start of AUTO_LANDING
        val start = sticks.indexOfFirst { s -> s.any { it != 0 } }.coerceAtLeast(0)
        val landing = rows.indexOfFirst { it[col.getValue("flight_mode")] == "AUTO_LANDING" }
        if (landing < 0) notes.add("no AUTO_LANDING: the whole flight is kept")
        val end = if (landing >= 0) landing else rows.size
        val flown = if (end > start) rows.subList(start, end) else emptyList()
        if (flown.size < MIN_FLOWN_ROWS) problems.add("only ${flown.size} flown rows (${flown.size / 10.0} s)")

        val gim = flown.mapNotNull { it[col.getValue("gimbal_pitch")].toDoubleOrNull() }
        if (gim.isNotEmpty()) {
            val spread = (gim.maxOrNull() ?: 0.0) - (gim.minOrNull() ?: 0.0)
            if (spread > GIMBAL_SPREAD_DEG) problems.add("camera moved ${"%.1f".format(spread)} deg")
            val median = gim.sorted()[gim.size / 2]
            if (abs(median - EpisodePlan.GIMBAL_PITCH_DEG) > GIMBAL_TARGET_TOL_DEG) {
                problems.add("camera at ${"%.1f".format(median)} deg, plan ${EpisodePlan.GIMBAL_PITCH_DEG.toInt()}")
            }
        }
        notes.add("${rows.size} rows, flown ${flown.size / 10.0} s")
        return finish(dir, problems, notes)
    }

    private fun finish(dir: File, problems: List<String>, notes: List<String>): Result {
        val ok = problems.isEmpty()
        val summary = (if (ok) "OK" else "CHECK: " + problems.joinToString("; ")) +
                if (notes.isNotEmpty()) " (" + notes.joinToString("; ") + ")" else ""
        try {
            File(dir, "${EpisodePlan.numberOf(dir)}_phone_check.txt").writeText("${dir.name} | $summary\n")
        } catch (_: Exception) {
        }
        return Result(ok, summary)
    }
}
