package dji.sampleV5.aircraft.vla

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Model next to the pilot (ideas M3 and M1), drawn like the two RC sticks (Mode 2):
 * left box = throttle (up/down) and yaw (left/right), right box = pitch (up = forward) and
 * roll (right = right).
 *
 *  - grey dot: the pilot's real stick
 *  - blue dot + arrow from the centre: what the model wants (its intent)
 *  - bars under the boxes: how sure the model is per stick (spread, stick percent):
 *    green below 8, yellow below 15, red above. No bars when the model gives no confidence.
 *  - "UNSURE" in red when the PC hovered instead of obeying (bridge --max-spread).
 *
 * In shadow mode this shows live where the model agrees with you and where it doesn't.
 */
class ModelOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pilot = IntArray(4)            // percent [pitch, roll, throttle, yaw]
    private var model = DoubleArray(4)
    private var spread: DoubleArray? = null
    private var armed = false
    private var unsure = false

    private val density = resources.displayMetrics.density
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.GRAY; strokeWidth = density }
    private val pilotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY }
    private val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(66, 165, 245); strokeWidth = 3 * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10 * density }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    fun update(s: StickController.Status) {
        pilot = s.pilotPct.copyOf()
        model = s.modelPct.copyOf()
        spread = s.spreadPct?.copyOf()
        armed = s.armed
        unsure = s.unsure
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((220 * density).toInt(), widthMeasureSpec)
        setMeasuredDimension(w, resolveSize((w * 0.62f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = 8 * density
        val side = min((width - gap) / 2f, height - 34 * density)
        modelPaint.color = if (armed) Color.rgb(66, 165, 245) else Color.rgb(144, 202, 249)
        // left: x = yaw, y = throttle; right: x = roll, y = pitch (up = forward)
        drawBox(canvas, 0f, side, pilot[3].toDouble(), pilot[2].toDouble(), model[3], model[2], "T / Y")
        drawBox(canvas, side + gap, side, pilot[1].toDouble(), pilot[0].toDouble(), model[1], model[0], "P / R")
        drawBars(canvas, side + 4 * density, width.toFloat())
        if (unsure) {
            text.color = Color.rgb(239, 83, 80)
            canvas.drawText("UNSURE", width - 48 * density, 10 * density, text)
            text.color = Color.WHITE
        }
    }

    private fun drawBox(c: Canvas, left: Float, side: Float, px: Double, py: Double,
                        mx: Double, my: Double, label: String) {
        val cx = left + side / 2
        val cy = side / 2
        val r = side / 2 - 4 * density
        c.drawRect(left + 1, 1f, left + side - 1, side - 1, frame)
        c.drawLine(cx, cy - r, cx, cy + r, frame)
        c.drawLine(cx - r, cy, cx + r, cy, frame)
        c.drawText(label, left + 3 * density, side - 3 * density, text)
        c.drawCircle(cx + (px / 100 * r).toFloat(), cy - (py / 100 * r).toFloat(), 5 * density, pilotPaint)
        val mxp = cx + (mx / 100 * r).toFloat()
        val myp = cy - (my / 100 * r).toFloat()
        c.drawLine(cx, cy, mxp, myp, modelPaint)
        c.drawCircle(mxp, myp, 4 * density, modelPaint)
    }

    private fun drawBars(c: Canvas, top: Float, w: Float) {
        val sp = spread ?: return
        val names = arrayOf("P", "R", "T", "Y")
        val cell = w / 4
        val h = 6 * density
        for (i in 0 until 4) {
            val v = sp[i]
            bar.color = when {
                v < 8 -> Color.rgb(102, 187, 106)
                v < 15 -> Color.rgb(255, 202, 40)
                else -> Color.rgb(239, 83, 80)
            }
            val x0 = i * cell + 12 * density
            val full = cell - 16 * density
            // full bar = sure; it shrinks as the spread grows (30 % spread = empty)
            val len = (full * (1 - min(v, 30.0) / 30.0)).toFloat().coerceAtLeast(2 * density)
            c.drawText(names[i], i * cell + 2 * density, top + h, text)
            c.drawRect(x0, top, x0 + len, top + h, bar)
            c.drawText("${v.toInt()}", x0 + full - 12 * density, top + h + 11 * density, text)
        }
    }
}
