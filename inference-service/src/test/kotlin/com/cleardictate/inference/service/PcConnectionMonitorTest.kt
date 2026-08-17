package com.cleardictate.inference.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Verifies that health results drive a recoverable PC connection state independently of model readiness.
 */
class PcConnectionMonitorTest
{
    @Test
    fun `polling reports connection loss and recovery`()
    {
        runBlocking {
            val endpoint = PcDictationEndpoint("http://127.0.0.1:8765", "test-token")
            val transport = ControllableHealthTransport(PcHealthStatus.READY)
            val observedStates = mutableListOf<PcConnectionState>()
            val monitor = PcConnectionMonitor(
                endpointProvider = PcEndpointProvider { endpoint },
                transport = transport,
                pollIntervalMilliseconds = 30_000L,
                stateChanged = observedStates::add
            )

            try
            {
                monitor.pollOnce()
                transport.healthStatus = PcHealthStatus.UNAVAILABLE
                monitor.pollOnce()
                transport.healthStatus = PcHealthStatus.PREPARING_AI
                monitor.pollOnce()
                transport.healthStatus = PcHealthStatus.READY
                monitor.pollOnce()

                assertEquals(
                    listOf(PcConnectionState.CONNECTED, PcConnectionState.DISCONNECTED, PcConnectionState.PREPARING_AI, PcConnectionState.CONNECTED),
                    observedStates
                )
            }
            finally
            {
                monitor.close()
            }
        }
    }

    @Test
    fun `missing pairing reports disconnected without a network request`()
    {
        runBlocking {
            val transport = ControllableHealthTransport(PcHealthStatus.READY)
            val observedStates = mutableListOf<PcConnectionState>()
            val monitor = PcConnectionMonitor(
                endpointProvider = PcEndpointProvider { null },
                transport = transport,
                pollIntervalMilliseconds = 30_000L,
                stateChanged = observedStates::add
            )

            try
            {
                monitor.pollOnce()

                assertEquals(listOf(PcConnectionState.DISCONNECTED), observedStates)
                assertEquals(0, transport.healthCheckCount)
            }
            finally
            {
                monitor.close()
            }
        }
    }

    @Test
    fun `repeated refresh requests share one pending health check`()
    {
        runBlocking {
            val endpoint = PcDictationEndpoint("http://127.0.0.1:8765", "test-token")
            val transport = BlockingHealthTransport()
            val monitor = PcConnectionMonitor(
                endpointProvider = PcEndpointProvider { endpoint },
                transport = transport,
                pollIntervalMilliseconds = 30_000L,
                stateChanged = {}
            )

            try
            {
                val firstRefresh = monitor.refreshNow()
                transport.firstCheckStarted.await()
                val repeatedRefresh = monitor.refreshNow()
                transport.allowCheckToComplete.complete(Unit)
                listOf(firstRefresh, repeatedRefresh).joinAll()

                assertEquals(1, transport.healthCheckCount)
            }
            finally
            {
                monitor.close()
            }
        }
    }

    @Test
    fun `pairing changed during a health request is checked before publishing`()
    {
        runBlocking {
            val firstEndpoint = PcDictationEndpoint("http://127.0.0.1:8765", "first-token")
            val replacementEndpoint = PcDictationEndpoint("http://100.64.0.2:8765", "replacement-token")
            var configuredEndpoint = firstEndpoint
            val checkedEndpoints = mutableListOf<PcDictationEndpoint>()
            val observedStates = mutableListOf<PcConnectionState>()
            val transport = object : PcDictationTransport
            {
                override suspend fun checkHealth(endpoint: PcDictationEndpoint): PcHealthStatus
                {
                    checkedEndpoints += endpoint
                    if (endpoint == firstEndpoint)
                    {
                        configuredEndpoint = replacementEndpoint
                        return PcHealthStatus.UNAVAILABLE
                    }
                    return PcHealthStatus.READY
                }

                override fun openDictation(endpoint: PcDictationEndpoint): PcDictationStream
                {
                    error("Dictation is outside this monitor test.")
                }
            }
            val monitor = PcConnectionMonitor(
                endpointProvider = PcEndpointProvider { configuredEndpoint },
                transport = transport,
                pollIntervalMilliseconds = 30_000L,
                stateChanged = observedStates::add
            )

            try
            {
                monitor.pollOnce()

                assertEquals(listOf(firstEndpoint, replacementEndpoint), checkedEndpoints)
                assertEquals(listOf(PcConnectionState.CONNECTED), observedStates)
            }
            finally
            {
                monitor.close()
            }
        }
    }

    /**
     * Exposes deterministic reachability changes while rejecting unrelated dictation calls.
     */
    private class ControllableHealthTransport(var healthStatus: PcHealthStatus) : PcDictationTransport
    {
        var healthCheckCount = 0
            private set

        override suspend fun checkHealth(endpoint: PcDictationEndpoint): PcHealthStatus
        {
            healthCheckCount += 1
            return healthStatus
        }

        override fun openDictation(endpoint: PcDictationEndpoint): PcDictationStream
        {
            error("Dictation is outside this monitor test.")
        }
    }

    /**
     * Holds the first health request open so redundant refresh work is observable without timing assumptions.
     */
    private class BlockingHealthTransport : PcDictationTransport
    {
        val firstCheckStarted = CompletableDeferred<Unit>()
        val allowCheckToComplete = CompletableDeferred<Unit>()
        var healthCheckCount = 0
            private set

        override suspend fun checkHealth(endpoint: PcDictationEndpoint): PcHealthStatus
        {
            healthCheckCount += 1
            firstCheckStarted.complete(Unit)
            allowCheckToComplete.await()
            return PcHealthStatus.READY
        }

        override fun openDictation(endpoint: PcDictationEndpoint): PcDictationStream
        {
            error("Dictation is outside this monitor test.")
        }
    }
}
