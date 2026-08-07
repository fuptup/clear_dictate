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

    @Test
    fun `requires recording started before progress and stop before final transcript`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.START_RECORDING))

        assertFailsWith<WorkerProtocolStateException> {
            stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))
        }

        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.RECORDING_STARTED))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))

        assertFailsWith<WorkerProtocolStateException> {
            stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.RECORDING_COMPLETE))
        }

        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.STOP_RECORDING))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.RECORDING_COMPLETE))

        assertEquals(WorkerLifecycleState.IDLE, stateMachine.state)
    }

    @Test
    fun `accepts cancellation acknowledgement only after cancellation and blocks later progress`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.START_RECORDING))

        assertFailsWith<WorkerProtocolStateException> {
            stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.CANCELLATION_ACKNOWLEDGED))
        }

        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.CANCEL))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.CANCELLATION_ACKNOWLEDGED))

        assertFailsWith<WorkerProtocolStateException> {
            stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))
        }

        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.OPERATION_CANCELLED))
        assertEquals(WorkerLifecycleState.IDLE, stateMachine.state)
    }

    @Test
    fun `accepts polish result when generation wins race before cancellation acknowledgement`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.POLISH_TRANSCRIPT))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.CANCEL))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.POLISHED_TRANSCRIPT))

        assertEquals(WorkerLifecycleState.IDLE, stateMachine.state)
    }

    @Test
    fun `accepts in flight start and progress until cancellation acknowledgement`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.START_RECORDING))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.CANCEL))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.RECORDING_STARTED))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.CANCELLATION_ACKNOWLEDGED))

        assertFailsWith<WorkerProtocolStateException> {
            stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))
        }

        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.OPERATION_CANCELLED))
        assertEquals(WorkerLifecycleState.IDLE, stateMachine.state)
    }

    @Test
    fun `accepts in flight recording progress after stop until final transcript`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.START_RECORDING))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.RECORDING_STARTED))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.STOP_RECORDING))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.RECORDING_COMPLETE))

        assertEquals(WorkerLifecycleState.IDLE, stateMachine.state)
    }

    @Test
    fun `rejects unsolicited cancellation terminal before host cancellation`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.START_RECORDING))

        assertFailsWith<WorkerProtocolStateException> {
            stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.OPERATION_CANCELLED))
        }
    }

    @Test
    fun `accepts in flight progress when cancelling active recording`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.START_RECORDING))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.RECORDING_STARTED))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.CANCEL))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.CANCELLATION_ACKNOWLEDGED))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.OPERATION_CANCELLED))

        assertEquals(WorkerLifecycleState.IDLE, stateMachine.state)
    }

    @Test
    fun `accepts final transcript when stop wins race before cancellation acknowledgement`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.START_RECORDING))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.RECORDING_STARTED))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.STOP_RECORDING))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.CANCEL))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.AUDIO_CHUNK))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.RECORDING_COMPLETE))

        assertEquals(WorkerLifecycleState.IDLE, stateMachine.state)
    }

    @Test
    fun `accepts error before cancellation acknowledgement`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.START_RECORDING))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.CANCEL))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.ERROR))

        assertEquals(WorkerLifecycleState.IDLE, stateMachine.state)
    }

    @Test
    fun `accepts duplicate matching cancellation commands before and after acknowledgement`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.START_RECORDING))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.CANCEL))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.CANCEL))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.CANCELLATION_ACKNOWLEDGED))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.CANCEL))
        stateMachine.acceptWorkerFrame(operationFrame(WorkerMessageType.OPERATION_CANCELLED))

        assertEquals(WorkerLifecycleState.IDLE, stateMachine.state)
    }

    @Test
    fun `rejects wrong identity while cancellation is pending`()
    {
        val stateMachine = readyStateMachine()
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.START_RECORDING))
        stateMachine.acceptHostFrame(operationFrame(WorkerMessageType.CANCEL))

        assertFailsWith<WorkerProtocolStateException> {
            stateMachine.acceptWorkerFrame(
                operationFrame(WorkerMessageType.CANCELLATION_ACKNOWLEDGED, workerRequestToken = 28)
            )
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
