package dji.sampleV5.aircraft.models

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.sampleV5.aircraft.recorder.RecordingController

class BasicAircraftControlVM : DJIViewModel() {

    // The three listen... functions below are called by the Fragment every time the page opens.
    // They only hand over to RecordingController, which registers each listener once per app
    // process (a view model is created again for every visit, so it must not own the listeners).

    fun listenForRecordingAndStartStream(rtmpUrl: String, onStatus: (String) -> Unit = {}) {
        RecordingController.ensureStream(rtmpUrl, onStatus)
    }

    fun listenForRecordingAndRecordTelemetry(onStatus: (String) -> Unit = {}) {
        RecordingController.ensureRecording(onStatus)
    }

    fun listenForLandingConfirmation() {
        RecordingController.ensureLandingConfirmation()
    }

    fun startTakeOff(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>) {
        FlightControllerKey.KeyStartTakeoff.create().action({
            callback.onSuccess(it)
        }, { e: IDJIError ->
            callback.onFailure(e)
        })
    }

    fun startLanding(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>) {
        FlightControllerKey.KeyStartAutoLanding.create().action({
            callback.onSuccess(it)
        }, { e: IDJIError ->
            callback.onFailure(e)
        })
    }

    fun confirmLanding(callback: CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>) {
        FlightControllerKey.KeyConfirmLanding.create().action({
            callback.onSuccess(it)
        }, { e: IDJIError ->
            callback.onFailure(e)
        })
    }
}