package com.cleardictate.inference.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PhonePairingPayloadTest
{
    @Test
    fun `round trips one endpoint and token without exposing the token in diagnostics`()
    {
        val payload = PhonePairingPayload("http://192.168.1.20:8765", "private-token")

        assertEquals(payload, PhonePairingPayload.decode(payload.encode()))
        assertFalse(payload.toString().contains("private-token"))
    }

    @Test
    fun `rejects unrelated or extended QR content`()
    {
        assertFailsWith<IllegalArgumentException> { PhonePairingPayload.decode("https://example.com") }
        assertFailsWith<IllegalArgumentException> {
            PhonePairingPayload.decode("CLEAR_DICTATE_PAIRING_V1\nendpoint=http://192.168.1.20:8765\ntoken=value\nignored=true")
        }
    }

    @Test
    fun `rejects blank and multiline values`()
    {
        assertFailsWith<IllegalArgumentException> { PhonePairingPayload("", "token") }
        assertFailsWith<IllegalArgumentException> { PhonePairingPayload("http://pc:8765", "token\nsecond-line") }
        assertFailsWith<IllegalArgumentException> {
            PhonePairingPayload.decode("CLEAR_DICTATE_PAIRING_V1\nendpoint=http://pc:8765\ntoken=")
        }
    }
}
