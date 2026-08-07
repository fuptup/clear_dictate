package com.cleardictate.desktop.inference

import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy

enum class WorkerLifecycleState
{
    NEW,
    AWAITING_READY,
    READY_FOR_MODEL_LOAD,
    AWAITING_MODELS,
    IDLE,
    OPERATION_ACTIVE,
    CLOSED,
    FAILED
}

enum class WorkerProtocolStateFailure
{
    ILLEGAL_DIRECTION,
    ILLEGAL_STATE,
    OPERATION_IDENTITY_MISMATCH
}

class WorkerProtocolStateException(
    val category: WorkerProtocolStateFailure,
    val lifecycleState: WorkerLifecycleState
) : Exception("Worker protocol state failure: $category in $lifecycleState")

/**
 * Enforces host-side worker sequencing and exactly one terminal operation outcome.
 *
 * All transitions are synchronized because process output and application commands
 * arrive on different threads.
 */
class WorkerProtocolStateMachine
{
    private var lifecycleState: WorkerLifecycleState = WorkerLifecycleState.NEW

    val state: WorkerLifecycleState
        @Synchronized get() = lifecycleState

    private var activeOperation: ActiveWorkerOperation? = null
    private var activeOperationPhase: WorkerOperationPhase? = null
    private var phaseBeforeCancellation: WorkerOperationPhase? = null

    @Synchronized
    fun acceptHostFrame(frame: WorkerProtocolFrame)
    {
        when (lifecycleState)
        {
            WorkerLifecycleState.NEW ->
            {
                requireControlType(frame, WorkerMessageType.HELLO)
                lifecycleState = WorkerLifecycleState.AWAITING_READY
            }

            WorkerLifecycleState.READY_FOR_MODEL_LOAD ->
            {
                requireControlType(frame, WorkerMessageType.LOAD_MODELS)
                lifecycleState = WorkerLifecycleState.AWAITING_MODELS
            }

            WorkerLifecycleState.IDLE ->
            {
                when (frame)
                {
                    is WorkerControlFrame ->
                    {
                        if (frame.type != WorkerMessageType.SHUTDOWN)
                        {
                            reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                        }

                        lifecycleState = WorkerLifecycleState.CLOSED
                    }

                    is WorkerProtocolMessage ->
                    {
                        val operationKind = when (frame.type)
                        {
                            WorkerMessageType.START_RECORDING -> WorkerOperationKind.RECORDING
                            WorkerMessageType.POLISH_TRANSCRIPT -> WorkerOperationKind.POLISHING
                            else -> reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                        }

                        activeOperation = ActiveWorkerOperation.from(frame)
                        activeOperationPhase = when (operationKind)
                        {
                            WorkerOperationKind.RECORDING -> WorkerOperationPhase.RECORDING_START_SENT
                            WorkerOperationKind.POLISHING -> WorkerOperationPhase.POLISHING
                        }
                        lifecycleState = WorkerLifecycleState.OPERATION_ACTIVE
                    }
                }
            }

            WorkerLifecycleState.OPERATION_ACTIVE ->
            {
                val operationFrame = frame as? WorkerProtocolMessage
                    ?: reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                requireActiveIdentity(operationFrame)
                acceptActiveHostOperationFrame(operationFrame)
            }

            else -> reject(WorkerProtocolStateFailure.ILLEGAL_STATE)
        }
    }

    @Synchronized
    fun acceptWorkerFrame(frame: WorkerProtocolFrame)
    {
        when (lifecycleState)
        {
            WorkerLifecycleState.AWAITING_READY ->
            {
                if (frame is WorkerControlFrame && frame.type == WorkerMessageType.CONTROL_ERROR)
                {
                    lifecycleState = WorkerLifecycleState.FAILED
                    return
                }

                requireControlType(frame, WorkerMessageType.READY)
                lifecycleState = WorkerLifecycleState.READY_FOR_MODEL_LOAD
            }

            WorkerLifecycleState.AWAITING_MODELS ->
            {
                if (frame is WorkerControlFrame && frame.type == WorkerMessageType.CONTROL_ERROR)
                {
                    lifecycleState = WorkerLifecycleState.FAILED
                    return
                }

                requireControlType(frame, WorkerMessageType.MODELS_LOADED)
                lifecycleState = WorkerLifecycleState.IDLE
            }

            WorkerLifecycleState.OPERATION_ACTIVE ->
            {
                val operationFrame = frame as? WorkerProtocolMessage
                    ?: reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                requireActiveIdentity(operationFrame)
                acceptActiveWorkerOperationFrame(operationFrame)
            }

            else -> reject(WorkerProtocolStateFailure.ILLEGAL_STATE)
        }
    }

    private fun acceptActiveHostOperationFrame(frame: WorkerProtocolMessage)
    {
        when (activeOperationPhase ?: reject(WorkerProtocolStateFailure.ILLEGAL_STATE))
        {
            WorkerOperationPhase.RECORDING_START_SENT,
            WorkerOperationPhase.RECORDING,
            WorkerOperationPhase.RECORDING_STOP_SENT,
            WorkerOperationPhase.POLISHING ->
            {
                when (frame.type)
                {
                    WorkerMessageType.CANCEL ->
                    {
                        phaseBeforeCancellation = activeOperationPhase
                        activeOperationPhase = WorkerOperationPhase.CANCELLATION_SENT
                    }

                    WorkerMessageType.STOP_RECORDING ->
                    {
                        if (activeOperationPhase != WorkerOperationPhase.RECORDING)
                        {
                            reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                        }
                        activeOperationPhase = WorkerOperationPhase.RECORDING_STOP_SENT
                    }

                    else -> reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                }
            }

            WorkerOperationPhase.CANCELLATION_SENT,
            WorkerOperationPhase.CANCELLATION_ACKNOWLEDGED ->
            {
                if (frame.type != WorkerMessageType.CANCEL)
                {
                    reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                }
            }
        }
    }

    private fun acceptActiveWorkerOperationFrame(frame: WorkerProtocolMessage)
    {
        when (activeOperationPhase ?: reject(WorkerProtocolStateFailure.ILLEGAL_STATE))
        {
            WorkerOperationPhase.RECORDING_START_SENT ->
            {
                when (frame.type)
                {
                    WorkerMessageType.RECORDING_STARTED -> activeOperationPhase = WorkerOperationPhase.RECORDING
                    WorkerMessageType.ERROR -> completeActiveOperation()
                    else -> reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                }
            }

            WorkerOperationPhase.RECORDING ->
            {
                when (frame.type)
                {
                    WorkerMessageType.AUDIO_CHUNK -> return
                    WorkerMessageType.ERROR -> completeActiveOperation()
                    else -> reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                }
            }

            WorkerOperationPhase.RECORDING_STOP_SENT ->
            {
                when (frame.type)
                {
                    WorkerMessageType.AUDIO_CHUNK -> return
                    WorkerMessageType.RECORDING_COMPLETE,
                    WorkerMessageType.ERROR -> completeActiveOperation()
                    else -> reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                }
            }

            WorkerOperationPhase.POLISHING ->
            {
                when (frame.type)
                {
                    WorkerMessageType.POLISHED_TRANSCRIPT,
                    WorkerMessageType.ERROR -> completeActiveOperation()
                    else -> reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                }
            }

            WorkerOperationPhase.CANCELLATION_SENT ->
            {
                when (frame.type)
                {
                    WorkerMessageType.RECORDING_STARTED ->
                    {
                        if (phaseBeforeCancellation != WorkerOperationPhase.RECORDING_START_SENT)
                        {
                            reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                        }
                        phaseBeforeCancellation = WorkerOperationPhase.RECORDING
                    }

                    WorkerMessageType.AUDIO_CHUNK ->
                    {
                        if (phaseBeforeCancellation != WorkerOperationPhase.RECORDING &&
                            phaseBeforeCancellation != WorkerOperationPhase.RECORDING_STOP_SENT)
                        {
                            reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                        }
                    }

                    WorkerMessageType.CANCELLATION_ACKNOWLEDGED ->
                    {
                        activeOperationPhase = WorkerOperationPhase.CANCELLATION_ACKNOWLEDGED
                    }

                    WorkerMessageType.OPERATION_CANCELLED,
                    WorkerMessageType.ERROR -> completeActiveOperation()
                    WorkerMessageType.RECORDING_COMPLETE ->
                    {
                        if (phaseBeforeCancellation != WorkerOperationPhase.RECORDING_STOP_SENT)
                        {
                            reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                        }
                        completeActiveOperation()
                    }

                    WorkerMessageType.POLISHED_TRANSCRIPT ->
                    {
                        if (phaseBeforeCancellation != WorkerOperationPhase.POLISHING)
                        {
                            reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                        }
                        completeActiveOperation()
                    }

                    else -> reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                }
            }

            WorkerOperationPhase.CANCELLATION_ACKNOWLEDGED ->
            {
                when (frame.type)
                {
                    WorkerMessageType.OPERATION_CANCELLED,
                    WorkerMessageType.ERROR -> completeActiveOperation()
                    else -> reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                }
            }
        }
    }

    private fun completeActiveOperation()
    {
        activeOperation = null
        activeOperationPhase = null
        phaseBeforeCancellation = null
        lifecycleState = WorkerLifecycleState.IDLE
    }

    private fun requireControlType(frame: WorkerProtocolFrame, expectedType: WorkerMessageType)
    {
        if (frame !is WorkerControlFrame || frame.type != expectedType)
        {
            reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
        }
    }

    private fun requireActiveIdentity(frame: WorkerProtocolMessage)
    {
        val expectedOperation = activeOperation
            ?: reject(WorkerProtocolStateFailure.ILLEGAL_STATE)

        if (!expectedOperation.matches(frame))
        {
            reject(WorkerProtocolStateFailure.OPERATION_IDENTITY_MISMATCH)
        }
    }

    private fun reject(failure: WorkerProtocolStateFailure): Nothing
    {
        throw WorkerProtocolStateException(failure, lifecycleState)
    }
}

private enum class WorkerOperationKind
{
    RECORDING,
    POLISHING
}

private enum class WorkerOperationPhase
{
    RECORDING_START_SENT,
    RECORDING,
    RECORDING_STOP_SENT,
    POLISHING,
    CANCELLATION_SENT,
    CANCELLATION_ACKNOWLEDGED
}

private data class ActiveWorkerOperation(
    val clientSessionIdentifier: ClientSessionIdentifier,
    val operationIdentifier: OperationIdentifier,
    val privacy: OperationPrivacy,
    val workerRequestToken: WorkerRequestToken
)
{
    fun matches(frame: WorkerProtocolMessage): Boolean
    {
        return clientSessionIdentifier == frame.clientSessionIdentifier &&
            operationIdentifier == frame.operationIdentifier &&
            privacy == frame.privacy &&
            workerRequestToken == frame.workerRequestToken
    }

    companion object
    {
        fun from(frame: WorkerProtocolMessage): ActiveWorkerOperation
        {
            return ActiveWorkerOperation(
                clientSessionIdentifier = frame.clientSessionIdentifier,
                operationIdentifier = frame.operationIdentifier,
                privacy = frame.privacy,
                workerRequestToken = frame.workerRequestToken
            )
        }
    }
}
