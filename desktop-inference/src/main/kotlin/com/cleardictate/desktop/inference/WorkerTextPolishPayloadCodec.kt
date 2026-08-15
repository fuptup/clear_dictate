package com.cleardictate.desktop.inference

import com.cleardictate.domain.TranscriptPolishingRequest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Encodes the trusted system instruction and untrusted user message as separate native-worker roles.
 */
object WorkerTextPolishPayloadCodec
{
    private const val MAGIC = 0x43445450
    private const val VERSION = 1
    private const val HEADER_BYTES = 14

    fun encode(request: TranscriptPolishingRequest): ByteArray
    {
        validateText(request.systemInstruction)
        validateText(request.userMessage)

        val systemInstructionBytes = request.systemInstruction.toByteArray(Charsets.UTF_8)
        val userMessageBytes = request.userMessage.toByteArray(Charsets.UTF_8)

        try
        {
            require(HEADER_BYTES + systemInstructionBytes.size + userMessageBytes.size <= WorkerProtocolCodec.ABSOLUTE_MAXIMUM_PAYLOAD_BYTES) {
                "The text-polish prompt exceeds the worker payload limit."
            }

            return ByteArrayOutputStream(HEADER_BYTES + systemInstructionBytes.size + userMessageBytes.size).use { payloadBytes ->
                DataOutputStream(payloadBytes).use { payload ->
                    payload.writeInt(MAGIC)
                    payload.writeShort(VERSION)
                    payload.writeInt(systemInstructionBytes.size)
                    payload.writeInt(userMessageBytes.size)
                    payload.write(systemInstructionBytes)
                    payload.write(userMessageBytes)
                }
                payloadBytes.toByteArray()
            }
        }
        finally
        {
            systemInstructionBytes.fill(0)
            userMessageBytes.fill(0)
        }
    }

    private fun validateText(text: String)
    {
        require(text.isNotEmpty() && !text.contains('\u0000')) { "Text-polish prompt roles must contain non-null text." }
    }
}
