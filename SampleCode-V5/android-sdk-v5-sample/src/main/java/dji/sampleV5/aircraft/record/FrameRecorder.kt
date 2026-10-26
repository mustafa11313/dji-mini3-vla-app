package dji.sampleV5.aircraft.recorder

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Saves live-camera-stream frames as JPEGs, exactly ONE per telemetry row:
 *
 *     <episode dir>/frames/0037_000000.jpg, 0037_000001.jpg, ... (0037 = episode number)
 *
 * The stream delivers frames at its own rate (~30 fps). This class only keeps the
 * NEWEST one in memory. TelemetryRecorder calls onTick(timeMs) before it writes a row
 * (every 100 ms). If there is a fresh frame, onTick saves it as the next numbered JPEG
 * and returns true, and the row is written. Otherwise it returns false and the row is
 * skipped. So row n of telemetry.csv always belongs to frame n.
 *
 * Usage:
 *     telemetry.rowGate = { t -> frames.onTick(t) }
 *     telemetry.start(...)
 *     frames.start(telemetry.episodeDir!!)
 *     ...
 *     frames.stop(); telemetry.stop()
 */
class FrameRecorder {

    companion object {
        const val JPEG_QUALITY = 90
        const val MAX_QUEUE = 4                 // frames waiting to be compressed
        const val MAX_AGE_MS = 100L             // older frames are stale copies (stream stalled)
    }

    @Volatile private var active = false
    private var listener: ICameraStreamManager.CameraFrameListener? = null
    private var executor: ThreadPoolExecutor? = null
    private var framesDir: File? = null
    private var frameIdx = 0
    private var epNumber = "0000"

    // Newest frame from the stream (guarded by lock). Buffer is reused: no allocation per frame.
    private val lock = Any()
    private var buf = ByteArray(0)
    private var bufLen = 0
    private var bufW = 0
    private var bufH = 0
    private var bufArrivalNs = 0L

    @Synchronized
    fun start(episodeDir: File) {
        if (active) return
        frameIdx = 0
        epNumber = Regex("^episode_(\\d+)").find(episodeDir.name)?.groupValues?.get(1) ?: "0000"
        synchronized(lock) { bufLen = 0 }
        framesDir = File(episodeDir, "frames").apply { mkdirs() }
        // Single worker thread. onTick checks there is room in the queue before submitting.
        executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(MAX_QUEUE)
        )
        active = true

        val l = object : ICameraStreamManager.CameraFrameListener {
            override fun onFrame(
                frameData: ByteArray,
                offset: Int,
                length: Int,
                width: Int,
                height: Int,
                format: ICameraStreamManager.FrameFormat
            ) {
                if (!active) return
                val now = SystemClock.elapsedRealtimeNanos()
                synchronized(lock) {
                    if (buf.size < length) buf = ByteArray(length)
                    System.arraycopy(frameData, offset, buf, 0, length)
                    bufLen = length
                    bufW = width
                    bufH = height
                    bufArrivalNs = now
                }
            }
        }
        listener = l
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
            ComponentIndexType.LEFT_OR_MAIN,
            ICameraStreamManager.FrameFormat.NV21,
            l
        )
    }

    /**
     * Called by TelemetryRecorder before it writes a row. Returns true if a fresh frame was
     * queued for saving (the row may then be written), false if the row must be skipped.
     */
    fun onTick(timeMs: Long): Boolean {
        if (!active) return false
        val ex = executor ?: return false
        if (ex.queue.remainingCapacity() == 0) return false      // JPEG saving is behind
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val copy: ByteArray
        val w: Int
        val h: Int
        synchronized(lock) {
            if (bufLen == 0) return false                        // no frame has arrived yet
            if ((nowNs - bufArrivalNs) / 1_000_000 > MAX_AGE_MS) return false   // stale
            copy = buf.copyOf(bufLen)
            w = bufW
            h = bufH
        }
        val name = String.format(Locale.US, "%s_%06d.jpg", epNumber, frameIdx++)
        ex.execute { save(copy, w, h, name) }
        return true
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        listener?.let { MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(it) }
        listener = null
        executor?.shutdown()
        executor?.awaitTermination(5, TimeUnit.SECONDS)
        executor = null
    }

    /** Runs on the single worker thread. */
    private fun save(nv21: ByteArray, width: Int, height: Int, name: String) {
        try {
            FileOutputStream(File(framesDir, name)).use { out ->
                YuvImage(nv21, ImageFormat.NV21, width, height, null)
                    .compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
            }
        } catch (_: Exception) {
            // Never let one bad frame kill the worker.
        }
    }
}