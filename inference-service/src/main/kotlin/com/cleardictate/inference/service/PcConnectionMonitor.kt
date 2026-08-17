package com.cleardictate.inference.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Distinguishes an in-flight reachability check from a confirmed PC connection or outage.
 */
enum class PcConnectionState
{
    CHECKING,
    PREPARING_AI,
    CONNECTED,
    DISCONNECTED
}

/**
 * Owns the single periodic health check for the paired PC and publishes only connection-state changes.
 */
internal class PcConnectionMonitor(
    private val endpointProvider: PcEndpointProvider,
    private val transport: PcDictationTransport,
    private val pollIntervalMilliseconds: Long,
    private val stateChanged: (PcConnectionState) -> Unit
) : AutoCloseable
{
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val refreshLock = Any()
    private var pollingJob: Job? = null
    private var refreshJob: Job? = null

    @Volatile
    var currentState = PcConnectionState.CHECKING
        private set

    init
    {
        require(pollIntervalMilliseconds > 0L) { "The PC connection poll interval must be positive." }
    }

    /**
     * Starts with an immediate check, then waits the configured interval after each completed check.
     */
    fun start()
    {
        if (pollingJob != null)
        {
            return
        }

        pollingJob = scope.launch {
            while (isActive)
            {
                pollOnce()
                delay(pollIntervalMilliseconds)
            }
        }
    }

    /**
     * Marks a newly configured endpoint as being checked and queues a prompt health request without overlapping an in-flight request.
     */
    fun refreshNow(): Job
    {
        publish(PcConnectionState.CHECKING)
        return synchronized(refreshLock)
        {
            refreshJob?.takeIf { job -> job.isActive } ?: scope.launch(start = CoroutineStart.LAZY) { pollOnce() }.also { job ->
                refreshJob = job
                job.start()
            }
        }
    }

    /**
     * Checks the endpoint captured at the start of the request. A result is discarded if pairing changes while the request is in flight.
     */
    internal suspend fun pollOnce()
    {
        checkMutex.withLock {
            while (true)
            {
                val endpoint = endpointProvider.load()
                if (endpoint == null)
                {
                    publish(PcConnectionState.DISCONNECTED)
                    return
                }

                val healthStatus = runCatching { transport.checkHealth(endpoint) }.getOrDefault(PcHealthStatus.UNAVAILABLE)
                if (endpointProvider.load() != endpoint)
                {
                    continue
                }

                publish(
                    when (healthStatus)
                    {
                        PcHealthStatus.READY -> PcConnectionState.CONNECTED
                        PcHealthStatus.PREPARING_AI -> PcConnectionState.PREPARING_AI
                        PcHealthStatus.UNAVAILABLE -> PcConnectionState.DISCONNECTED
                    }
                )
                return
            }
        }
    }

    /**
     * Suppresses identical callbacks so periodic checks do not cause unnecessary UI recomposition or Binder traffic.
     */
    private fun publish(nextState: PcConnectionState)
    {
        if (currentState == nextState)
        {
            return
        }

        currentState = nextState
        stateChanged(nextState)
    }

    override fun close()
    {
        scope.cancel()
        pollingJob = null
        synchronized(refreshLock) { refreshJob = null }
    }
}
