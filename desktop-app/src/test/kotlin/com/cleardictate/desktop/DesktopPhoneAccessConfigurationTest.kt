package com.cleardictate.desktop

import com.cleardictate.inference.remote.PhonePairingPayload
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopPhoneAccessConfigurationTest
{
    @Test
    fun `creates a pairing payload only for an advertised endpoint`()
    {
        val configuration = DesktopPhoneAccessConfiguration(
            authorizationToken = "private-token",
            port = 8_765,
            bindAddress = InetSocketAddress("192.168.1.20", 8_765),
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

    @Test
    fun `advertises private LAN and Tailscale addresses only`()
    {
        assertTrue(DesktopPhoneAccessConfiguration.isPhoneEndpointAddress(ipv4("192.168.1.20")))
        assertTrue(DesktopPhoneAccessConfiguration.isPhoneEndpointAddress(ipv4("100.64.0.1")))
        assertTrue(DesktopPhoneAccessConfiguration.isPhoneEndpointAddress(ipv4("100.127.255.254")))
        assertFalse(DesktopPhoneAccessConfiguration.isPhoneEndpointAddress(ipv4("100.63.255.254")))
        assertFalse(DesktopPhoneAccessConfiguration.isPhoneEndpointAddress(ipv4("100.128.0.1")))
        assertFalse(DesktopPhoneAccessConfiguration.isPhoneEndpointAddress(ipv4("8.8.8.8")))
    }

    @Test
    fun `distinguishes Tailscale addresses from private LAN addresses`()
    {
        assertTrue(DesktopPhoneAccessConfiguration.isTailscaleAddress(ipv4("100.94.114.97")))
        assertFalse(DesktopPhoneAccessConfiguration.isTailscaleAddress(ipv4("192.168.1.20")))
    }

    /**
     * Converts a literal test address without making the assertions depend on a machine's active interfaces.
     */
    private fun ipv4(value: String): Inet4Address
    {
        return InetAddress.getByName(value) as Inet4Address
    }
}
