package com.cleardictate.android.accessibility

import com.cleardictate.inference.service.ClientRecordingState
import com.cleardictate.inference.service.InferenceClientState
import com.cleardictate.inference.service.InferenceConnectionState
import com.cleardictate.inference.service.PcConnectionState
import com.cleardictate.inference.service.SpeechModelState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that the floating control distinguishes a lost PC connection from other unavailable states.
 */
class FloatingDictationVisualStateTest
{
    @Test
    fun `disconnected client shows no-entry state even when recording was active`()
    {
        val clientState = InferenceClientState(
            connectionState = InferenceConnectionState.DISCONNECTED,
            pcConnectionState = PcConnectionState.CONNECTED,
            speechModelState = SpeechModelState.READY,
            recordingState = ClientRecordingState.LISTENING
        )

        assertEquals(FloatingDictationVisualState.DISCONNECTED, clientState.visualState())
    }

    @Test
    fun `unreachable PC shows no-entry state while Android service remains connected`()
    {
        val clientState = InferenceClientState(
            connectionState = InferenceConnectionState.CONNECTED,
            pcConnectionState = PcConnectionState.DISCONNECTED,
            speechModelState = SpeechModelState.READY,
            recordingState = ClientRecordingState.IDLE
        )

        assertEquals(FloatingDictationVisualState.DISCONNECTED, clientState.visualState())
    }

    @Test
    fun `connected client retains the existing ready and unavailable states`()
    {
        val readyState = InferenceClientState(
            connectionState = InferenceConnectionState.CONNECTED,
            pcConnectionState = PcConnectionState.CONNECTED,
            speechModelState = SpeechModelState.READY,
            recordingState = ClientRecordingState.IDLE
        )
        val modelUnavailableState = readyState.copy(speechModelState = SpeechModelState.VERIFYING_AND_LOADING)
        val connectionCheckState = readyState.copy(pcConnectionState = PcConnectionState.CHECKING)

        assertEquals(FloatingDictationVisualState.READY, readyState.visualState())
        assertEquals(FloatingDictationVisualState.UNAVAILABLE, modelUnavailableState.visualState())
        assertEquals(FloatingDictationVisualState.UNAVAILABLE, connectionCheckState.visualState())
    }
}
