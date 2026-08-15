package com.cleardictate.desktop.inference

import com.cleardictate.inference.ClientSessionIdentifier
import com.cleardictate.inference.OperationIdentifier
import com.cleardictate.inference.OperationPrivacy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Arrays

/**
 * Enumerates every allowed command or event on the private worker pipe.
 */
enum class WorkerMessageType(val code: Int)
{
    HELLO(1),
    READY(2),
    LOAD_MODELS(3),
    START_RECORDING(4),
    STOP_RECORDING(5),
    CANCEL(6),
    CANCELLATION_ACKNOWLEDGED(7),
    AUDIO_CHUNK(8),
    RECORDING_COMPLETE(9),
    POLISH_TRANSCRIPT(11),
    POLISHED_TRANSCRIPT(12),
    ERROR(13),
    SHUTDOWN(14),
    MODELS_LOADED(15),
    CONTROL_ERROR(16),
    OPERATION_CANCELLED(17),
    RECORDING_STARTED(18);

    val isControlMessage: Boolean
        get() = this in CONTROL_MESSAGE_TYPES

    companion object
    {
        fun fromCode(code: Int): WorkerMessageType?
        {
            return entries.firstOrNull { messageType -> messageType.code == code }
        }

        private val CONTROL_MESSAGE_TYPES = setOf(
            HELLO,
            READY,
            LOAD_MODELS,
            SHUTDOWN,
            MODELS_LOADED,
            CONTROL_ERROR
        )
    }
}

/**
 * Carries one bounded worker message while redacting its potentially sensitive payload in diagnostics.
 */
@JvmInline
value class WorkerRequestToken(val value: Long)
{
    init
    {
        require(value > 0) { "A worker request token must be positive." }
    }
}

class WorkerRequestTokenExhaustedException : IllegalStateException(
    "The worker request token epoch is exhausted and the worker must be restarted."
)

/**
 * Allocates collision-free native request tokens within one worker process epoch.
 */
class WorkerRequestTokenAllocator(
    private var nextTokenValue: Long = 1
)
{
    private var isExhausted = false

    init
    {
        require(nextTokenValue > 0) { "The next worker request token must be positive." }
    }

    @Synchronized
    fun allocate(): WorkerRequestToken
    {
        if (isExhausted)
        {
            throw WorkerRequestTokenExhaustedException()
        }

        val allocatedToken = WorkerRequestToken(nextTokenValue)
        if (nextTokenValue == Long.MAX_VALUE)
        {
            isExhausted = true
        }
        else
        {
            nextTokenValue += 1
        }

        return allocatedToken
    }
}

/**
 * Owns a defensive copy of potentially sensitive bytes.
 */
class WorkerPayload private constructor(
    private val ownedBytes: ByteArray
)
{
    val size: Int
        get() = ownedBytes.size

    fun copyBytes(): ByteArray
    {
        return ownedBytes.copyOf()
    }

    internal fun writeTo(output: DataOutputStream)
    {
        output.write(ownedBytes)
    }

    internal fun asReadOnlyByteBuffer(): ByteBuffer
    {
        return ByteBuffer.wrap(ownedBytes).asReadOnlyBuffer()
    }

    /**
     * Best-effort overwrite used immediately after a process-pipe operation.
     */
    internal fun clearOwnedBytes()
    {
        ownedBytes.fill(0)
    }

    override fun equals(other: Any?): Boolean
    {
        return other is WorkerPayload && ownedBytes.contentEquals(other.ownedBytes)
    }

    override fun hashCode(): Int
    {
        return Arrays.hashCode(ownedBytes)
    }

    override fun toString(): String
    {
        return "WorkerPayload(<redacted>)"
    }

    companion object
    {
        fun copyOf(bytes: ByteArray): WorkerPayload
        {
            return WorkerPayload(bytes.copyOf())
        }
    }
}

sealed interface WorkerProtocolFrame
{
    val type: WorkerMessageType
    val payload: WorkerPayload
}

class WorkerControlFrame(
    override val type: WorkerMessageType,
    override val payload: WorkerPayload
) : WorkerProtocolFrame
{
    constructor(type: WorkerMessageType, payload: ByteArray) : this(type, WorkerPayload.copyOf(payload))

    init
    {
        require(type.isControlMessage) { "$type is not legal in a control frame." }
    }

    override fun equals(other: Any?): Boolean
    {
        return other is WorkerControlFrame && type == other.type && payload == other.payload
    }

    override fun hashCode(): Int
    {
        return 31 * type.hashCode() + payload.hashCode()
    }

    override fun toString(): String
    {
        return "WorkerControlFrame(type=$type, payload=<redacted>)"
    }
}

class WorkerProtocolMessage(
    override val type: WorkerMessageType,
    val clientSessionIdentifier: ClientSessionIdentifier,
    val operationIdentifier: OperationIdentifier,
    val privacy: OperationPrivacy,
    val workerRequestToken: WorkerRequestToken,
    override val payload: WorkerPayload
) : WorkerProtocolFrame
{
    constructor(
        type: WorkerMessageType,
        clientSessionIdentifier: ClientSessionIdentifier,
        operationIdentifier: OperationIdentifier,
        privacy: OperationPrivacy,
        workerRequestToken: WorkerRequestToken,
        payload: ByteArray
    ) : this(
        type,
        clientSessionIdentifier,
        operationIdentifier,
        privacy,
        workerRequestToken,
        WorkerPayload.copyOf(payload)
    )

    init
    {
        require(!type.isControlMessage) { "$type is not legal in an operation frame." }
    }

    override fun toString(): String
    {
        return "WorkerProtocolMessage(type=$type, clientSessionIdentifier=$clientSessionIdentifier, operationIdentifier=$operationIdentifier, privacy=$privacy, workerRequestToken=$workerRequestToken, payload=<redacted>)"
    }
}

/**
 * Reports a fixed protocol category without including malformed or sensitive bytes.
 */
class WorkerProtocolException(
    val category: WorkerProtocolFailure
) : Exception("Worker protocol failure: $category")

enum class WorkerProtocolFailure
{
    INVALID_FRAME_LENGTH,
    INVALID_MAGIC,
    UNSUPPORTED_VERSION,
    UNKNOWN_MESSAGE_TYPE,
    INVALID_FRAME_SCOPE,
    MESSAGE_SCOPE_MISMATCH,
    INVALID_IDENTIFIER,
    INVALID_PRIVACY,
    INVALID_WORKER_REQUEST_TOKEN,
    INVALID_PAYLOAD_LENGTH,
    INVALID_MESSAGE_PAYLOAD,
    INVALID_UTF8,
    TRUNCATED_FRAME,
    TRAILING_BYTES
}

/**
 * Encodes and decodes strict length-prefixed frames for anonymous process pipes.
 */
class WorkerProtocolCodec(
    private val maximumPayloadBytes: Int = DEFAULT_MAXIMUM_PAYLOAD_BYTES
)
{
    init
    {
        require(maximumPayloadBytes in 0..ABSOLUTE_MAXIMUM_PAYLOAD_BYTES) {
            "Maximum payload bytes must be between 0 and $ABSOLUTE_MAXIMUM_PAYLOAD_BYTES."
        }
    }

    /**
     * Writes one complete frame and flushes it so control messages are not buffered indefinitely.
     */
    fun write(frame: WorkerProtocolFrame, output: DataOutputStream)
    {
        if (frame.payload.size > maximumPayloadBytes)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.INVALID_PAYLOAD_LENGTH)
        }

        validateMessagePayload(frame.type, frame.payload)

        val frameBodyBytes = ByteArrayOutputStream()
        DataOutputStream(frameBodyBytes).use { frameBody ->
            frameBody.writeInt(MAGIC)
            frameBody.writeShort(PROTOCOL_VERSION)
            frameBody.writeByte(frame.type.code)

            when (frame)
            {
                is WorkerControlFrame ->
                {
                    frameBody.writeByte(FrameScope.CONTROL.code)
                }

                is WorkerProtocolMessage ->
                {
                    frameBody.writeByte(FrameScope.OPERATION.code)
                    writeIdentifier(frameBody, frame.clientSessionIdentifier.value)
                    writeIdentifier(frameBody, frame.operationIdentifier.value)
                    frameBody.writeByte(privacyCode(frame.privacy))
                    frameBody.writeLong(frame.workerRequestToken.value)
                }
            }

            frameBody.writeInt(frame.payload.size)
            frame.payload.writeTo(frameBody)
        }

        output.writeInt(frameBodyBytes.size())
        output.write(frameBodyBytes.toByteArray())
        output.flush()
    }

    /**
     * Reads exactly one frame and rejects malformed, oversized, truncated, or ambiguous data.
     */
    fun read(input: DataInputStream): WorkerProtocolFrame
    {
        val frameLength = try
        {
            input.readInt()
        }
        catch (_: EOFException)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.TRUNCATED_FRAME)
        }

        val maximumFrameLength = maximumPayloadBytes.toLong() + MAXIMUM_FRAME_OVERHEAD_BYTES.toLong()
        if (frameLength < MINIMUM_FRAME_BYTES || frameLength.toLong() > maximumFrameLength)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.INVALID_FRAME_LENGTH)
        }

        val frameBytes = ByteArray(frameLength)

        try
        {
            input.readFully(frameBytes)
        }
        catch (_: EOFException)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.TRUNCATED_FRAME)
        }

        return try
        {
            decodeFrame(frameBytes)
        }
        catch (exception: WorkerProtocolException)
        {
            throw exception
        }
        catch (_: EOFException)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.TRUNCATED_FRAME)
        }
    }

    private fun decodeFrame(frameBytes: ByteArray): WorkerProtocolFrame
    {
        val frameInput = DataInputStream(ByteArrayInputStream(frameBytes))
        if (frameInput.readInt() != MAGIC)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.INVALID_MAGIC)
        }

        if (frameInput.readUnsignedShort() != PROTOCOL_VERSION)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.UNSUPPORTED_VERSION)
        }

        val messageType = WorkerMessageType.fromCode(frameInput.readUnsignedByte())
            ?: throw WorkerProtocolException(WorkerProtocolFailure.UNKNOWN_MESSAGE_TYPE)
        val frameScope = FrameScope.fromCode(frameInput.readUnsignedByte())
            ?: throw WorkerProtocolException(WorkerProtocolFailure.INVALID_FRAME_SCOPE)

        val operationIdentity = if (frameScope == FrameScope.OPERATION)
        {
            OperationFrameIdentity(
                clientSessionIdentifier = decodeClientIdentifier(readIdentifier(frameInput)),
                operationIdentifier = decodeOperationIdentifier(readIdentifier(frameInput)),
                privacy = decodePrivacy(frameInput.readUnsignedByte()),
                workerRequestToken = decodeWorkerRequestToken(frameInput.readLong())
            )
        }
        else
        {
            null
        }

        val payloadLength = frameInput.readInt()

        if (payloadLength < 0 || payloadLength > maximumPayloadBytes || payloadLength > frameInput.available())
        {
            throw WorkerProtocolException(WorkerProtocolFailure.INVALID_PAYLOAD_LENGTH)
        }

        val payload = ByteArray(payloadLength)
        frameInput.readFully(payload)

        if (frameInput.available() != 0)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.TRAILING_BYTES)
        }

        val ownedPayload = WorkerPayload.copyOf(payload)
        validateMessagePayload(messageType, ownedPayload)

        return when (frameScope)
        {
            FrameScope.CONTROL ->
            {
                if (!messageType.isControlMessage)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure.MESSAGE_SCOPE_MISMATCH)
                }

                WorkerControlFrame(messageType, ownedPayload)
            }

            FrameScope.OPERATION ->
            {
                if (messageType.isControlMessage || operationIdentity == null)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure.MESSAGE_SCOPE_MISMATCH)
                }

                WorkerProtocolMessage(
                    type = messageType,
                    clientSessionIdentifier = operationIdentity.clientSessionIdentifier,
                    operationIdentifier = operationIdentity.operationIdentifier,
                    privacy = operationIdentity.privacy,
                    workerRequestToken = operationIdentity.workerRequestToken,
                    payload = ownedPayload
                )
            }
        }
    }

    private fun writeIdentifier(output: DataOutputStream, value: String)
    {
        val identifierBytes = value.toByteArray(Charsets.UTF_8)

        if (identifierBytes.isEmpty() || identifierBytes.size > MAXIMUM_IDENTIFIER_BYTES)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.INVALID_IDENTIFIER)
        }

        output.writeInt(identifierBytes.size)
        output.write(identifierBytes)
    }

    private fun readIdentifier(input: DataInputStream): String
    {
        val identifierLength = input.readInt()

        if (identifierLength <= 0 || identifierLength > MAXIMUM_IDENTIFIER_BYTES || identifierLength > input.available())
        {
            throw WorkerProtocolException(WorkerProtocolFailure.INVALID_IDENTIFIER)
        }

        val identifierBytes = ByteArray(identifierLength)
        input.readFully(identifierBytes)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try
        {
            decoder.decode(ByteBuffer.wrap(identifierBytes)).toString()
        }
        catch (_: java.nio.charset.CharacterCodingException)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.INVALID_UTF8)
        }
    }

    private fun privacyCode(privacy: OperationPrivacy): Int
    {
        return when (privacy)
        {
            OperationPrivacy.STANDARD -> 0
            OperationPrivacy.PRIVATE -> 1
        }
    }

    private fun decodePrivacy(code: Int): OperationPrivacy
    {
        return when (code)
        {
            0 -> OperationPrivacy.STANDARD
            1 -> OperationPrivacy.PRIVATE
            else -> throw WorkerProtocolException(WorkerProtocolFailure.INVALID_PRIVACY)
        }
    }

    private fun decodeWorkerRequestToken(value: Long): WorkerRequestToken
    {
        return try
        {
            WorkerRequestToken(value)
        }
        catch (_: IllegalArgumentException)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.INVALID_WORKER_REQUEST_TOKEN)
        }
    }

    private fun validateMessagePayload(type: WorkerMessageType, payload: WorkerPayload)
    {
        val payloadSize = payload.size

        when (type)
        {
            WorkerMessageType.HELLO,
            WorkerMessageType.READY,
            WorkerMessageType.MODELS_LOADED,
            WorkerMessageType.SHUTDOWN,
            WorkerMessageType.STOP_RECORDING,
            WorkerMessageType.CANCEL,
            WorkerMessageType.CANCELLATION_ACKNOWLEDGED,
            WorkerMessageType.OPERATION_CANCELLED,
            WorkerMessageType.RECORDING_STARTED,
            WorkerMessageType.RECORDING_COMPLETE ->
            {
                if (payloadSize != 0)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure.INVALID_MESSAGE_PAYLOAD)
                }
            }

            WorkerMessageType.START_RECORDING ->
            {
                if (payloadSize !in MINIMUM_RECORDING_START_PAYLOAD_BYTES..MAXIMUM_RECORDING_START_METADATA_BYTES)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure.INVALID_MESSAGE_PAYLOAD)
                }
            }

            WorkerMessageType.ERROR,
            WorkerMessageType.CONTROL_ERROR ->
            {
                if (payloadSize != FIXED_VALUE_PAYLOAD_BYTES)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure.INVALID_MESSAGE_PAYLOAD)
                }
            }

            WorkerMessageType.LOAD_MODELS ->
            {
                if (payloadSize !in 1..MAXIMUM_MODEL_METADATA_BYTES)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure.INVALID_MESSAGE_PAYLOAD)
                }
            }

            WorkerMessageType.AUDIO_CHUNK ->
            {
                if (payloadSize !in 1..ABSOLUTE_MAXIMUM_PAYLOAD_BYTES)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure.INVALID_MESSAGE_PAYLOAD)
                }
            }

            WorkerMessageType.POLISH_TRANSCRIPT ->
            {
                if (payloadSize !in 1..ABSOLUTE_MAXIMUM_PAYLOAD_BYTES)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure.INVALID_MESSAGE_PAYLOAD)
                }
            }

            WorkerMessageType.POLISHED_TRANSCRIPT ->
            {
                if (payloadSize !in 1..ABSOLUTE_MAXIMUM_PAYLOAD_BYTES)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure.INVALID_MESSAGE_PAYLOAD)
                }

                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)

                try
                {
                    decoder.decode(payload.asReadOnlyByteBuffer())
                }
                catch (_: java.nio.charset.CharacterCodingException)
                {
                    throw WorkerProtocolException(WorkerProtocolFailure.INVALID_UTF8)
                }
            }
        }
    }

    private fun decodeClientIdentifier(value: String): ClientSessionIdentifier
    {
        return try
        {
            ClientSessionIdentifier(value)
        }
        catch (_: IllegalArgumentException)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.INVALID_IDENTIFIER)
        }
    }

    private fun decodeOperationIdentifier(value: String): OperationIdentifier
    {
        return try
        {
            OperationIdentifier(value)
        }
        catch (_: IllegalArgumentException)
        {
            throw WorkerProtocolException(WorkerProtocolFailure.INVALID_IDENTIFIER)
        }
    }

    companion object
    {
        const val MAGIC: Int = 0x43444950
        const val PROTOCOL_VERSION: Int = 5
        const val ABSOLUTE_MAXIMUM_PAYLOAD_BYTES: Int = 64 * 1024
        const val DEFAULT_MAXIMUM_PAYLOAD_BYTES: Int = ABSOLUTE_MAXIMUM_PAYLOAD_BYTES
        private const val FIXED_VALUE_PAYLOAD_BYTES: Int = 4
        private const val MINIMUM_RECORDING_START_PAYLOAD_BYTES: Int = 10
        private const val MAXIMUM_RECORDING_START_METADATA_BYTES: Int = 4096
        private const val MAXIMUM_MODEL_METADATA_BYTES: Int = 4096
        private const val MAXIMUM_IDENTIFIER_BYTES: Int = 64
        private const val MINIMUM_FRAME_BYTES: Int = 4 + 2 + 1 + 1 + 4
        private const val MAXIMUM_FRAME_OVERHEAD_BYTES: Int = 4 + 2 + 1 + 1 + 4 + MAXIMUM_IDENTIFIER_BYTES + 4 + MAXIMUM_IDENTIFIER_BYTES + 1 + 8 + 4
    }
}

private enum class FrameScope(val code: Int)
{
    CONTROL(0),
    OPERATION(1);

    companion object
    {
        fun fromCode(code: Int): FrameScope?
        {
            return entries.firstOrNull { scope -> scope.code == code }
        }
    }
}

private data class OperationFrameIdentity(
    val clientSessionIdentifier: ClientSessionIdentifier,
    val operationIdentifier: OperationIdentifier,
    val privacy: OperationPrivacy,
    val workerRequestToken: WorkerRequestToken
)

/**
 * Rejects late or cross-client events before the application can display or insert them.
 */
class ActiveOperationMessageFilter(
    private val activeClientSessionIdentifier: ClientSessionIdentifier,
    private val activeOperationIdentifier: OperationIdentifier,
    private val activeWorkerRequestToken: WorkerRequestToken
)
{
    fun accepts(message: WorkerProtocolMessage): Boolean
    {
        return message.clientSessionIdentifier == activeClientSessionIdentifier &&
            message.operationIdentifier == activeOperationIdentifier &&
            message.workerRequestToken == activeWorkerRequestToken
    }
}
