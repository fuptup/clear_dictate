package com.cleardictate.inference.service

import com.cleardictate.inference.remote.RemotePcmAudio
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
            val transport = ControllableHealthTransport(reachable = true)
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
                transport.reachable = false
                monitor.pollOnce()
                transport.reachable = true
                monitor.pollOnce()

                assertEquals(
                    listOf(PcConnectionState.CONNECTED, PcConnectionState.DISCONNECTED, PcConnectionState.CONNECTED),
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
            val transport = ControllableHealthTransport(reachable = true)
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
    private class ControllableHealthTransport(var reachable: Boolean) : PcDictationTransport
    {
        var healthCheckCount = 0
            private set

        override suspend fun checkHealth(endpoint: PcDictationEndpoint): Boolean
        {
            healthCheckCount += 1
            return reachable
        }

        override suspend fun dictate(endpoint: PcDictationEndpoint, audio: RemotePcmAudio): String
        {
            error("Dictation is outside this monitor test.")
        }
    }
}
