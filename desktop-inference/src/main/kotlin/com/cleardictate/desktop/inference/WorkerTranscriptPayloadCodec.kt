package com.cleardictate.desktop.inference

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * One immutable Moonshine line change copied before the next native model call.
 */
data class WorkerTranscriptDelta(
    val lineIdentifier: Long,
    val isNew: Boolean,
    val isUpdated: Boolean,
    val isComplete: Boolean,
    val text: String
)
{
    init
    {
        require(lineIdentifier != 0L) { "A transcript line identifier must be non-zero." }
        require(isNew || isUpdated) { "A transcript delta must describe a new or updated line." }
        require('\u0000' !in text) { "Transcript text must not contain a null character." }
    }
}

class WorkerTranscriptPayloadException : Exception("Worker transcript payload failure.")

object WorkerTranscriptPayloadCodec
{
    private const val MAGIC = 0x43445444
    private const val VERSION = 1
    private const val IS_NEW_FLAG = 1
    private const val IS_UPDATED_FLAG = 2
    private const val IS_COMPLETE_FLAG = 4
    private const val KNOWN_FLAGS = IS_NEW_FLAG or IS_UPDATED_FLAG or IS_COMPLETE_FLAG
    private const val HEADER_BYTES = 19
    private const val MAXIMUM_TEXT_BYTES = 64 * 1024 - HEADER_BYTES

    fun encode(delta: WorkerTranscriptDelta): ByteArray
    {
        val textBytes = delta.text.toByteArray(Charsets.UTF_8)
        if (textBytes.size > MAXIMUM_TEXT_BYTES)
        {
            throw WorkerTranscriptPayloadException()
        }

        val encodedPayload = ByteArray(HEADER_BYTES + textBytes.size)
        try
        {
            val output = ByteBuffer.wrap(encodedPayload)
            output.putInt(MAGIC)
            output.putShort(VERSION.toShort())
            output.putLong(delta.lineIdentifier)
            var flags = 0
            flags = flags or if (delta.isNew) IS_NEW_FLAG else 0
            flags = flags or if (delta.isUpdated) IS_UPDATED_FLAG else 0
            flags = flags or if (delta.isComplete) IS_COMPLETE_FLAG else 0
            output.put(flags.toByte())
            output.putInt(textBytes.size)
            output.put(textBytes)
            return encodedPayload
        }
        finally
        {
            textBytes.fill(0)
        }
    }

    fun decode(payload: ByteArray): WorkerTranscriptDelta
    {
        try
        {
            val input = DataInputStream(ByteArrayInputStream(payload))
            if (input.readInt() != MAGIC || input.readUnsignedShort() != VERSION)
            {
                throw WorkerTranscriptPayloadException()
            }

            val lineIdentifier = input.readLong()
            val flags = input.readUnsignedByte()
            val hasLineChange = flags and (IS_NEW_FLAG or IS_UPDATED_FLAG) != 0
            if (lineIdentifier == 0L || flags and KNOWN_FLAGS.inv() != 0 || !hasLineChange)
            {
                throw WorkerTranscriptPayloadException()
            }

            val textByteCount = input.readInt()
            if (textByteCount !in 0..MAXIMUM_TEXT_BYTES || textByteCount != input.available())
            {
                throw WorkerTranscriptPayloadException()
            }
            val textBytes = ByteArray(textByteCount)
            input.readFully(textBytes)
            val text = try
            {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(textBytes))
                    .toString()
            }
            finally
            {
                textBytes.fill(0)
            }

            return WorkerTranscriptDelta(
                lineIdentifier = lineIdentifier,
                isNew = flags and IS_NEW_FLAG != 0,
                isUpdated = flags and IS_UPDATED_FLAG != 0,
                isComplete = flags and IS_COMPLETE_FLAG != 0,
                text = text
            )
        }
        catch (exception: WorkerTranscriptPayloadException)
        {
            throw exception
        }
        catch (_: Exception)
        {
            throw WorkerTranscriptPayloadException()
        }
    }
}
