package dji.sampleV5.aircraft.vla

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sampleV5.aircraft.recorder.EpisodePlan
import dji.v5.et.create
import dji.v5.et.get

/**
 * Checklist before the model may fly. MODEL FLY stays locked until every line is OK.
 * Takeoff is NOT done by the model (it was automatic in every episode): take off and hover first.
 */
object PreflightCheck {
    class Item(val name: String, val ok: Boolean, val detail: String)

    const val MIN_BATTERY_PCT = 30
    const val GIMBAL_TOLERANCE_DEG = 3.0

    fun run(): List<Item> {
        val battery = BatteryKey.KeyChargeRemainingInPercent.create().get(0) ?: 0
        val gimbal = GimbalKey.KeyGimbalAttitude.create(ComponentIndexType.LEFT_OR_MAIN).get(Attitude(0.0, 0.0, 0.0)) ?: Attitude(0.0, 0.0, 0.0)
        val gimbalOk = Math.abs(gimbal.pitch - EpisodePlan.GIMBAL_PITCH_DEG) <= GIMBAL_TOLERANCE_DEG
        val sticksCentred = LiveTelemetry.maxAbsStick() < 0.05 * LiveTelemetry.STICK_MAX
        return listOf(
            Item("PC connected", StickController.connected, LabSettings.bridgeUrl),
            Item("PC in active mode", StickController.mode == StickController.Mode.ACTIVE,
                "now ${StickController.mode.name.lowercase()}, start bridge_server with --mode active"),
            Item("Flying", LiveTelemetry.flying, "take off and hover first"),
            Item("Above landing height", LiveTelemetry.heightM > StickController.landHeightM + 0.3,
                "height ${"%.1f".format(LiveTelemetry.heightM)} m"),
            Item("Battery", battery >= MIN_BATTERY_PCT, "$battery %, need $MIN_BATTERY_PCT %"),
            Item("Camera angle", gimbalOk,
                "${"%.0f".format(gimbal.pitch)} deg, training data used ${EpisodePlan.GIMBAL_PITCH_DEG.toInt()} deg"),
            Item("Sticks centred", sticksCentred, "let go of the sticks"),
        )
    }
}
