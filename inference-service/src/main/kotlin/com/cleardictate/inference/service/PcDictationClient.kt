package com.cleardictate.inference.service

import com.cleardictate.inference.remote.RemoteDictationProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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

/**
 * Separates a reachable PC that is still loading AI from a network or authentication failure.
 */
enum class PcHealthStatus
{
    READY,
    PREPARING_AI,
    UNAVAILABLE
}

class PcDictationException(
    val failure: PcDictationFailure,
    val statusCode: Int? = null
) : IllegalStateException("PC dictation request failed: $failure")

/**
 * Defines health verification and a live utterance stream independent of OkHttp details.
 */
interface PcDictationTransport
{
    suspend fun checkHealth(endpoint: PcDictationEndpoint): PcHealthStatus
    fun openDictation(endpoint: PcDictationEndpoint): PcDictationStream
}

/**
 * Owns one authenticated chunked request from microphone activation through release or cancellation.
 */
interface PcDictationStream
{
    fun sendPcm16(samples: ShortArray, sampleCount: Int)
    fun finish(): String
    fun cancel()
}

/**
 * Streams Android microphone fragments to the paired PC and returns only the final polished transcript.
 */
class PcDictationClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) : PcDictationTransport
{
    /**
     * Verifies the paired service without uploading audio.
     */
    override suspend fun checkHealth(endpoint: PcDictationEndpoint): PcHealthStatus = withContext(Dispatchers.IO)
    {
        val request = authenticatedRequest(endpoint, RemoteDictationProtocol.HEALTH_PATH).get().build()
        val call = httpClient.newCall(request)
        call.timeout().timeout(HEALTH_CHECK_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        call.await().use { response ->
            val healthState = response.header(RemoteDictationProtocol.HEALTH_STATE_HEADER)
            when
            {
                response.isSuccessful && healthState == RemoteDictationProtocol.HEALTH_STATE_READY -> PcHealthStatus.READY
                response.code == 503 && healthState == RemoteDictationProtocol.HEALTH_STATE_PREPARING_AI -> PcHealthStatus.PREPARING_AI
                else -> PcHealthStatus.UNAVAILABLE
            }
        }
    }

    /**
     * Begins the chunked HTTP body immediately so subsequent microphone buffers travel while the user is still speaking.
     */
    override fun openDictation(endpoint: PcDictationEndpoint): PcDictationStream
    {
        val requestBody = PcStreamingRequestBody()
        val request = authenticatedRequest(endpoint, RemoteDictationProtocol.DICTATION_PATH).post(requestBody).build()
        val call = httpClient.newCall(request)
        val responseFuture = CompletableFuture<Response>()
        call.enqueue(object : Callback
        {
            override fun onFailure(call: Call, e: IOException)
            {
                responseFuture.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response)
            {
                responseFuture.complete(response)
            }
        })
        return OkHttpPcDictationStream(call, requestBody, responseFuture)
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
            override fun onFailure(call: Call, e: IOException)
            {
                continuation.resumeWith(Result.failure(e))
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

    internal companion object
    {
        const val HEALTH_CHECK_TIMEOUT_MILLISECONDS = 5_000L
        const val DICTATION_RESPONSE_TIMEOUT_MILLISECONDS = 135_000L

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

/**
 * Streams copied PCM frames through OkHttp's background request writer and exposes one synchronous terminal result to the recognition thread.
 */
private class OkHttpPcDictationStream(
    private val call: Call,
    private val requestBody: PcStreamingRequestBody,
    private val responseFuture: CompletableFuture<Response>
) : PcDictationStream
{
    private val terminal = AtomicBoolean(false)

    override fun sendPcm16(samples: ShortArray, sampleCount: Int)
    {
        check(!terminal.get()) { "The PC dictation stream is already finished." }
        requestBody.send(samples, sampleCount)
    }

    override fun finish(): String
    {
        check(terminal.compareAndSet(false, true)) { "The PC dictation stream is already finished." }
        requestBody.finish()
        val response = try
        {
            responseFuture.get(PcDictationClient.DICTATION_RESPONSE_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        }
        catch (_: TimeoutException)
        {
            cancelCallAndCloseAnyResponse()
            throw IOException("The PC did not finish dictation within its response timeout.")
        }
        catch (failure: ExecutionException)
        {
            throw failure.cause ?: failure
        }
        return response.use(::readTranscript)
    }

    override fun cancel()
    {
        if (terminal.compareAndSet(false, true))
        {
            requestBody.cancel()
            cancelCallAndCloseAnyResponse()
        }
    }

    private fun cancelCallAndCloseAnyResponse()
    {
        responseFuture.thenAccept(Response::close)
        call.cancel()
    }

    private fun readTranscript(response: Response): String
    {
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
            return transcript
        }
        finally
        {
            responseBytes.fill(0)
        }
    }
}

/**
 * Bridges synchronous microphone callbacks to OkHttp's request thread while retaining at most the protocol-bounded recording already allowed by the product.
 */
private class PcStreamingRequestBody : RequestBody()
{
    private val commands = LinkedBlockingQueue<StreamCommand>()
    private val terminal = AtomicBoolean(false)
    private var sentSampleCount = 0

    override fun contentType() = RemoteDictationProtocol.STREAM_AUDIO_CONTENT_TYPE.toMediaType()

    override fun contentLength() = -1L

    fun send(samples: ShortArray, sampleCount: Int)
    {
        check(!terminal.get()) { "The streaming request body is already finished." }
        require(sampleCount in 1..samples.size) { "The PCM sample count is invalid." }
        require(sentSampleCount.toLong() + sampleCount <= RemoteDictationProtocol.MAXIMUM_SAMPLE_COUNT) { "The recording exceeds the protocol boundary." }
        sentSampleCount += sampleCount
        commands.add(StreamCommand.Audio(samples.copyOf(sampleCount)))
    }

    fun finish()
    {
        check(terminal.compareAndSet(false, true)) { "The streaming request body is already finished." }
        commands.add(StreamCommand.Finish)
    }

    fun cancel()
    {
        if (terminal.compareAndSet(false, true))
        {
            scrubQueuedAudio()
            commands.add(StreamCommand.Cancel)
        }
    }

    override fun writeTo(sink: BufferedSink)
    {
        val output = DataOutputStream(sink.outputStream())
        try
        {
            RemoteDictationProtocol.writeStreamHeader(output)
            output.flush()
            while (true)
            {
                when (val command = commands.take())
                {
                    is StreamCommand.Audio ->
                    {
                        try
                        {
                            RemoteDictationProtocol.writeAudioFrame(output, command.samples, command.samples.size)
                            output.flush()
                        }
                        finally
                        {
                            command.samples.fill(0)
                        }
                    }
                    StreamCommand.Finish ->
                    {
                        RemoteDictationProtocol.writeStreamFinish(output)
                        output.flush()
                        return
                    }
                    StreamCommand.Cancel -> throw IOException("The streaming dictation was cancelled.")
                }
            }
        }
        catch (_: InterruptedException)
        {
            Thread.currentThread().interrupt()
            throw IOException("The streaming request writer was interrupted.")
        }
        finally
        {
            scrubQueuedAudio()
        }
    }

    private fun scrubQueuedAudio()
    {
        while (true)
        {
            val command = commands.poll() ?: return
            if (command is StreamCommand.Audio)
            {
                command.samples.fill(0)
            }
        }
    }

    private sealed interface StreamCommand
    {
        data class Audio(val samples: ShortArray) : StreamCommand
        data object Finish : StreamCommand
        data object Cancel : StreamCommand
    }
}
