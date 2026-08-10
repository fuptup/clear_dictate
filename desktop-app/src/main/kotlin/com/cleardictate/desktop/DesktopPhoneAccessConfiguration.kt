package com.cleardictate.desktop

import com.cleardictate.inference.remote.PhonePairingPayload
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64
import java.util.prefs.Preferences

/**
 * Holds the persistent bearer token and trusted network addresses needed by a paired Android client.
 */
data class DesktopPhoneAccessConfiguration(
    val authorizationToken: String,
    val port: Int,
    val bindAddress: InetSocketAddress,
    val endpointUrls: List<String>
)
{
    /**
     * Creates the exact payload shown in the QR code after confirming the address belongs to this host configuration.
     */
    fun pairingPayload(endpointUrl: String): PhonePairingPayload
    {
        require(endpointUrl in endpointUrls) { "The pairing endpoint is not advertised by this PC." }
        return PhonePairingPayload(endpointUrl, authorizationToken)
    }

    companion object
    {
        private const val DEFAULT_PORT = 8_765
        private const val TOKEN_PREFERENCE = "phone_authorization_token"
        private const val TOKEN_BYTE_COUNT = 32
        private const val TAILSCALE_FIRST_OCTET = 100
        private val TAILSCALE_SECOND_OCTET_RANGE = 64..127

        /**
         * Reuses one cryptographically random token and prefers one Tailscale endpoint so the server does not also bind to an untrusted LAN.
         * Machines without Tailscale retain local-network pairing by binding their active private IPv4 interfaces.
         */
        fun loadOrCreate(): DesktopPhoneAccessConfiguration
        {
            val preferences = Preferences.userNodeForPackage(DesktopPhoneAccessConfiguration::class.java)
            val storedToken = preferences.get(TOKEN_PREFERENCE, "").takeIf(String::isNotBlank)
            val token = storedToken ?: generateToken().also { generated ->
                preferences.put(TOKEN_PREFERENCE, generated)
                preferences.flush()
            }
            val activeAddresses = activePhoneIpv4Addresses()
            val tailscaleAddress = activeAddresses.firstOrNull(::isTailscaleAddress)
            val endpointAddresses = tailscaleAddress?.let(::listOf) ?: activeAddresses
            val bindAddress = InetSocketAddress(tailscaleAddress?.hostAddress ?: "0.0.0.0", DEFAULT_PORT)
            val endpointUrls = endpointAddresses.map { address -> "http://${address.hostAddress}:$DEFAULT_PORT" }
            return DesktopPhoneAccessConfiguration(token, DEFAULT_PORT, bindAddress, endpointUrls)
        }

        /**
         * Accepts private LAN addresses and Tailscale's dedicated CGNAT range while excluding unrelated public interfaces.
         */
        internal fun isPhoneEndpointAddress(address: Inet4Address): Boolean
        {
            if (address.isSiteLocalAddress)
            {
                return true
            }

            return isTailscaleAddress(address)
        }

        /**
         * Recognizes Tailscale's reserved CGNAT range so ClearDictate can bind exclusively to its encrypted interface.
         */
        internal fun isTailscaleAddress(address: Inet4Address): Boolean
        {
            val octets = address.address
            val firstOctet = octets[0].toInt() and 0xff
            val secondOctet = octets[1].toInt() and 0xff
            return firstOctet == TAILSCALE_FIRST_OCTET && secondOctet in TAILSCALE_SECOND_OCTET_RANGE
        }

        private fun generateToken(): String
        {
            val tokenBytes = ByteArray(TOKEN_BYTE_COUNT)
            SecureRandom().nextBytes(tokenBytes)
            try
            {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
            }
            finally
            {
                tokenBytes.fill(0)
            }
        }

        private fun activePhoneIpv4Addresses(): List<Inet4Address>
        {
            return NetworkInterface.getNetworkInterfaces().toList()
                .filter { networkInterface -> networkInterface.isUp && !networkInterface.isLoopback }
                .flatMap { networkInterface -> networkInterface.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter(::isPhoneEndpointAddress)
                .distinctBy(Inet4Address::getHostAddress)
                .sortedBy(Inet4Address::getHostAddress)
        }
    }
}
