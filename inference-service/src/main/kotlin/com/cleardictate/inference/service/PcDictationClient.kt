package com.cleardictate.inference.service

import com.cleardictate.inference.remote.RemoteDictationProtocol
import com.cleardictate.inference.remote.RemotePcmAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Identifies the manually paired PC endpoint without retaining dictated content.
 */
data class PcDictationEndpoint(
    val baseUrl: String,
    val authorizationToken: String
)
{
    init
    {
        val parsedUrl = baseUrl.toHttpUrl()
        require(parsedUrl.scheme == "http" || parsedUrl.scheme == "https") { "The PC endpoint must use HTTP or HTTPS." }
        require(parsedUrl.username.isEmpty() && parsedUrl.password.isEmpty()) { "The PC endpoint cannot contain credentials." }
        require(parsedUrl.query == null && parsedUrl.fragment == null) { "The PC endpoint cannot contain a query or fragment." }
        require(authorizationToken.isNotBlank()) { "The PC authorization token cannot be empty." }
    }

    fun resolve(path: String) = baseUrl.trimEnd('/').plus(path).toHttpUrl()
}

enum class PcDictationFailure
{
    HTTP_FAILURE,
    EMPTY_RESPONSE,
    RESPONSE_TOO_LARGE
}

class PcDictationException(
    val failure: PcDictationFailure,
    val statusCode: Int? = null
) : IllegalStateException("PC dictation request failed: $failure")

/**
 * Sends one completed Android recording to the paired PC and returns only its polished transcript.
 */
class PcDictationClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
)
{
    /**
     * Verifies the paired service without uploading audio.
     */
    suspend fun checkHealth(endpoint: PcDictationEndpoint): Boolean = withContext(Dispatchers.IO)
    {
        val request = authenticatedRequest(endpoint, RemoteDictationProtocol.HEALTH_PATH).get().build()
        httpClient.newCall(request).execute().use { response -> response.isSuccessful }
    }

    /**
     * Takes ownership of the completed PCM array, erases it after the synchronous upload, and bounds the returned text.
     */
    suspend fun dictate(endpoint: PcDictationEndpoint, audio: RemotePcmAudio): String = withContext(Dispatchers.IO)
    {
        val payload = RemoteDictationProtocol.encodeAudio(audio)
        try
        {
            val request = authenticatedRequest(endpoint, RemoteDictationProtocol.DICTATION_PATH)
                .post(payload.toRequestBody(RemoteDictationProtocol.AUDIO_CONTENT_TYPE.toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful)
                {
                    throw PcDictationException(PcDictationFailure.HTTP_FAILURE, response.code)
                }
                val responseBytes = response.body?.byteStream()
                    ?.readNBytes(RemoteDictationProtocol.MAXIMUM_TRANSCRIPT_UTF8_BYTES + 1)
                    ?: throw PcDictationException(PcDictationFailure.EMPTY_RESPONSE)
                try
                {
                    if (responseBytes.size > RemoteDictationProtocol.MAXIMUM_TRANSCRIPT_UTF8_BYTES)
                    {
                        throw PcDictationException(PcDictationFailure.RESPONSE_TOO_LARGE)
                    }
                    val transcript = responseBytes.toString(StandardCharsets.UTF_8)
                    if (transcript.isEmpty())
                    {
                        throw PcDictationException(PcDictationFailure.EMPTY_RESPONSE)
                    }
                    transcript
                }
                finally
                {
                    responseBytes.fill(0)
                }
            }
        }
        finally
        {
            payload.fill(0)
            audio.samples.fill(0)
        }
    }

    private fun authenticatedRequest(endpoint: PcDictationEndpoint, path: String): Request.Builder
    {
        return Request.Builder()
            .url(endpoint.resolve(path))
            .header("Authorization", "Bearer ${endpoint.authorizationToken}")
            .header("Cache-Control", "no-store")
    }

    private companion object
    {
        /**
         * Matches the PC's 120-second ASR boundary plus its 15-second transcript-polishing boundary.
         */
        fun defaultHttpClient(): OkHttpClient
        {
            return OkHttpClient.Builder()
                .readTimeout(135_000, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
