package dji.sampleV5.aircraft.vla

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The phone side of the PC brain (brain/bridge_server.py, protocol in brain/protocol.py).
 *
 * 10 times per second: the newest camera frame (JPEG) + telemetry go to the PC as ONE binary
 * WebSocket message. Stick commands come back as JSON and go straight to StickController,
 * which decides whether they are shown (shadow) or flown (active + MODEL FLY).
 *
 * Only the newest frame is sent; if the network is behind (more than MAX_QUEUE_BYTES waiting),
 * frames are skipped instead of queued, so the model never works on old pictures.
 * Reconnects by itself every 2 s while switched on.
 */
object BridgeClient {
    private const val RATE_HZ = 10L
    private const val JPEG_QUALITY = 80
    private const val MAX_FRAME_AGE_MS = 150L
    private const val MAX_QUEUE_BYTES = 400_000L
    private const val RECONNECT_MS = 2000L

    @Volatile private var running = false
    private var socket: WebSocket? = null
    private var executor: ScheduledExecutorService? = null
    private var listener: ICameraStreamManager.CameraFrameListener? = null
    private val http = OkHttpClient.Builder().pingInterval(2, TimeUnit.SECONDS).build()
    private var seq = 0L

    // Newest frame from the stream (guarded by lock), same idea as FrameRecorder.
    private val lock = Any()
    private var buf = ByteArray(0)
    private var bufLen = 0
    private var bufW = 0
    private var bufH = 0
    private var bufAtMs = 0L

    @Volatile var onLog: ((String) -> Unit)? = null

    @Synchronized
    fun connect() {
        if (running) return
        running = true
        StickController.start()
        StickController.onEvent = { name, detail -> sendEvent(name, detail) }
        val l = object : ICameraStreamManager.CameraFrameListener {
            override fun onFrame(data: ByteArray, offset: Int, length: Int, width: Int, height: Int,
                                 format: ICameraStreamManager.FrameFormat) {
                synchronized(lock) {
                    if (buf.size < length) buf = ByteArray(length)
                    System.arraycopy(data, offset, buf, 0, length)
                    bufLen = length; bufW = width; bufH = height
                    bufAtMs = SystemClock.elapsedRealtime()
                }
            }
        }
        listener = l
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
            ComponentIndexType.LEFT_OR_MAIN, ICameraStreamManager.FrameFormat.NV21, l)
        executor = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate({ sendFrame() }, 0, 1000 / RATE_HZ, TimeUnit.MILLISECONDS)
        }
        open()
    }

    @Synchronized
    fun disconnect() {
        if (!running) return
        running = false
        StickController.emergencyStop()
        listener?.let { MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(it) }
        listener = null
        executor?.shutdownNow()
        executor = null
        socket?.close(1000, "bridge off")
        socket = null
        StickController.connected = false
    }

    private fun open() {
        if (!running) return
        val url = LabSettings.bridgeUrl
        log("Connecting to $url")
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                StickController.connected = true
                ws.send(JSONObject().put("type", "hello").put("app", "dji-mini3-vla-app")
                    .put("stick_mode", 2).toString())
                log("Connected to PC brain")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val m = JSONObject(text)
                    when (m.optString("type")) {
                        "config" -> StickController.onConfig(
                            m.optString("mode", "shadow"), doubles(m.optJSONArray("limits_pct")),
                            if (m.has("land_height_m")) m.getDouble("land_height_m") else null)
                        "cmd" -> doubles(m.optJSONArray("sticks"))?.let {
                            StickController.onCommand(it, m.optString("mode", "shadow"), m.optLong("t_ms"))
                        }
                    }
                } catch (e: Exception) {
                    log("Bad message from PC: ${e.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) = lost("closed: $reason")
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = lost("failed: ${t.message}")
        })
    }

    private fun lost(why: String) {
        StickController.connected = false        // the watchdog hands control back within 0.5-3 s
        log("PC link $why")
        if (running) executor?.schedule({ open() }, RECONNECT_MS, TimeUnit.MILLISECONDS)
    }

    private fun sendFrame() {
        try {
            val ws = socket ?: return
            if (!StickController.connected || ws.queueSize() > MAX_QUEUE_BYTES) return
            val copy: ByteArray
            val w: Int
            val h: Int
            val atMs: Long
            synchronized(lock) {
                if (bufLen == 0 || SystemClock.elapsedRealtime() - bufAtMs > MAX_FRAME_AGE_MS) return
                copy = buf.copyOf(bufLen); w = bufW; h = bufH; atMs = bufAtMs
            }
            val jpeg = ByteArrayOutputStream(200_000).also {
                YuvImage(copy, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, it)
            }.toByteArray()
            val v = LiveTelemetry.velocity
            val header = JSONObject()
                .put("type", "frame").put("seq", ++seq).put("t_ms", atMs)
                .put("telemetry", JSONObject()
                    .put("height_m", LiveTelemetry.heightM)
                    .put("vx", v?.x ?: 0.0).put("vy", v?.y ?: 0.0).put("vz", v?.z ?: 0.0)
                    .put("heading", LiveTelemetry.headingDeg())
                    .put("pilot", JSONArray(LiveTelemetry.pilotPct().toList()))
                    .put("flying", LiveTelemetry.flying)
                    .put("vs_enabled", StickController.armed))
                .toString().toByteArray()
            val msg = ByteBuffer.allocate(4 + header.size + jpeg.size)
                .putInt(header.size).put(header).put(jpeg).array()     // big-endian length first
            ws.send(msg.toByteString())
        } catch (e: Exception) {
            // never let one bad frame stop the loop
        }
    }

    fun sendEvent(name: String, detail: String) {
        socket?.send(JSONObject().put("type", "event").put("name", name).put("detail", detail)
            .put("seq", seq).toString())
    }

    private fun doubles(a: JSONArray?): DoubleArray? = a?.let { DoubleArray(it.length()) { i -> it.getDouble(i) } }

    private fun log(s: String) {
        android.util.Log.i("BridgeClient", s)
        onLog?.invoke(s)
    }
}
