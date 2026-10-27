package dji.sampleV5.aircraft.vla

/**
 * Where is the red pad in the picture? (idea M5) Done on the phone, on the raw NV21 camera frame,
 * so it costs about 1 ms and needs no PC.
 *
 * A pixel counts as "pad red" when its colour difference Cr is high and Cb is low (YUV), on a
 * grid of every STEP-th pixel. The box around those pixels is the pad. Same idea as the PC's
 * brain/phases.py and brain/servo.py, so phone and PC agree on what "pad in view" means.
 *
 * The result goes:
 *  - into every frame header to the PC ("pad"), so the logs show what the phone saw,
 *  - onto the screen (pad left / right / centre, and how big), so the pilot sees whether the
 *    camera has the pad before pressing MODEL FLY.
 *
 * x, y are -1..1 (0 = picture centre, y = +1 bottom); size is the share of the picture covered.
 * Thresholds are for the lab pad under indoor light; check them once on the screen.
 */
object PadFinder {
    private const val STEP = 8
    private const val CR_MIN = 165       // V (Cr), 128 = no colour
    private const val CB_MAX = 125       // U (Cb)
    private const val Y_MIN = 40         // ignore very dark pixels (noise)
    private const val MIN_HITS = 12      // fewer grid hits = no pad

    class Pad(val visible: Boolean, val x: Double, val y: Double, val size: Double)

    @Volatile var last = Pad(false, 0.0, 0.0, 0.0); private set

    /** nv21: one camera frame, width x height. */
    fun find(nv21: ByteArray, width: Int, height: Int): Pad {
        if (width <= 0 || height <= 0 || nv21.size < width * height * 3 / 2) return last
        var hits = 0
        var minX = width; var maxX = 0; var minY = height; var maxY = 0
        var sumX = 0L; var sumY = 0L
        val uv = width * height
        var y = 0
        while (y < height) {
            val row = uv + (y / 2) * width
            var x = 0
            while (x < width) {
                val lum = nv21[y * width + x].toInt() and 0xFF
                val i = row + (x / 2) * 2              // NV21: V then U for every 2x2 block
                val cr = nv21[i].toInt() and 0xFF
                val cb = nv21[i + 1].toInt() and 0xFF
                if (lum >= Y_MIN && cr >= CR_MIN && cb <= CB_MAX) {
                    hits++
                    sumX += x; sumY += y
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += STEP
            }
            y += STEP
        }
        val grid = ((width + STEP - 1) / STEP) * ((height + STEP - 1) / STEP)
        val pad = if (hits < MIN_HITS) Pad(false, 0.0, 0.0, 0.0) else Pad(
            true,
            (sumX.toDouble() / hits) / width * 2 - 1,
            (sumY.toDouble() / hits) / height * 2 - 1,
            hits.toDouble() / grid)
        last = pad
        return pad
    }

    /** Short text for the screen. */
    fun describe(p: Pad = last): String {
        if (!p.visible) return "pad: not in view"
        val side = when {
            p.x < -0.2 -> "left"
            p.x > 0.2 -> "right"
            else -> "centre"
        }
        val depth = when {
            p.y < -0.3 -> "far"
            p.y > 0.4 -> "close"
            else -> "middle"
        }
        return "pad: $side, $depth, ${"%.1f".format(p.size * 100)} % of view"
    }
}
