package com.cleardictate.desktop.inference

import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that stale worker results cannot cross client or operation boundaries.
 */
class ActiveOperationMessageFilterTest
{
    @Test
    fun `accepts only the active client and operation identifiers`()
    {
        val filter = ActiveOperationMessageFilter(
            activeClientSessionIdentifier = ClientSessionIdentifier("client-7"),
            activeOperationIdentifier = OperationIdentifier("operation-19"),
            activeWorkerRequestToken = WorkerRequestToken(27)
        )

        assertTrue(filter.accepts(message("client-7", "operation-19", 27)))
        assertFalse(filter.accepts(message("client-8", "operation-19", 27)))
        assertFalse(filter.accepts(message("client-7", "operation-20", 27)))
        assertFalse(filter.accepts(message("client-7", "operation-19", 28)))
    }

    private fun message(clientIdentifier: String, operationIdentifier: String, workerRequestToken: Long): WorkerProtocolMessage
    {
        return WorkerProtocolMessage(
            type = WorkerMessageType.POLISHED_TRANSCRIPT,
            clientSessionIdentifier = ClientSessionIdentifier(clientIdentifier),
            operationIdentifier = OperationIdentifier(operationIdentifier),
            privacy = OperationPrivacy.STANDARD,
            workerRequestToken = WorkerRequestToken(workerRequestToken),
            payload = ByteArray(0)
        )
    }
}
