package com.cleardictate.domain

/**
 * Represents every externally visible phase of one recording and transcript-processing operation.
 */
enum class RecordingState
{
    IDLE,
    REQUESTING_PERMISSION,
    PREPARING,
    LISTENING,
    SPEECH_DETECTED,
    FINALIZING_RECOGNITION,
    CLEANING_TRANSCRIPT,
    POLISHING_TRANSCRIPT,
    COMPLETED,
    CANCELLED,
    ERROR
}
/**
 * Represents facts or user requests that may advance the recording pipeline.
 */
enum class RecordingEvent
{
    RECORD_REQUESTED,
    PERMISSION_GRANTED,
    PERMISSION_DENIED,
    AUDIO_READY,
    SPEECH_STARTED,
    STOP_REQUESTED,
    RECOGNITION_FINALIZED,
    CLEANUP_COMPLETED,
    CLEANUP_COMPLETED_WITHOUT_POLISHING,
    POLISHING_COMPLETED,
    CANCEL_REQUESTED,
    FAILURE_OCCURRED,
    RESET_REQUESTED
}

/**
 * Makes illegal lifecycle behaviour immediately visible to tests and coordinating code.
 */
class InvalidRecordingTransitionException(
    currentState: RecordingState,
    event: RecordingEvent
) : IllegalStateException("Recording event $event is invalid while in state $currentState.")

/**
 * Owns explicit recording-state transitions without performing platform work.
 *
 * Platform coordinators execute side effects only after a transition succeeds. This separation lets
 * Android and Windows share identical lifecycle rules and makes stale or illegal events auditable.
 */
class RecordingStateMachine(
    initialState: RecordingState = RecordingState.IDLE
)
{
    var currentState: RecordingState = initialState
        private set

    /**
     * Applies one event atomically or throws without mutating the current state.
     */
    @Synchronized
    fun transition(event: RecordingEvent): RecordingState
    {
        val nextState = determineNextState(currentState, event)
            ?: throw InvalidRecordingTransitionException(currentState, event)

        currentState = nextState
        return currentState
    }

    private fun determineNextState(state: RecordingState, event: RecordingEvent): RecordingState?
    {
        if (event == RecordingEvent.FAILURE_OCCURRED && state != RecordingState.IDLE)
        {
            return RecordingState.ERROR
        }

        if (event == RecordingEvent.CANCEL_REQUESTED && isActiveState(state))
        {
            return RecordingState.CANCELLED
        }

        return when (state to event)
        {
            RecordingState.IDLE to RecordingEvent.RECORD_REQUESTED -> RecordingState.REQUESTING_PERMISSION
            RecordingState.REQUESTING_PERMISSION to RecordingEvent.PERMISSION_GRANTED -> RecordingState.PREPARING
            RecordingState.REQUESTING_PERMISSION to RecordingEvent.PERMISSION_DENIED -> RecordingState.ERROR
            RecordingState.PREPARING to RecordingEvent.AUDIO_READY -> RecordingState.LISTENING
            RecordingState.LISTENING to RecordingEvent.SPEECH_STARTED -> RecordingState.SPEECH_DETECTED
            RecordingState.LISTENING to RecordingEvent.STOP_REQUESTED -> RecordingState.FINALIZING_RECOGNITION
            RecordingState.SPEECH_DETECTED to RecordingEvent.STOP_REQUESTED -> RecordingState.FINALIZING_RECOGNITION
            RecordingState.FINALIZING_RECOGNITION to RecordingEvent.RECOGNITION_FINALIZED -> RecordingState.CLEANING_TRANSCRIPT
            RecordingState.CLEANING_TRANSCRIPT to RecordingEvent.CLEANUP_COMPLETED -> RecordingState.POLISHING_TRANSCRIPT
            RecordingState.CLEANING_TRANSCRIPT to RecordingEvent.CLEANUP_COMPLETED_WITHOUT_POLISHING -> RecordingState.COMPLETED
            RecordingState.POLISHING_TRANSCRIPT to RecordingEvent.POLISHING_COMPLETED -> RecordingState.COMPLETED
            RecordingState.COMPLETED to RecordingEvent.RESET_REQUESTED -> RecordingState.IDLE
            RecordingState.CANCELLED to RecordingEvent.RESET_REQUESTED -> RecordingState.IDLE
            RecordingState.ERROR to RecordingEvent.RESET_REQUESTED -> RecordingState.IDLE
            else -> null
        }
    }

    private fun isActiveState(state: RecordingState): Boolean
    {
        return state == RecordingState.REQUESTING_PERMISSION ||
            state == RecordingState.PREPARING ||
            state == RecordingState.LISTENING ||
            state == RecordingState.SPEECH_DETECTED ||
            state == RecordingState.FINALIZING_RECOGNITION ||
            state == RecordingState.CLEANING_TRANSCRIPT ||
            state == RecordingState.POLISHING_TRANSCRIPT
    }
}
