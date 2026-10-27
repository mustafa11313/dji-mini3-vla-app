package dji.sampleV5.aircraft.pages

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import dji.sampleV5.aircraft.databinding.FragVirtualStickPageBinding
import dji.sampleV5.aircraft.keyvalue.KeyValueDialogUtil
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.SimulatorVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.recorder.EpisodePlan
import dji.sampleV5.aircraft.recorder.EpisodeUploader
import dji.sampleV5.aircraft.recorder.RecordingController
import dji.sampleV5.aircraft.util.Helper
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sampleV5.aircraft.vla.BridgeClient
import dji.sampleV5.aircraft.vla.LabSettings
import dji.sampleV5.aircraft.vla.Speaker
import dji.sampleV5.aircraft.vla.StickController
import dji.sampleV5.aircraft.vla.VoiceCommands
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystick
import dji.sampleV5.aircraft.virtualstick.OnScreenJoystickListener
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.utils.common.JsonUtil
import kotlin.math.abs

/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/5/11
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class VirtualStickFragment : DJIFragment() {

    private val basicAircraftControlVM: BasicAircraftControlVM by activityViewModels()
    private val virtualStickVM: VirtualStickVM by activityViewModels()
    private val simulatorVM: SimulatorVM by activityViewModels()
    private var binding: FragVirtualStickPageBinding? = null
    private val deviation: Double = 0.02

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragVirtualStickPageBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.widgetHorizontalSituationIndicator?.setSimpleModeEnable(false)
        Speaker.init(requireContext())
        initBtnClickListener()
        initStickListener()
        virtualStickVM.listenRCStick()
        basicAircraftControlVM.listenForLandingConfirmation()
        // The PC address is set in the app: type "pc <ip>" and press Execute Command.
        basicAircraftControlVM.listenForRecordingAndStartStream(LabSettings.rtmpUrl) { status ->
            ToastUtils.showToast("[Stream] $status")
            android.util.Log.i("LiveStreamHelper", status)
        }
        basicAircraftControlVM.listenForRecordingAndRecordTelemetry()
        initEpisodePanel()
        initModelPanel()
        virtualStickVM.currentSpeedLevel.observe(viewLifecycleOwner) {
            updateVirtualStickInfo()
        }
        virtualStickVM.useRcStick.observe(viewLifecycleOwner) {
            updateVirtualStickInfo()
        }
        virtualStickVM.currentVirtualStickStateInfo.observe(viewLifecycleOwner) {
            updateVirtualStickInfo()
        }
        virtualStickVM.stickValue.observe(viewLifecycleOwner) {
            updateVirtualStickInfo()
        }
        virtualStickVM.virtualStickAdvancedParam.observe(viewLifecycleOwner) {
            updateVirtualStickInfo()
        }
        simulatorVM.simulatorStateSb.observe(viewLifecycleOwner) {
            binding?.simulatorStateInfoTv?.text = it
        }
        // Shows the raw JSON of the last command received (row number,
        // pitch/roll/yaw/throttle) -- populated by stepReplay(), resetReplay(),
        // and getCommandFromServer() in VirtualStickVM.
        virtualStickVM.lastStepInfo.observe(viewLifecycleOwner) {
            if (it.isNotBlank()) {
                ToastUtils.showToast(it)
            }
        }
        // Replay reached the end of the CSV (either a manual step or the
        // automatic loop) -- land automatically instead of needing a
        // separate manual button press.
        virtualStickVM.replayFinished.observe(viewLifecycleOwner) { finished ->
            if (finished) {
                ToastUtils.showToast("Replay finished -- landing...")
                basicAircraftControlVM.startLanding(object :
                    CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(t: EmptyMsg?) {
                        ToastUtils.showToast("Landing started successfully")
                        virtualStickVM.stopCsvLogging()
                    }

                    override fun onFailure(error: IDJIError) {
                        ToastUtils.showToast("Landing failed: $error -- land manually!")
                    }
                })
                virtualStickVM.resetReplayFinishedFlag()
            }
        }
    }

    // ---- model loop: status, MODEL FLY, STOP (see vla/StickController.kt) ----
    private fun initModelPanel() {
        StickController.start()
        StickController.onStatus = { s ->
            val link = if (s.connected) "link ok" else "no link"
            val delay = if (s.delayMs >= 0) "${s.delayMs} ms" else "-"
            val m = s.modelPct
            val p = s.pilotPct
            binding?.modelTv?.text = "Model: ${s.mode}${if (s.armed) " FLYING" else ""} | $link | delay $delay\n" +
                    "model P ${m[0].toInt()} R ${m[1].toInt()} T ${m[2].toInt()} Y ${m[3].toInt()}\n" +
                    "pilot P ${p[0]} R ${p[1]} T ${p[2]} Y ${p[3]}\n${s.note}"
            binding?.modelOverlay?.update(s)
        }
        BridgeClient.onLog = { msg -> mainHandler.post { ToastUtils.showToast("[PC] $msg") } }
        binding?.btnModelFly?.setOnClickListener {
            virtualStickVM.stopReplay()      // a running replay must not fight the model
            StickController.arm { ok, msg -> mainHandler.post { ToastUtils.showToast(if (ok) msg else "MODEL FLY refused. $msg") } }
        }
        binding?.btnModelStop?.setOnClickListener { stopEverything() }
        binding?.btnStopCommand?.setOnClickListener { stopEverything() }
        initVoice()
    }

    // ---- voice: MIC = say the mission (V1), voice STOP (V7); see vla/VoiceCommands.kt ----
    private fun initVoice() {
        VoiceCommands.onHeard = { text -> mainHandler.post { binding?.voiceTv?.text = "Heard: $text" } }
        VoiceCommands.onProblem = { msg -> mainHandler.post { ToastUtils.showToast("[Voice] $msg") } }
        VoiceCommands.onMission = { text ->
            BridgeClient.instruction = text
            Speaker.say("Mission: $text")
            mainHandler.post { binding?.voiceTv?.text = "Mission: \"$text\" (type 'mission reset' for the default)" }
        }
        binding?.btnMic?.setOnClickListener {
            if (!hasMic()) return@setOnClickListener
            binding?.voiceTv?.text = "Listening... say the mission"
            VoiceCommands.listenForMission(requireContext())
        }
    }

    /** Microphone permission; asks for it once if missing. */
    private fun hasMic(): Boolean {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        ToastUtils.showToast("Allow the microphone, then try again")
        return false
    }

    private fun setVoiceStop(on: Boolean) {
        if (on && hasMic()) {
            VoiceCommands.startStopListener(requireContext())
            binding?.voiceTv?.text = "Voice STOP on: say \"stop\" or \"hover\""
        } else if (!on) {
            VoiceCommands.stopStopListener()
            binding?.voiceTv?.text = "Voice STOP off"
        }
    }

    /** Stops the model loop and any replay, and hovers. Works whatever is running. */
    private fun stopEverything() {
        StickController.emergencyStop()
        virtualStickVM.stopFromServerCommand()
        ToastUtils.showToast("STOP: model and replay stopped, hovering")
    }

    override fun onDestroyView() {
        // The voice STOP listener keeps running on purpose (it does not need this screen).
        StickController.onStatus = null
        BridgeClient.onLog = null
        VoiceCommands.onHeard = null
        VoiceCommands.onProblem = null
        VoiceCommands.onMission = null
        RecordingController.onEpisodeChecked = null
        super.onDestroyView()
    }

    // ---- recording plan: next setup on screen, SUCCESS / FAIL, upload to the PC ----
    private fun initEpisodePanel() {
        refreshPlanText()
        RecordingController.onEpisodeChecked = { dir, r ->
            mainHandler.post {
                binding?.epCheckTv?.text = "${dir.name.take(12)}: ${r.summary}"
                binding?.epCheckTv?.setTextColor(if (r.ok) Color.rgb(102, 187, 106) else Color.rgb(239, 83, 80))
                if (!r.ok) ToastUtils.showToast("Episode check: ${r.summary}")
            }
        }
        binding?.btnSetGimbal?.setOnClickListener {
            virtualStickVM.rotateGimbalTo(EpisodePlan.GIMBAL_PITCH_DEG)
            ToastUtils.showToast("Camera angle ${EpisodePlan.GIMBAL_PITCH_DEG.toInt()} deg (set it BEFORE takeoff)")
        }
        binding?.btnEpOk?.setOnClickListener { finishEpisode(true) }
        binding?.btnEpBad?.setOnClickListener { finishEpisode(false) }
        // RESET only works with a long press, so it cannot be hit by accident.
        binding?.btnEpReset?.setOnClickListener { ToastUtils.showToast("Hold RESET to start the plan over at episode 1") }
        binding?.btnEpReset?.setOnLongClickListener {
            EpisodePlan.reset()
            refreshPlanText()
            ToastUtils.showToast("Plan reset to episode 1")
            true
        }
    }

    private fun refreshPlanText() {
        binding?.planTv?.text = EpisodePlan.describe()
    }

    private fun finishEpisode(ok: Boolean) {
        if (EpisodePlan.recording) {
            ToastUtils.showToast("Still recording - land first")
            return
        }
        val dir = EpisodePlan.finish(ok)
        if (dir == null) {
            ToastUtils.showToast("No finished episode to mark")
            return
        }
        refreshPlanText()
        ToastUtils.showToast("${dir.name}: ${if (ok) "success" else "fail"} - uploading...")
        EpisodeUploader.upload(dir) { msg -> mainHandler.post { ToastUtils.showToast(msg) } }
    }

    private fun initBtnClickListener() {
        binding?.btnEnableVirtualStick?.setOnClickListener {
            virtualStickVM.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("enableVirtualStick success.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("enableVirtualStick error,$error")
                }
            })
        }
        binding?.btnDisableVirtualStick?.setOnClickListener {
            virtualStickVM.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    ToastUtils.showToast("disableVirtualStick success.")
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("disableVirtualStick error,${error})")
                }
            })
        }
        binding?.btnSetVirtualStickSpeedLevel?.setOnClickListener {
            val speedLevels = doubleArrayOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
            initPopupNumberPicker(Helper.makeList(speedLevels)) {
                virtualStickVM.setSpeedLevel(speedLevels[indexChosen[0]])
                resetIndex()
            }
        }
        binding?.btnTakeOff?.setOnClickListener {
            basicAircraftControlVM.startTakeOff(object :
                CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {
                    ToastUtils.showToast("start takeOff onSuccess.")
                    virtualStickVM.startCsvLogging(requireContext())
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("start takeOff onFailure,$error")
                }
            })
        }
        binding?.btnLanding?.setOnClickListener {
            basicAircraftControlVM.startLanding(object :
                CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(t: EmptyMsg?) {
                    ToastUtils.showToast("start landing onSuccess.")
                    virtualStickVM.stopCsvLogging()
                }

                override fun onFailure(error: IDJIError) {
                    ToastUtils.showToast("start landing onFailure,$error")
                }
            })
        }
        binding?.btnUseRcStick?.setOnClickListener {
            virtualStickVM.useRcStick.value = virtualStickVM.useRcStick.value != true
            if (virtualStickVM.useRcStick.value == true) {
                ToastUtils.showToast(
                    "After it is turned on," +
                            "the joystick value of the RC will be used as the left/ right stick value"
                )
            }
        }
        binding?.btnSetVirtualStickAdvancedParam?.setOnClickListener {
            KeyValueDialogUtil.showInputDialog(
                activity, "Set Virtual Stick Advanced Param",
                JsonUtil.toJson(virtualStickVM.virtualStickAdvancedParam.value), "", false
            ) {
                it?.apply {
                    val param = JsonUtil.toBean(this, VirtualStickFlightControlParam::class.java)
                    if (param == null) {
                        ToastUtils.showToast("Value Parse Error")
                        return@showInputDialog
                    }
                    virtualStickVM.virtualStickAdvancedParam.postValue(param)
                }
            }
        }
        binding?.btnSendVirtualStickAdvancedParam?.setOnClickListener {
            virtualStickVM.virtualStickAdvancedParam.value?.let {
                virtualStickVM.sendVirtualStickAdvancedParam(it)
            }
        }
        binding?.btnEnableVirtualStickAdvancedMode?.setOnClickListener {
            virtualStickVM.enableVirtualStickAdvancedMode()
        }
        binding?.btnDisableVirtualStickAdvancedMode?.setOnClickListener {
            virtualStickVM.disableVirtualStickAdvancedMode()
        }
        binding?.btnExecuteCommand?.setOnClickListener {
            val command = binding?.commandInput?.text.toString().trim().lowercase()

            if (command.startsWith("mission ")) {
                val text = command.removePrefix("mission ").trim()
                BridgeClient.instruction = if (text == "reset") null else text
                ToastUtils.showToast(if (text == "reset") "Mission: training sentence" else "Mission: $text")
                return@setOnClickListener
            }
            if (command.startsWith("pc ")) {
                LabSettings.pcHost = command.removePrefix("pc ").trim()
                ToastUtils.showToast("PC address set to ${LabSettings.pcHost} (restart the app for the live stream)")
                return@setOnClickListener
            }
            when (command) {
                "bridge on" -> {
                    BridgeClient.connect()
                    setVoiceStop(true)          // voice STOP comes with the model loop
                    ToastUtils.showToast("Connecting to the PC brain at ${LabSettings.bridgeUrl}")
                }
                "bridge off" -> {
                    BridgeClient.disconnect()
                    setVoiceStop(false)
                    ToastUtils.showToast("PC brain disconnected")
                }
                "listen on" -> setVoiceStop(true)
                "listen off" -> setVoiceStop(false)
                "voice on" -> { Speaker.enabled = true; ToastUtils.showToast("Spoken status on") }
                "voice off" -> { Speaker.enabled = false; ToastUtils.showToast("Spoken status off") }
                "replay" -> {
                    // Sends exactly ONE CSV row per click. Click again to
                    // advance to the next row. Type "reset" first to start
                    // from row 0.
                    virtualStickVM.stepReplay()
                }
                "reset" -> {
                    // Resets the server's row position back to 0, without
                    // moving the aircraft. Useful for manual step-through
                    // debugging via "replay". Not needed before "autoplay"
                    // -- that command takes off and resets on its own.
                    virtualStickVM.resetReplay()
                }
                "autoplay" -> {
                    // One command does the whole thing: takes off, waits to
                    // stabilize, replays the entire CSV automatically
                    // (100ms/row), and lands automatically once the CSV
                    // ends. No other command needed before or after this.
                    ToastUtils.showToast("Taking off...")
                    basicAircraftControlVM.startTakeOff(object :
                        CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                        override fun onSuccess(t: EmptyMsg?) {
                            ToastUtils.showToast("Takeoff accepted -- stabilizing...")
                            virtualStickVM.startCsvLogging(requireContext())

                            // Adjust this delay if your aircraft's takeoff
                            // climb takes noticeably longer or shorter than
                            // ~6 seconds to stabilize. startReplay() resets
                            // the row position to 0 internally before it
                            // starts polling, so no separate reset is needed.
                            // The app also starts the recording itself right after the
                            // takeoff has settled (so you do not switch pages), and the
                            // replay follows 1.5 s later. Landing stops the recording.
                            val handler = android.os.Handler(android.os.Looper.getMainLooper())
                            handler.postDelayed({
                                virtualStickVM.startRecording()
                                handler.postDelayed({
                                    virtualStickVM.startReplay()
                                }, 1500)
                            }, 6000)
                        }

                        override fun onFailure(error: IDJIError) {
                            ToastUtils.showToast("Takeoff failed: $error -- replay NOT started")
                        }
                    })
                }
                "stopreplay" -> {
                    virtualStickVM.stopReplay()
                }
                "stop" -> stopEverything()
                "forward", "backward", "left", "right" -> {
                    virtualStickVM.getCommandFromServer(command)
                }
                else -> {
                    ToastUtils.showToast("Unknown command: $command")
                }
            }
        }
    }

    private fun initStickListener() {
        binding?.leftStickView?.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var leftPx = 0F
                var leftPy = 0F

                if (abs(pX) >= deviation) {
                    leftPx = pX
                }

                if (abs(pY) >= deviation) {
                    leftPy = pY
                }

                virtualStickVM.setLeftPosition(
                    (leftPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (leftPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })
        binding?.rightStickView?.setJoystickListener(object : OnScreenJoystickListener {
            override fun onTouch(joystick: OnScreenJoystick?, pX: Float, pY: Float) {
                var rightPx = 0F
                var rightPy = 0F

                if (abs(pX) >= deviation) {
                    rightPx = pX
                }

                if (abs(pY) >= deviation) {
                    rightPy = pY
                }

                virtualStickVM.setRightPosition(
                    (rightPx * Stick.MAX_STICK_POSITION_ABS).toInt(),
                    (rightPy * Stick.MAX_STICK_POSITION_ABS).toInt()
                )
            }
        })
    }

    private fun updateVirtualStickInfo() {
        val builder = StringBuilder()
        builder.append("Speed level:").append(virtualStickVM.currentSpeedLevel.value)
        builder.append("\n")
        builder.append("Use rc stick as virtual stick:").append(virtualStickVM.useRcStick.value)
        builder.append("\n")
        builder.append("Is virtual stick enable:").append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickEnable)
        builder.append("\n")
        builder.append("Current control permission owner:").append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.currentFlightControlAuthorityOwner)
        builder.append("\n")
        builder.append("Change reason:").append(virtualStickVM.currentVirtualStickStateInfo.value?.reason)
        builder.append("\n")
        builder.append("Rc stick value:").append(virtualStickVM.stickValue.value?.toString())
        builder.append("\n")
        builder.append("Is virtual stick advanced mode enable:").append(virtualStickVM.currentVirtualStickStateInfo.value?.state?.isVirtualStickAdvancedModeEnabled)
        builder.append("\n")
        builder.append("Virtual stick advanced mode param:").append(virtualStickVM.virtualStickAdvancedParam.value?.toJson())
        builder.append("\n")
        mainHandler.post {
            binding?.virtualStickInfoTv?.text = builder.toString()
        }
    }
}