package com.cleardictate.desktop

import com.cleardictate.inference.remote.PhonePairingPayload
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64
import java.util.prefs.Preferences

/**
 * Holds the persistent bearer token and LAN addresses needed by a paired Android client.
 */
data class DesktopPhoneAccessConfiguration(
    val authorizationToken: String,
    val port: Int,
    val endpointUrls: List<String>
)
{
    val bindAddress = InetSocketAddress("0.0.0.0", port)

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

        /**
         * Reuses one cryptographically random token across launches and advertises active private IPv4 interfaces.
         */
        fun loadOrCreate(): DesktopPhoneAccessConfiguration
        {
            val preferences = Preferences.userNodeForPackage(DesktopPhoneAccessConfiguration::class.java)
            val storedToken = preferences.get(TOKEN_PREFERENCE, "").takeIf(String::isNotBlank)
            val token = storedToken ?: generateToken().also { generated ->
                preferences.put(TOKEN_PREFERENCE, generated)
                preferences.flush()
            }
            val endpointUrls = activePrivateIpv4Addresses().map { address -> "http://$address:$DEFAULT_PORT" }
            return DesktopPhoneAccessConfiguration(token, DEFAULT_PORT, endpointUrls)
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

        private fun activePrivateIpv4Addresses(): List<String>
        {
            return NetworkInterface.getNetworkInterfaces().toList()
                .filter { networkInterface -> networkInterface.isUp && !networkInterface.isLoopback }
                .flatMap { networkInterface -> networkInterface.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter(Inet4Address::isSiteLocalAddress)
                .mapNotNull(Inet4Address::getHostAddress)
                .distinct()
                .sorted()
        }
    }
}
