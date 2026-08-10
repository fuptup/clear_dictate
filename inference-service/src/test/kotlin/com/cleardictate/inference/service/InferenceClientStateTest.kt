package com.cleardictate.inference.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Specifies the presentation-state transition applied when Android reconnects a long-lived inference client.
 */
class InferenceClientStateTest
{
    @Test
    fun `operation failure remains visible without disabling the next recording`()
    {
        val recordingState = InferenceClientState(
            connectionState = InferenceConnectionState.CONNECTED,
            speechModelState = SpeechModelState.READY,
            recordingState = ClientRecordingState.FINALIZING,
            partialRawTranscript = "Private partial transcript"
        )

        val failedState = recordingState.afterOperationFailure("PC transcription failed.")

        assertEquals(InferenceConnectionState.CONNECTED, failedState.connectionState)
        assertEquals(SpeechModelState.READY, failedState.speechModelState)
        assertEquals(ClientRecordingState.IDLE, failedState.recordingState)
        assertEquals("", failedState.partialRawTranscript)
        assertEquals("PC transcription failed.", failedState.failureMessage)
    }

    @Test
    fun `service reconnection clears the abandoned operation error`()
    {
        val disconnectedState = InferenceClientState(
            connectionState = InferenceConnectionState.DISCONNECTED,
            speechModelState = SpeechModelState.READY,
            recordingState = ClientRecordingState.ERROR,
            normalizedAudioLevel = 0.75f,
            partialRawTranscript = "Private partial transcript",
            finalRawTranscript = "Private final transcript",
            cleanTranscript = "Private clean transcript",
            polishedTranscript = "Private polished transcript",
            selectedTranscript = "Private selected transcript",
            usedDeterministicFallback = true,
            completedOperationIdentifier = "abandoned-operation",
            failureMessage = "The recording service stopped. Reconnect to try again."
        )

        val reconnectedState = disconnectedState.afterServiceConnected()

        assertEquals(InferenceConnectionState.CONNECTED, reconnectedState.connectionState)
        assertEquals(SpeechModelState.READY, reconnectedState.speechModelState)
        assertEquals(ClientRecordingState.IDLE, reconnectedState.recordingState)
        assertEquals(0.0f, reconnectedState.normalizedAudioLevel)
        assertEquals("", reconnectedState.partialRawTranscript)
        assertEquals("", reconnectedState.finalRawTranscript)
        assertEquals("", reconnectedState.cleanTranscript)
        assertNull(reconnectedState.polishedTranscript)
        assertEquals("", reconnectedState.selectedTranscript)
        assertEquals(false, reconnectedState.usedDeterministicFallback)
        assertNull(reconnectedState.completedOperationIdentifier)
        assertNull(reconnectedState.failureMessage)
    }
}
