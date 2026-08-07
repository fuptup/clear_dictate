package com.cleardictate.desktop

import com.cleardictate.inference.remote.RemoteAudioPayloadException
import com.cleardictate.inference.remote.RemoteDictationProtocol
import com.cleardictate.inference.remote.RemotePcmAudio
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Processes one completed phone recording without exposing HTTP details to the model pipeline.
 */
fun interface DesktopRemoteDictationProcessor
{
    suspend fun process(audio: RemotePcmAudio): String
}

/**
 * Hosts the first authenticated phone-to-PC transport boundary.
 * The service accepts only bounded completed recordings and never returns raw transcripts or internal failures.
 */
class DesktopRemoteDictationServer(
    private val bindAddress: InetSocketAddress,
    authorizationToken: String,
    private val dictationProcessor: DesktopRemoteDictationProcessor
) : AutoCloseable
{
    private val expectedAuthorization = "Bearer $authorizationToken".toByteArray(StandardCharsets.UTF_8)
    private val ownershipLock = Any()
    private var activeServer: HttpServer? = null
    private var activeExecutor: ExecutorService? = null

    init
    {
        require(authorizationToken.isNotBlank()) { "Phone authorization requires a non-empty token." }
    }

    val localAddress: InetSocketAddress?
        get() = synchronized(ownershipLock) { activeServer?.address }

    /**
     * Binds both endpoints once. Two daemon threads allow health checks while the serialized GPU operation is running.
     */
    fun start()
    {
        synchronized(ownershipLock)
        {
            check(activeServer == null) { "The phone dictation server is already running." }
            val server = HttpServer.create(bindAddress, 0)
            val executor = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "cleardictate-phone-request").apply { isDaemon = true }
            }
            try
            {
                server.executor = executor
                server.createContext(RemoteDictationProtocol.HEALTH_PATH, ::handleHealth)
                server.createContext(RemoteDictationProtocol.DICTATION_PATH, ::handleDictation)
                server.start()
                activeExecutor = executor
                activeServer = server
            }
            catch (throwable: Throwable)
            {
                server.stop(0)
                executor.shutdownNow()
                throw throwable
            }
        }
    }

    override fun close()
    {
        val resources = synchronized(ownershipLock)
        {
            Pair(activeServer.also { activeServer = null }, activeExecutor.also { activeExecutor = null })
        }
        resources.first?.stop(0)
        resources.second?.shutdownNow()
    }

    /**
     * Reports service availability only to the paired client.
     */
    private fun handleHealth(exchange: HttpExchange)
    {
        exchange.use {
            if (!authorize(exchange))
            {
                sendText(exchange, 401, "Unauthorized")
                return
            }
            if (exchange.requestMethod != "GET")
            {
                exchange.responseHeaders.set("Allow", "GET")
                sendText(exchange, 405, "Method not allowed")
                return
            }
            sendText(exchange, 200, "Ready")
        }
    }

    /**
     * Authenticates before reading audio, rejects oversized bodies before decoding, and scrubs accepted PCM samples.
     */
    private fun handleDictation(exchange: HttpExchange)
    {
        exchange.use {
            if (!authorize(exchange))
            {
                sendText(exchange, 401, "Unauthorized")
                return
            }
            if (exchange.requestMethod != "POST")
            {
                exchange.responseHeaders.set("Allow", "POST")
                sendText(exchange, 405, "Method not allowed")
                return
            }
            if (!hasAudioContentType(exchange))
            {
                sendText(exchange, 415, "Unsupported media type")
                return
            }

            val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength !in 1..RemoteDictationProtocol.MAXIMUM_AUDIO_PAYLOAD_BYTES.toLong())
            {
                sendText(exchange, 413, "Audio payload too large")
                return
            }
            val payload = exchange.requestBody.readNBytes(RemoteDictationProtocol.MAXIMUM_AUDIO_PAYLOAD_BYTES + 1)
            if (payload.size > RemoteDictationProtocol.MAXIMUM_AUDIO_PAYLOAD_BYTES)
            {
                payload.fill(0)
                sendText(exchange, 413, "Audio payload too large")
                return
            }

            val audio = try
            {
                RemoteDictationProtocol.decodeAudio(payload)
            }
            catch (_: RemoteAudioPayloadException)
            {
                payload.fill(0)
                sendText(exchange, 422, "Invalid audio payload")
                return
            }
            finally
            {
                payload.fill(0)
            }

            try
            {
                val polishedTranscript = runBlocking { dictationProcessor.process(audio) }
                sendText(exchange, 200, polishedTranscript)
            }
            catch (_: Exception)
            {
                sendText(exchange, 500, "Dictation failed")
            }
            finally
            {
                audio.samples.fill(0)
            }
        }
    }

    private fun authorize(exchange: HttpExchange): Boolean
    {
        val suppliedAuthorization = exchange.requestHeaders.getFirst("Authorization")
            ?.toByteArray(StandardCharsets.UTF_8)
            ?: return false
        return try
        {
            MessageDigest.isEqual(expectedAuthorization, suppliedAuthorization)
        }
        finally
        {
            suppliedAuthorization.fill(0)
        }
    }

    private fun hasAudioContentType(exchange: HttpExchange): Boolean
    {
        val mediaType = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()
        return mediaType.equals(RemoteDictationProtocol.AUDIO_CONTENT_TYPE, ignoreCase = true)
    }

    private fun sendText(exchange: HttpExchange, statusCode: Int, text: String)
    {
        val body = text.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", RemoteDictationProtocol.TEXT_CONTENT_TYPE)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(statusCode, body.size.toLong())
        try
        {
            exchange.responseBody.use { response -> response.write(body) }
        }
        finally
        {
            body.fill(0)
        }
    }
}
