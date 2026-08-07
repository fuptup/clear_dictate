package com.cleardictate.desktop

import com.cleardictate.inference.remote.PhonePairingPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopPhoneAccessConfigurationTest
{
    @Test
    fun `creates a pairing payload only for an advertised endpoint`()
    {
        val configuration = DesktopPhoneAccessConfiguration(
            authorizationToken = "private-token",
            port = 8_765,
            endpointUrls = listOf("http://192.168.1.20:8765")
        )

        assertEquals(
            PhonePairingPayload("http://192.168.1.20:8765", "private-token"),
            configuration.pairingPayload("http://192.168.1.20:8765")
        )
        assertFailsWith<IllegalArgumentException> {
            configuration.pairingPayload("http://192.168.1.21:8765")
        }
    }
}
