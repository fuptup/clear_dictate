package com.cleardictate.desktop

import com.cleardictate.inference.remote.RemoteAudioPayloadException
import com.cleardictate.inference.remote.RemoteAudioPayloadFailure
import com.cleardictate.inference.remote.RemoteDictationProtocol
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens one stateful model session before the server starts reading phone audio.
 */
fun interface DesktopRemoteDictationProcessor
{
    suspend fun open(): DesktopRemoteDictationSession?
}

/**
 * Describes the supervised endpoint independently from AI readiness so temporary network failures remain recoverable.
 */
enum class DesktopPhoneServerState
{
    STARTING,
    PREPARING_AI,
    READY,
    RECOVERING,
    STOPPED
}

/**
 * Hosts the first authenticated phone-to-PC transport boundary.
 * The service advances bounded authenticated audio streams and never returns raw transcripts or internal failures.
 */
class DesktopRemoteDictationServer(
    private val bindAddress: InetSocketAddress,
    authorizationToken: String,
    private val dictationProcessor: DesktopRemoteDictationProcessor,
    initiallyReady: Boolean = true
) : AutoCloseable
{
    private val expectedAuthorization = "Bearer $authorizationToken".toByteArray(StandardCharsets.UTF_8)
    private val ownershipLock = Any()
    private val dictationReady = AtomicBoolean(initiallyReady)
    private val mutableLastSuccessfulTiming = MutableStateFlow<DesktopDictationTiming?>(null)
    private val mutableState = MutableStateFlow(DesktopPhoneServerState.STOPPED)
    private var supervisor: ScheduledExecutorService? = null
    private var activeServer: HttpServer? = null
    private var activeExecutor: ExecutorService? = null
    private var consecutiveProbeFailures = 0
    private var nextProbeNanoseconds = 0L
    private var closed = false

    init
    {
        require(authorizationToken.isNotBlank()) { "Phone authorization requires a non-empty token." }
    }

    val localAddress: InetSocketAddress?
        get() = synchronized(ownershipLock) { activeServer?.address }

    val state: StateFlow<DesktopPhoneServerState> = mutableState.asStateFlow()

    /**
     * Exposes only timing for the most recent successful phone request; it deliberately retains neither audio nor text.
     */
    val lastSuccessfulTiming: StateFlow<DesktopDictationTiming?> = mutableLastSuccessfulTiming.asStateFlow()

    /**
     * Starts one daemon supervisor that retries a failed bind and repairs an endpoint that no longer answers local liveness checks.
     */
    fun start()
    {
        val startedSupervisor = synchronized(ownershipLock)
        {
            check(!closed) { "The phone dictation server is closed." }
            check(supervisor == null) { "The phone dictation server supervisor is already running." }
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "cleardictate-phone-supervisor").apply { isDaemon = true }
            }.also { createdSupervisor ->
                supervisor = createdSupervisor
                mutableState.value = DesktopPhoneServerState.STARTING
            }
        }

        reconcileEndpoint()
        startedSupervisor.scheduleWithFixedDelay(::reconcileEndpointSafely, SUPERVISION_INTERVAL_MILLISECONDS, SUPERVISION_INTERVAL_MILLISECONDS, TimeUnit.MILLISECONDS)
    }

    /**
     * Changes what authenticated clients may do without disturbing the independently supervised listening socket.
     */
    fun setDictationReady(ready: Boolean)
    {
        dictationReady.set(ready)
        synchronized(ownershipLock)
        {
            if (activeServer != null)
            {
                mutableState.value = if (ready) DesktopPhoneServerState.READY else DesktopPhoneServerState.PREPARING_AI
            }
        }
    }

    override fun close()
    {
        val resources = synchronized(ownershipLock)
        {
            if (closed)
            {
                return
            }
            closed = true
            mutableState.value = DesktopPhoneServerState.STOPPED
            Triple(
                supervisor.also { supervisor = null },
                activeServer.also { activeServer = null },
                activeExecutor.also { activeExecutor = null }
            )
        }
        resources.first?.shutdownNow()
        resources.second?.stop(0)
        resources.third?.shutdownNow()
        expectedAuthorization.fill(0)
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
            if (dictationReady.get())
            {
                exchange.responseHeaders.set(RemoteDictationProtocol.HEALTH_STATE_HEADER, RemoteDictationProtocol.HEALTH_STATE_READY)
                sendText(exchange, 200, "Ready")
            }
            else
            {
                exchange.responseHeaders.set(RemoteDictationProtocol.HEALTH_STATE_HEADER, RemoteDictationProtocol.HEALTH_STATE_PREPARING_AI)
                sendText(exchange, 503, "Preparing AI")
            }
        }
    }

    /**
     * Authenticates before reading audio, then advances ASR for every framed PCM fragment before the explicit release marker arrives.
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
            if (!dictationReady.get())
            {
                sendText(exchange, 503, "Preparing AI")
                return
            }
            if (!hasAudioContentType(exchange))
            {
                sendText(exchange, 415, "Unsupported media type")
                return
            }

            val session = try
            {
                runBlocking { dictationProcessor.open() }
            }
            catch (_: Exception)
            {
                sendText(exchange, 500, "Dictation failed")
                return
            }
            if (session == null)
            {
                sendText(exchange, 409, "Dictation is busy")
                return
            }

            var finished = false
            try
            {
                var receivedSampleCount = 0
                DataInputStream(BufferedInputStream(exchange.requestBody)).use { input ->
                    RemoteDictationProtocol.readAndValidateStreamHeader(input)
                    while (true)
                    {
                        val samples = RemoteDictationProtocol.readAudioFrame(input, RemoteDictationProtocol.MAXIMUM_SAMPLE_COUNT - receivedSampleCount) ?: break
                        try
                        {
                            runBlocking { session.acceptPcm16(samples) }
                            receivedSampleCount += samples.size
                        }
                        finally
                        {
                            samples.fill(0)
                        }
                    }
                }
                if (receivedSampleCount == 0)
                {
                    throw RemoteAudioPayloadException(RemoteAudioPayloadFailure.INVALID_SAMPLE_COUNT)
                }

                val result = runBlocking { session.finish() }
                finished = true
                mutableLastSuccessfulTiming.value = result.timing
                sendText(exchange, 200, result.polishedTranscript)
            }
            catch (_: RemoteAudioPayloadException)
            {
                sendText(exchange, 422, "Invalid audio payload")
            }
            catch (_: EOFException)
            {
                sendText(exchange, 422, "Invalid audio payload")
            }
            catch (_: Exception)
            {
                sendText(exchange, 500, "Dictation failed")
            }
            finally
            {
                if (!finished)
                {
                    runCatching { runBlocking { session.cancel() } }
                }
            }
        }
    }

    /**
     * Contains every scheduled probe failure so one transient network or HTTP error cannot kill future recovery attempts.
     */
    private fun reconcileEndpointSafely()
    {
        runCatching(::reconcileEndpoint)
    }

    /**
     * Binds when absent and replaces an endpoint only after two consecutive failed local probes, avoiding restarts during one transient timeout.
     */
    private fun reconcileEndpoint()
    {
        val server = synchronized(ownershipLock)
        {
            if (closed)
            {
                return
            }
            activeServer
        }

        if (server == null)
        {
            tryBindEndpoint()
            return
        }

        val probeIsDue = synchronized(ownershipLock)
        {
            if (activeServer !== server || closed)
            {
                return
            }
            val currentNanoseconds = System.nanoTime()
            if (currentNanoseconds < nextProbeNanoseconds)
            {
                false
            }
            else
            {
                nextProbeNanoseconds = currentNanoseconds + TimeUnit.MILLISECONDS.toNanos(PROBE_INTERVAL_MILLISECONDS)
                true
            }
        }
        if (!probeIsDue)
        {
            return
        }

        if (probeEndpoint(server.address))
        {
            synchronized(ownershipLock)
            {
                if (activeServer === server)
                {
                    consecutiveProbeFailures = 0
                    mutableState.value = readyState()
                }
            }
            return
        }

        val resourcesToReplace = synchronized(ownershipLock)
        {
            if (activeServer !== server || closed)
            {
                return
            }
            consecutiveProbeFailures += 1
            if (consecutiveProbeFailures < REQUIRED_PROBE_FAILURES)
            {
                return
            }
            consecutiveProbeFailures = 0
            mutableState.value = DesktopPhoneServerState.RECOVERING
            Pair(activeServer.also { activeServer = null }, activeExecutor.also { activeExecutor = null })
        }
        resourcesToReplace.first?.stop(0)
        resourcesToReplace.second?.shutdownNow()
        tryBindEndpoint()
    }

    /**
     * Attempts one complete bind while retaining the supervisor after failure so the next scheduled pass can retry.
     */
    private fun tryBindEndpoint()
    {
        synchronized(ownershipLock)
        {
            if (closed || activeServer != null)
            {
                return
            }

            val requestExecutor = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "cleardictate-phone-request").apply { isDaemon = true }
            }
            var createdServer: HttpServer? = null
            val server = try
            {
                HttpServer.create(bindAddress, 0).also { createdServer = it }.apply {
                    executor = requestExecutor
                    createContext(RemoteDictationProtocol.HEALTH_PATH, ::handleHealth)
                    createContext(RemoteDictationProtocol.DICTATION_PATH, ::handleDictation)
                    start()
                }
            }
            catch (_: Exception)
            {
                createdServer?.stop(0)
                requestExecutor.shutdownNow()
                mutableState.value = DesktopPhoneServerState.RECOVERING
                return
            }

            activeExecutor = requestExecutor
            activeServer = server
            consecutiveProbeFailures = 0
            nextProbeNanoseconds = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_INTERVAL_MILLISECONDS)
            mutableState.value = readyState()
        }
    }

    /**
     * Uses the authenticated boundary's deliberate 401 response as a content-free local liveness signal.
     */
    private fun probeEndpoint(address: InetSocketAddress): Boolean
    {
        val probeHost = if (address.address.isAnyLocalAddress) "127.0.0.1" else address.address.hostAddress
        val connection = URI("http", null, probeHost, address.port, RemoteDictationProtocol.HEALTH_PATH, null, null).toURL().openConnection() as HttpURLConnection
        return try
        {
            connection.connectTimeout = PROBE_TIMEOUT_MILLISECONDS
            connection.readTimeout = PROBE_TIMEOUT_MILLISECONDS
            connection.requestMethod = "GET"
            connection.responseCode == 401
        }
        catch (_: Exception)
        {
            false
        }
        finally
        {
            connection.disconnect()
        }
    }

    private fun readyState(): DesktopPhoneServerState
    {
        return if (dictationReady.get()) DesktopPhoneServerState.READY else DesktopPhoneServerState.PREPARING_AI
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
        return mediaType.equals(RemoteDictationProtocol.STREAM_AUDIO_CONTENT_TYPE, ignoreCase = true)
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

    private companion object
    {
        const val SUPERVISION_INTERVAL_MILLISECONDS = 1_000L
        const val PROBE_INTERVAL_MILLISECONDS = 5_000L
        const val PROBE_TIMEOUT_MILLISECONDS = 1_000
        const val REQUIRED_PROBE_FAILURES = 2
    }
}
