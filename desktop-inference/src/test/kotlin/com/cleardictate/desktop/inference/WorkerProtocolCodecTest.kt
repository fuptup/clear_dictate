package com.cleardictate.desktop.inference

import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies strict framing for the private Windows worker pipe.
 */
class WorkerProtocolCodecTest
{
    private val codec = WorkerProtocolCodec(maximumPayloadBytes = 1024)

    @Test
    fun `round trips Unicode payload without exposing it through diagnostics`()
    {
        val message = WorkerProtocolMessage(
            type = WorkerMessageType.POLISH_TRANSCRIPT,
            clientSessionIdentifier = ClientSessionIdentifier("client-7"),
            operationIdentifier = OperationIdentifier("operation-19"),
            privacy = OperationPrivacy.PRIVATE,
            workerRequestToken = WorkerRequestToken(27),
            payload = "Preserve £1,250 at 10:30.".toByteArray(Charsets.UTF_8)
        )
        val outputBytes = ByteArrayOutputStream()

        codec.write(message, DataOutputStream(outputBytes))
        val decoded = assertIs<WorkerProtocolMessage>(
            codec.read(DataInputStream(ByteArrayInputStream(outputBytes.toByteArray())))
        )

        assertEquals(message.type, decoded.type)
        assertEquals(message.clientSessionIdentifier, decoded.clientSessionIdentifier)
        assertEquals(message.operationIdentifier, decoded.operationIdentifier)
        assertEquals(message.privacy, decoded.privacy)
        assertEquals(message.workerRequestToken, decoded.workerRequestToken)
        assertContentEquals(message.payload.copyBytes(), decoded.payload.copyBytes())
        assertFalse(decoded.toString().contains("£1,250"))
    }

    @Test
    fun `round trips control frame without fabricated operation identity`()
    {
        val controlFrame = WorkerControlFrame(
            type = WorkerMessageType.HELLO,
            payload = ByteArray(0)
        )
        val outputBytes = ByteArrayOutputStream()

        codec.write(controlFrame, DataOutputStream(outputBytes))
        val decoded = codec.read(DataInputStream(ByteArrayInputStream(outputBytes.toByteArray())))

        assertEquals(controlFrame, decoded)
    }

    @Test
    fun `rejects operation type in control scope`()
    {
        assertFailsWith<IllegalArgumentException> {
            WorkerControlFrame(
                type = WorkerMessageType.POLISH_TRANSCRIPT,
                payload = ByteArray(0)
            )
        }
    }

    @Test
    fun `rejects payload that is illegal for its message type`()
    {
        assertFailsWith<WorkerProtocolException> {
            codec.write(
                WorkerControlFrame(WorkerMessageType.HELLO, byteArrayOf(1)),
                DataOutputStream(ByteArrayOutputStream())
            )
        }

        val invalidUtf8Message = WorkerProtocolMessage(
            WorkerMessageType.POLISHED_TRANSCRIPT,
            ClientSessionIdentifier("client-7"),
            OperationIdentifier("operation-19"),
            OperationPrivacy.PRIVATE,
            WorkerRequestToken(27),
            byteArrayOf(0xC3.toByte(), 0x28)
        )

        assertFailsWith<WorkerProtocolException> {
            codec.write(invalidUtf8Message, DataOutputStream(ByteArrayOutputStream()))
        }
    }

    @Test
    fun `polish request accepts binary role payload while polished response remains UTF-8`()
    {
        val binaryPromptMessage = WorkerProtocolMessage(
            WorkerMessageType.POLISH_TRANSCRIPT,
            ClientSessionIdentifier("client-7"),
            OperationIdentifier("operation-19"),
            OperationPrivacy.PRIVATE,
            WorkerRequestToken(27),
            byteArrayOf(0xC3.toByte(), 0x28)
        )

        codec.write(binaryPromptMessage, DataOutputStream(ByteArrayOutputStream()))
    }

    @Test
    fun `rejects payload larger than the configured bound`()
    {
        val oversizedMessage = WorkerProtocolMessage(
            WorkerMessageType.POLISH_TRANSCRIPT,
            ClientSessionIdentifier("client-7"),
            OperationIdentifier("operation-19"),
            OperationPrivacy.STANDARD,
            WorkerRequestToken(27),
            ByteArray(1025)
        )

        assertFailsWith<WorkerProtocolException> {
            codec.write(oversizedMessage, DataOutputStream(ByteArrayOutputStream()))
        }
    }

    @Test
    fun `rejects invalid magic version and message type`()
    {
        assertFailsWith<WorkerProtocolException> {
            codec.read(frameWithHeader(magic = 0x01020304, version = WorkerProtocolCodec.PROTOCOL_VERSION, messageType = WorkerMessageType.READY.code))
        }
        assertFailsWith<WorkerProtocolException> {
            codec.read(frameWithHeader(magic = WorkerProtocolCodec.MAGIC, version = 99, messageType = WorkerMessageType.READY.code))
        }
        assertFailsWith<WorkerProtocolException> {
            codec.read(frameWithHeader(magic = WorkerProtocolCodec.MAGIC, version = WorkerProtocolCodec.PROTOCOL_VERSION, messageType = 127))
        }
    }

    @Test
    fun `normalizes truncation at every frame byte boundary`()
    {
        val completeFrame = encode(
            WorkerProtocolMessage(
                WorkerMessageType.POLISH_TRANSCRIPT,
                ClientSessionIdentifier("client-7"),
                OperationIdentifier("operation-19"),
                OperationPrivacy.PRIVATE,
                WorkerRequestToken(27),
                "sensitive sentinel".toByteArray(Charsets.UTF_8)
            )
        )

        for (truncatedLength in 0 until completeFrame.size)
        {
            val exception = assertFailsWith<WorkerProtocolException> {
                codec.read(DataInputStream(ByteArrayInputStream(completeFrame.copyOf(truncatedLength))))
            }

            assertFalse(exception.message.orEmpty().contains("sensitive sentinel"))
        }
    }

    @Test
    fun `defensively owns mutable payload bytes`()
    {
        val sourceBytes = "original".toByteArray(Charsets.UTF_8)
        val message = WorkerProtocolMessage(
            WorkerMessageType.POLISH_TRANSCRIPT,
            ClientSessionIdentifier("client-7"),
            OperationIdentifier("operation-19"),
            OperationPrivacy.PRIVATE,
            WorkerRequestToken(27),
            sourceBytes
        )

        sourceBytes.fill(0)

        assertTrue(message.payload.copyBytes().contentEquals("original".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `rejects unsafe configured frame maximum`()
    {
        assertFailsWith<IllegalArgumentException> {
            WorkerProtocolCodec(Int.MAX_VALUE)
        }
    }

    @Test
    fun `accepts empty final transcript for a silent recording`()
    {
        val finalFrame = WorkerProtocolMessage(
            WorkerMessageType.RECORDING_COMPLETE,
            ClientSessionIdentifier("client-7"),
            OperationIdentifier("operation-19"),
            OperationPrivacy.STANDARD,
            WorkerRequestToken(27),
            ByteArray(0)
        )

        val decoded = assertIs<WorkerProtocolMessage>(
            codec.read(DataInputStream(ByteArrayInputStream(encode(finalFrame))))
        )

        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `rejects an empty partial transcript delta`()
    {
        assertFailsWith<WorkerProtocolException> {
            codec.write(
                WorkerProtocolMessage(
                    WorkerMessageType.AUDIO_CHUNK,
                    ClientSessionIdentifier("client-7"),
                    OperationIdentifier("operation-19"),
                    OperationPrivacy.STANDARD,
                    WorkerRequestToken(27),
                    ByteArray(0)
                ),
                DataOutputStream(ByteArrayOutputStream())
            )
        }
    }

    @Test
    fun `requires versioned recording start payload and accepts empty recording started event`()
    {
        val startPayload = WorkerRecordingStartPayloadCodec.encode(
            WorkerRecordingStartConfiguration(endpointIdentifier = "")
        )
        val startFrame = WorkerProtocolMessage(
            WorkerMessageType.START_RECORDING,
            ClientSessionIdentifier("client-7"),
            OperationIdentifier("operation-19"),
            OperationPrivacy.STANDARD,
            WorkerRequestToken(27),
            startPayload
        )
        val startedFrame = WorkerProtocolMessage(
            WorkerMessageType.RECORDING_STARTED,
            ClientSessionIdentifier("client-7"),
            OperationIdentifier("operation-19"),
            OperationPrivacy.STANDARD,
            WorkerRequestToken(27),
            ByteArray(0)
        )

        assertIs<WorkerProtocolMessage>(
            codec.read(DataInputStream(ByteArrayInputStream(encode(startFrame))))
        )
        assertIs<WorkerProtocolMessage>(
            codec.read(DataInputStream(ByteArrayInputStream(encode(startedFrame))))
        )

        assertFailsWith<WorkerProtocolException> {
            codec.write(
                WorkerProtocolMessage(
                    WorkerMessageType.START_RECORDING,
                    ClientSessionIdentifier("client-7"),
                    OperationIdentifier("operation-19"),
                    OperationPrivacy.STANDARD,
                    WorkerRequestToken(27),
                    ByteArray(0)
                ),
                DataOutputStream(ByteArrayOutputStream())
            )
        }
    }

    private fun encode(frame: WorkerProtocolFrame): ByteArray
    {
        val outputBytes = ByteArrayOutputStream()
        codec.write(frame, DataOutputStream(outputBytes))
        return outputBytes.toByteArray()
    }

    private fun frameWithHeader(magic: Int, version: Int, messageType: Int): DataInputStream
    {
        val frameBody = ByteArrayOutputStream()
        DataOutputStream(frameBody).use { output ->
            output.writeInt(magic)
            output.writeShort(version)
            output.writeByte(messageType)
            output.writeByte(1)
            writeText(output, "client-7")
            writeText(output, "operation-19")
            output.writeByte(0)
            output.writeLong(27)
            output.writeInt(0)
        }
        val framedOutput = ByteArrayOutputStream()
        DataOutputStream(framedOutput).use { output ->
            output.writeInt(frameBody.size())
            output.write(frameBody.toByteArray())
        }
        return DataInputStream(ByteArrayInputStream(framedOutput.toByteArray()))
    }

    private fun writeText(output: DataOutputStream, value: String)
    {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }
}
