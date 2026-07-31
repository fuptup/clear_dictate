package com.cleardictate.desktop.inference

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * One active Windows microphone endpoint returned by the native device enumerator.
 * The endpoint identifier must be passed back unchanged when recording starts.
 */
data class WindowsCaptureDevice(
    val endpointIdentifier: String,
    val friendlyName: String,
    val isDefault: Boolean
)

class CaptureDeviceListPayloadException : IllegalArgumentException("The capture-device list payload is invalid.")

/**
 * Strict decoder for the bounded, versioned native capture-device list.
 */
object CaptureDeviceListPayloadCodec
{
    private const val PAYLOAD_MAGIC = 0x4344414C
    private const val PAYLOAD_VERSION = 1
    private const val MAXIMUM_PAYLOAD_BYTES = 64 * 1024
    private const val MAXIMUM_DEVICE_COUNT = 128
    private const val MAXIMUM_ENDPOINT_IDENTIFIER_BYTES = 4000
    private const val MAXIMUM_FRIENDLY_NAME_BYTES = 1000
    private const val DEFAULT_DEVICE_FLAG = 1

    fun decode(payload: ByteArray): List<WindowsCaptureDevice>
    {
        rejectUnless(payload.size in 8..MAXIMUM_PAYLOAD_BYTES)
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        rejectUnless(input.int == PAYLOAD_MAGIC)
        rejectUnless(input.short.toInt() and 0xFFFF == PAYLOAD_VERSION)
        val deviceCount = input.short.toInt() and 0xFFFF
        rejectUnless(deviceCount <= MAXIMUM_DEVICE_COUNT)

        val devices = ArrayList<WindowsCaptureDevice>(deviceCount)
        val endpointIdentifiers = HashSet<String>(deviceCount)
        var defaultDeviceFound = false
        repeat(deviceCount) {
            rejectUnless(input.remaining() >= 9)
            val flags = input.get().toInt() and 0xFF
            rejectUnless(flags and DEFAULT_DEVICE_FLAG.inv() == 0)
            val endpointIdentifierByteCount = input.int
            val friendlyNameByteCount = input.int
            rejectUnless(endpointIdentifierByteCount in 1..MAXIMUM_ENDPOINT_IDENTIFIER_BYTES)
            rejectUnless(friendlyNameByteCount in 1..MAXIMUM_FRIENDLY_NAME_BYTES)
            rejectUnless(endpointIdentifierByteCount <= input.remaining())
            val endpointIdentifier = readStrictUtf8(input, endpointIdentifierByteCount)
            rejectUnless(friendlyNameByteCount <= input.remaining())
            val friendlyName = readStrictUtf8(input, friendlyNameByteCount)
            rejectUnless('\u0000' !in endpointIdentifier && '\u0000' !in friendlyName)
            rejectUnless(endpointIdentifiers.add(endpointIdentifier))

            val isDefault = flags and DEFAULT_DEVICE_FLAG != 0
            rejectUnless(!isDefault || !defaultDeviceFound)
            defaultDeviceFound = defaultDeviceFound || isDefault
            devices += WindowsCaptureDevice(endpointIdentifier, friendlyName, isDefault)
        }
        rejectUnless(!input.hasRemaining())
        return devices
    }

    private fun readStrictUtf8(input: ByteBuffer, byteCount: Int): String
    {
        val encodedText = ByteArray(byteCount)
        input.get(encodedText)
        return try
        {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encodedText))
                .toString()
        }
        catch (_: Exception)
        {
            throw CaptureDeviceListPayloadException()
        }
    }

    private fun rejectUnless(condition: Boolean)
    {
        if (!condition)
        {
            throw CaptureDeviceListPayloadException()
        }
    }
}
