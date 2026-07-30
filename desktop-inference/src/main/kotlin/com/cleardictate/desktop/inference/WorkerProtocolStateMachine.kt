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

                        activeOperation = ActiveWorkerOperation.from(frame, operationKind)
                        lifecycleState = WorkerLifecycleState.OPERATION_ACTIVE
                    }
                }
            }

            WorkerLifecycleState.OPERATION_ACTIVE ->
            {
                val operationFrame = frame as? WorkerProtocolMessage
                    ?: reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                requireActiveIdentity(operationFrame)

                val activeKind = requireNotNull(activeOperation).kind
                val legalCommand = operationFrame.type == WorkerMessageType.CANCEL ||
                    (activeKind == WorkerOperationKind.RECORDING && operationFrame.type == WorkerMessageType.STOP_RECORDING)

                if (!legalCommand)
                {
                    reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                }
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

                val activeKind = requireNotNull(activeOperation).kind
                val isProgressEvent = when (activeKind)
                {
                    WorkerOperationKind.RECORDING ->
                    {
                        operationFrame.type == WorkerMessageType.AUDIO_LEVEL ||
                            operationFrame.type == WorkerMessageType.PARTIAL_TRANSCRIPT ||
                            operationFrame.type == WorkerMessageType.CANCELLATION_ACKNOWLEDGED
                    }

                    WorkerOperationKind.POLISHING ->
                    {
                        operationFrame.type == WorkerMessageType.CANCELLATION_ACKNOWLEDGED
                    }
                }

                if (isProgressEvent)
                {
                    return
                }

                val isTerminalEvent = operationFrame.type == WorkerMessageType.ERROR ||
                    operationFrame.type == WorkerMessageType.OPERATION_CANCELLED ||
                    (activeKind == WorkerOperationKind.RECORDING && operationFrame.type == WorkerMessageType.FINAL_TRANSCRIPT) ||
                    (activeKind == WorkerOperationKind.POLISHING && operationFrame.type == WorkerMessageType.POLISHED_TRANSCRIPT)

                if (!isTerminalEvent)
                {
                    reject(WorkerProtocolStateFailure.ILLEGAL_DIRECTION)
                }

                activeOperation = null
                lifecycleState = WorkerLifecycleState.IDLE
            }

            else -> reject(WorkerProtocolStateFailure.ILLEGAL_STATE)
        }
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

private data class ActiveWorkerOperation(
    val clientSessionIdentifier: ClientSessionIdentifier,
    val operationIdentifier: OperationIdentifier,
    val privacy: OperationPrivacy,
    val workerRequestToken: WorkerRequestToken,
    val kind: WorkerOperationKind
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
        fun from(frame: WorkerProtocolMessage, kind: WorkerOperationKind): ActiveWorkerOperation
        {
            return ActiveWorkerOperation(
                clientSessionIdentifier = frame.clientSessionIdentifier,
                operationIdentifier = frame.operationIdentifier,
                privacy = frame.privacy,
                workerRequestToken = frame.workerRequestToken,
                kind = kind
            )
        }
    }
}
