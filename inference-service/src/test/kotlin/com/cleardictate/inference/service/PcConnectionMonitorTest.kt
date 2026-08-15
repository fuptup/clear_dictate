package com.cleardictate.inference.service

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
}
