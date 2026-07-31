package com.cleardictate.desktop.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptureDeviceListPayloadCodecTest
{
    @Test
    fun `native golden payload decodes without losing endpoint identity`()
    {
        val payload = byteArrayOf(
            0x43, 0x44, 0x41, 0x4C,
            0x00, 0x01,
            0x00, 0x02,
            0x01,
            0x00, 0x00, 0x00, 0x0A,
            0x00, 0x00, 0x00, 0x0F,
            *"endpoint_1".encodeToByteArray(),
            *"Desk microphone".encodeToByteArray(),
            0x00,
            0x00, 0x00, 0x00, 0x0A,
            0x00, 0x00, 0x00, 0x11,
            *"endpoint_2".encodeToByteArray(),
            *"Webcam microphone".encodeToByteArray()
        )

        assertEquals(
            listOf(
                WindowsCaptureDevice("endpoint_1", "Desk microphone", isDefault = true),
                WindowsCaptureDevice("endpoint_2", "Webcam microphone", isDefault = false)
            ),
            CaptureDeviceListPayloadCodec.decode(payload)
        )
    }

    @Test
    fun `payload with trailing bytes is rejected`()
    {
        val emptyPayloadWithTrailingByte = byteArrayOf(0x43, 0x44, 0x41, 0x4C, 0x00, 0x01, 0x00, 0x00, 0x01)

        assertFailsWith<CaptureDeviceListPayloadException> {
            CaptureDeviceListPayloadCodec.decode(emptyPayloadWithTrailingByte)
        }
    }

    @Test
    fun `payload with duplicate endpoint identifiers is rejected`()
    {
        val firstDevice = encodedDevice("same", "First", isDefault = true)
        val secondDevice = encodedDevice("same", "Second", isDefault = false)
        val payload = byteArrayOf(0x43, 0x44, 0x41, 0x4C, 0x00, 0x01, 0x00, 0x02, *firstDevice, *secondDevice)

        assertFailsWith<CaptureDeviceListPayloadException> {
            CaptureDeviceListPayloadCodec.decode(payload)
        }
    }

    private fun encodedDevice(endpointIdentifier: String, friendlyName: String, isDefault: Boolean): ByteArray
    {
        val endpointBytes = endpointIdentifier.encodeToByteArray()
        val friendlyNameBytes = friendlyName.encodeToByteArray()
        return byteArrayOf(
            if (isDefault) 1 else 0,
            0, 0, 0, endpointBytes.size.toByte(),
            0, 0, 0, friendlyNameBytes.size.toByte(),
            *endpointBytes,
            *friendlyNameBytes
        )
    }
}
