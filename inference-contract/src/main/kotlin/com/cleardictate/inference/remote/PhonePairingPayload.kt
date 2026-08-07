package com.cleardictate.inference.remote

/**
 * Carries one PC endpoint and its bearer token in the versioned text encoded by the desktop QR code.
 */
data class PhonePairingPayload(
    val endpointUrl: String,
    val authorizationToken: String
)
{
    init
    {
        require(endpointUrl.isNotBlank() && !endpointUrl.containsLineBreak()) { "The pairing endpoint must be one non-empty line." }
        require(authorizationToken.isNotBlank() && !authorizationToken.containsLineBreak()) { "The pairing token must be one non-empty line." }
    }

    fun encode(): String
    {
        return "$FORMAT_HEADER\n$ENDPOINT_PREFIX$endpointUrl\n$TOKEN_PREFIX$authorizationToken"
    }

    override fun toString(): String
    {
        return "PhonePairingPayload(endpointUrl=$endpointUrl, authorizationToken=<redacted>)"
    }

    companion object
    {
        private const val FORMAT_HEADER = "CLEAR_DICTATE_PAIRING_V1"
        private const val ENDPOINT_PREFIX = "endpoint="
        private const val TOKEN_PREFIX = "token="

        /**
         * Rejects anything outside the exact three-line format so unrelated QR codes cannot alter pairing state.
         */
        fun decode(encodedPayload: String): PhonePairingPayload
        {
            val lines = encodedPayload.lines()
            require(lines.size == 3 && lines[0] == FORMAT_HEADER) { "The QR code is not a ClearDictate pairing code." }
            require(lines[1].startsWith(ENDPOINT_PREFIX) && lines[2].startsWith(TOKEN_PREFIX)) { "The pairing fields are invalid." }
            return PhonePairingPayload(
                endpointUrl = lines[1].removePrefix(ENDPOINT_PREFIX),
                authorizationToken = lines[2].removePrefix(TOKEN_PREFIX)
            )
        }
    }
}

private fun String.containsLineBreak(): Boolean
{
    return contains('\n') || contains('\r')
}
