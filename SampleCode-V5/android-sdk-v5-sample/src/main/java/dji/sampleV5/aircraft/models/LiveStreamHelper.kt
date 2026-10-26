package dji.sampleV5.aircraft.models

import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamStatusListener
import dji.v5.manager.datacenter.livestream.LiveStreamStatus
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings
import dji.v5.common.error.IDJIError

/**
 * Standalone camera-live-stream helper. Nothing to do with VirtualStickVM --
 * pushes the drone's camera feed directly to an RTMP server on your laptop,
 * independent of whatever screen the app is currently showing.
 *
 * Usage:
 *   LiveStreamHelper.start("rtmp://<your-laptop-ip>:1935/live")
 *   LiveStreamHelper.stop()
 *
 * View on the laptop with:
 *   vlc rtmp://127.0.0.1:1935/live
 * (or whatever IP/path you started it with, from the laptop's own side)
 */
object LiveStreamHelper {

    private const val MAX_RETRIES = 30
    private const val RETRY_DELAY_MS = 2000L
    private var hasStarted = false

    /**
     * Call this ONCE, e.g. from your main Activity's onCreate(). It is not
     * tied to any button, command, or screen -- it keeps retrying in the
     * background until the aircraft/camera is ready, starts the stream,
     * and then just stays running for the rest of the app's lifetime.
     * No further calls needed.
     */
    fun startAutomatically(rtmpUrl: String, onStatus: (String) -> Unit = {}) {
        if (hasStarted) return  // never start a second time by accident
        hasStarted = true
        attemptStart(rtmpUrl, 0, onStatus)
    }

    private fun attemptStart(rtmpUrl: String, retryCount: Int, onStatus: (String) -> Unit) {
        val liveStreamManager = try {
            MediaDataCenter.getInstance().liveStreamManager
        } catch (e: Exception) {
            null
        }

        if (liveStreamManager == null) {
            if (retryCount < MAX_RETRIES) {
                onStatus("Waiting for aircraft/camera to be ready... (attempt ${retryCount + 1})")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    attemptStart(rtmpUrl, retryCount + 1, onStatus)
                }, RETRY_DELAY_MS)
            } else {
                onStatus("Gave up waiting for aircraft/camera after $MAX_RETRIES attempts")
            }
            return
        }

        start(rtmpUrl, onStatus)
    }

    fun start(rtmpUrl: String, onStatus: (String) -> Unit = {}) {
        val liveStreamManager = MediaDataCenter.getInstance().liveStreamManager

        liveStreamManager.addLiveStreamStatusListener(object : LiveStreamStatusListener {
            override fun onLiveStreamStatusUpdate(status: LiveStreamStatus?) {
                onStatus("Streaming: ${status?.isStreaming}, packetLoss: ${status?.packetLoss}")
            }

            override fun onError(error: IDJIError?) {
                onStatus("Live stream error: ${error?.description()}")
            }
        })

        liveStreamManager.liveStreamSettings = LiveStreamSettings.Builder()
            .setLiveStreamType(LiveStreamType.RTMP)
            .setRtmpSettings(RtmpSettings.Builder().setUrl(rtmpUrl).build())
            .build()

        liveStreamManager.startStream(object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                onStatus("Live stream started successfully")
            }

            override fun onFailure(error: IDJIError) {
                onStatus("Failed to start live stream: ${error.description()}")
            }
        })
    }

    fun stop(onStatus: (String) -> Unit = {}) {
        MediaDataCenter.getInstance().liveStreamManager.stopStream(object :
            dji.v5.common.callback.CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                onStatus("Live stream stopped")
            }

            override fun onFailure(error: IDJIError) {
                onStatus("Failed to stop live stream: ${error.description()}")
            }
        })
    }
}