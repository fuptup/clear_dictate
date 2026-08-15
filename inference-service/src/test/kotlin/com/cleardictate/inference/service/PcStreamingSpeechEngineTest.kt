package com.cleardictate.inference.service

import com.cleardictate.inference.remote.RemotePcmAudio
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Proves completed-audio accumulation, remote delivery, result forwarding, and terminal scrubbing.
 */
class PcStreamingSpeechEngineTest
{
    @Test
    fun `release uploads all captured chunks and forwards the polished result`()
    {
        val endpoint = PcDictationEndpoint("http://192.168.1.10:8765", "paired-token")
        var transportedSamples = shortArrayOf()
        var transportOwnedSamples: ShortArray? = null
        val completed = mutableListOf<String>()
        val backend = PcCompletedAudioRecognitionBackend(
            endpointProvider = PcEndpointProvider { endpoint },
            transport = object : PcDictationTransport
            {
                override suspend fun checkHealth(endpoint: PcDictationEndpoint) = PcHealthStatus.READY

                override suspend fun dictate(endpoint: PcDictationEndpoint, audio: RemotePcmAudio): String
                {
                    transportOwnedSamples = audio.samples
                    transportedSamples = audio.samples.copyOf()
                    return "Send the report tomorrow."
                }
            }
        )
        backend.startSession(listener(completed))

        backend.acceptPcm16(shortArrayOf(1, 2, 99), 2, 16_000)
        backend.acceptPcm16(shortArrayOf(3, 4), 2, 16_000)
        backend.stopAndFlush()

        assertContentEquals(shortArrayOf(1, 2, 3, 4), transportedSamples)
        assertContentEquals(shortArrayOf(0, 0, 0, 0), transportOwnedSamples)
        assertEquals(listOf("Send the report tomorrow."), completed)
    }

    @Test
    fun `cancellation discards captured chunks without contacting the PC`()
    {
        var transportCalled = false
        val backend = PcCompletedAudioRecognitionBackend(
            endpointProvider = PcEndpointProvider { PcDictationEndpoint("http://192.168.1.10:8765", "token") },
            transport = object : PcDictationTransport
            {
                override suspend fun checkHealth(endpoint: PcDictationEndpoint) = PcHealthStatus.READY

                override suspend fun dictate(endpoint: PcDictationEndpoint, audio: RemotePcmAudio): String
                {
                    transportCalled = true
                    return "unexpected"
                }
            }
        )
        backend.startSession(listener(mutableListOf()))
        backend.acceptPcm16(shortArrayOf(1, 2), 2, 16_000)

        backend.cancelAndFlush()

        assertEquals(false, transportCalled)
    }

    private fun listener(completed: MutableList<String>): StreamingSpeechEventListener
    {
        return object : StreamingSpeechEventListener
        {
            override fun onPartial(lineIdentifier: Long, text: String) = Unit
            override fun onCompleted(lineIdentifier: Long, text: String) { completed += text }
            override fun onFailure() = Unit
        }
    }
}
