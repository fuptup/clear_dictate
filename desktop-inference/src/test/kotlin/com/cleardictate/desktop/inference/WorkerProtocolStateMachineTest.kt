package com.cleardictate.desktop.inference

import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies legal host/worker sequencing and one terminal outcome per operation.
 */
class WorkerProtocolStateMachineTest
{
    @Test
    fun `accepts handshake model load and one polish result`()
    {
        val stateMachine = WorkerProtocolStateMachine()
        val operation = operationFrame(WorkerMessageType.POLISH_TRANSCRIPT)

        stateMachine.acceptHostFrame(controlFrame(WorkerMessageType.HELLO))
        stateMachine.acceptWorkerFrame(controlFrame(WorkerMessageType.READY))
        stateMachine.acceptHostFrame(controlFrame(WorkerMessageType.LOAD_MODELS))
        stateMachine.acceptWorkerFrame(controlFrame(WorkerMessageType.MODELS_LOADED))
        stateMachine.acceptHostFrame(operation)
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.POLISHED_TRANSCRIPT))

        assertEquals(WorkerLifecycleState.IDLE, stateMachine.state)
    }

    @Test
    fun `rejects duplicate terminal result`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.POLISH_TRANSCRIPT))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.POLISHED_TRANSCRIPT))

        assertFailsWith<WorkerProtocolStateException> {
            stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.POLISHED_TRANSCRIPT))
        }
    }

    @Test
    fun `rejects stale token and privacy relaxation`()
    {
        val staleTokenMachine = readyStateMachine()
        staleTokenMachine.acceptHostFrame(operationFrame(WorkerMessageType.POLISH_TRANSCRIPT))

        assertFailsWith<WorkerProtocolStateException> {
            staleTokenMachine.acceptWorkerFrame(
                operationFrame(WorkerMessageType.POLISHED_TRANSCRIPT, workerRequestToken = 28)
            )
        }

        val privacyMachine = readyStateMachine()
        privacyMachine.acceptHostFrame(operationFrame(WorkerMessageType.POLISH_TRANSCRIPT, privacy = OperationPrivacy.PRIVATE))

        assertFailsWith<WorkerProtocolStateException> {
            privacyMachine.acceptWorkerFrame(
                operationFrame(WorkerMessageType.POLISHED_TRANSCRIPT, privacy = OperationPrivacy.STANDARD)
            )
        }
    }

    @Test
    fun `rejects message sent by the wrong peer`()
    {
        val stateMachine = WorkerProtocolStateMachine()

        assertFailsWith<WorkerProtocolStateException> {
            stateMachine.acceptWorkerFrame(controlFrame(WorkerMessageType.HELLO))
        }
    }

    private fun readyStateMachine(): WorkerProtocolStateMachine
    {
        val stateMachine = WorkerProtocolStateMachine()
        stateMachine.acceptHostFrame(controlFrame(WorkerMessageType.HELLO))
        stateMachine.acceptWorkerFrame(controlFrame(WorkerMessageType.READY))
        stateMachine.acceptHostFrame(controlFrame(WorkerMessageType.LOAD_MODELS))
        stateMachine.acceptWorkerFrame(controlFrame(WorkerMessageType.MODELS_LOADED))
        return stateMachine
    }

    private fun controlFrame(type: WorkerMessageType): WorkerControlFrame
    {
        return WorkerControlFrame(type, ByteArray(0))
    }

    private fun operationFrame(
        type: WorkerMessageType,
        privacy: OperationPrivacy = OperationPrivacy.PRIVATE,
        workerRequestToken: Long = 27
    ): WorkerProtocolMessage
    {
        return WorkerProtocolMessage(
            type = type,
            clientSessionIdentifier = ClientSessionIdentifier("client-7"),
            operationIdentifier = OperationIdentifier("operation-19"),
            privacy = privacy,
            workerRequestToken = WorkerRequestToken(workerRequestToken),
            payload = ByteArray(0)
        )
    }
}
