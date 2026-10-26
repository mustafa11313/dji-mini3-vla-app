package dji.sampleV5.aircraft.vla

import android.content.Context
import dji.v5.utils.common.ContextUtil

/**
 * The PC's address, in ONE place (it used to be hard-coded in three files).
 * Change it in the app with the command box: "pc 192.168.1.20", then Execute Command.
 * Kept in SharedPreferences, so it survives app restarts.
 */
object LabSettings {
    private const val PREFS = "lab_settings"
    private const val KEY_PC_HOST = "pc_host"
    private const val DEFAULT_PC_HOST = "172.24.14.216"

    const val HTTP_PORT = 5000      // upload_server.py / replay.py
    const val RTMP_PORT = 1935      // MediaMTX
    const val BRIDGE_PORT = 8765    // brain/bridge_server.py

    private fun prefs() = ContextUtil.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var pcHost: String
        get() = prefs().getString(KEY_PC_HOST, DEFAULT_PC_HOST) ?: DEFAULT_PC_HOST
        set(v) { prefs().edit().putString(KEY_PC_HOST, v.trim()).apply() }

    val httpBase get() = "http://$pcHost:$HTTP_PORT"
    val rtmpUrl get() = "rtmp://$pcHost:$RTMP_PORT/live"
    val bridgeUrl get() = "ws://$pcHost:$BRIDGE_PORT"
}
