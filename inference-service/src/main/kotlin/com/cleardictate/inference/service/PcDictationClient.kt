package com.cleardictate.inference.service

import com.cleardictate.inference.remote.RemoteDictationProtocol
import com.cleardictate.inference.remote.RemotePcmAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
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

    override fun toString(): String
    {
        return "PcDictationEndpoint(baseUrl=$baseUrl, authorizationToken=<redacted>)"
    }
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
 * Defines the cancellable network operations used by endpoint verification and completed recording upload.
 */
interface PcDictationTransport
{
    suspend fun checkHealth(endpoint: PcDictationEndpoint): Boolean
    suspend fun dictate(endpoint: PcDictationEndpoint, audio: RemotePcmAudio): String
}

/**
 * Sends one completed Android recording to the paired PC and returns only its polished transcript.
 */
class PcDictationClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) : PcDictationTransport
{
    /**
     * Verifies the paired service without uploading audio.
     */
    override suspend fun checkHealth(endpoint: PcDictationEndpoint): Boolean = withContext(Dispatchers.IO)
    {
        val request = authenticatedRequest(endpoint, RemoteDictationProtocol.HEALTH_PATH).get().build()
        val call = httpClient.newCall(request)
        call.timeout().timeout(HEALTH_CHECK_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        call.await().use { response -> response.isSuccessful }
    }

    /**
     * Takes ownership of the completed PCM array, erases it after the synchronous upload, and bounds the returned text.
     */
    override suspend fun dictate(endpoint: PcDictationEndpoint, audio: RemotePcmAudio): String = withContext(Dispatchers.IO)
    {
        val payload = RemoteDictationProtocol.encodeAudio(audio)
        try
        {
            val request = authenticatedRequest(endpoint, RemoteDictationProtocol.DICTATION_PATH)
                .post(payload.toRequestBody(RemoteDictationProtocol.AUDIO_CONTENT_TYPE.toMediaType()))
                .build()
            httpClient.newCall(request).await().use { response ->
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

    /**
     * Couples coroutine cancellation to OkHttp cancellation so an abandoned editor session cannot retain audio in flight.
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback
        {
            override fun onFailure(call: Call, exception: IOException)
            {
                continuation.resumeWith(Result.failure(exception))
            }

            override fun onResponse(call: Call, response: Response)
            {
                if (!continuation.isActive)
                {
                    response.close()
                }
                else
                {
                    continuation.resumeWith(Result.success(response))
                }
            }
        })
    }

    private companion object
    {
        const val HEALTH_CHECK_TIMEOUT_MILLISECONDS = 5_000L

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
