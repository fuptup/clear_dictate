package com.cleardictate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Specifies explicit, auditable recording and processing transitions.
 */
class RecordingStateMachineTest
{
    @Test
    fun `successful polished dictation follows the complete state sequence`()
    {
        val stateMachine = RecordingStateMachine()

        val observedStates = listOf(
            stateMachine.transition(RecordingEvent.RECORD_REQUESTED),
            stateMachine.transition(RecordingEvent.PERMISSION_GRANTED),
            stateMachine.transition(RecordingEvent.AUDIO_READY),
            stateMachine.transition(RecordingEvent.SPEECH_STARTED),
            stateMachine.transition(RecordingEvent.STOP_REQUESTED),
            stateMachine.transition(RecordingEvent.RECOGNITION_FINALIZED),
            stateMachine.transition(RecordingEvent.CLEANUP_COMPLETED),
            stateMachine.transition(RecordingEvent.POLISHING_COMPLETED)
        )

        assertEquals(
            listOf(
                RecordingState.REQUESTING_PERMISSION,
                RecordingState.PREPARING,
                RecordingState.LISTENING,
                RecordingState.SPEECH_DETECTED,
                RecordingState.FINALIZING_RECOGNITION,
                RecordingState.CLEANING_TRANSCRIPT,
                RecordingState.POLISHING_TRANSCRIPT,
                RecordingState.COMPLETED
            ),
            observedStates
        )
    }

    @Test
    fun `cancel is valid from active processing and returns to cancelled`()
    {
        val stateMachine = RecordingStateMachine(initialState = RecordingState.POLISHING_TRANSCRIPT)

        assertEquals(RecordingState.CANCELLED, stateMachine.transition(RecordingEvent.CANCEL_REQUESTED))
    }

    @Test
    fun `invalid transitions fail instead of silently changing state`()
    {
        val stateMachine = RecordingStateMachine()

        assertFailsWith<InvalidRecordingTransitionException> {
            stateMachine.transition(RecordingEvent.SPEECH_STARTED)
        }
        assertEquals(RecordingState.IDLE, stateMachine.currentState)
    }
}
