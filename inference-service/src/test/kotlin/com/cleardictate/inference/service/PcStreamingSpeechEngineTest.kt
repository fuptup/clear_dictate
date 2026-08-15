package com.cleardictate.inference.service

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Proves that phone audio reaches one authenticated PC stream while recording rather than being retained until release.
 */
class PcStreamingSpeechEngineTest
{
    @Test
    fun `captured chunks are sent before release and release forwards the polished result`()
    {
        val events = mutableListOf<String>()
        val transportedChunks = mutableListOf<ShortArray>()
        val completed = mutableListOf<String>()
        val backend = PcLiveAudioRecognitionBackend(
            endpointProvider = PcEndpointProvider { PcDictationEndpoint("http://192.168.1.10:8765", "paired-token") },
            transport = fakeTransport(
                onOpen = { events += "open" },
                onSend = { samples ->
                    events += "send"
                    transportedChunks += samples.copyOf()
                },
                onFinish = {
                    events += "finish"
                    "Send the report tomorrow."
                }
            )
        )

        backend.startSession(listener(completed))
        backend.acceptPcm16(shortArrayOf(1, 2, 99), 2, 16_000)

        assertEquals(listOf("open", "send"), events)
        assertContentEquals(shortArrayOf(1, 2), transportedChunks.single())
        assertEquals(emptyList(), completed)

        backend.acceptPcm16(shortArrayOf(3, 4), 2, 16_000)
        backend.stopAndFlush()

        assertEquals(listOf("open", "send", "send", "finish"), events)
        assertEquals(listOf("Send the report tomorrow."), completed)
    }

    @Test
    fun `cancellation terminates the already opened PC stream without finishing it`()
    {
        val events = mutableListOf<String>()
        val backend = PcLiveAudioRecognitionBackend(
            endpointProvider = PcEndpointProvider { PcDictationEndpoint("http://192.168.1.10:8765", "token") },
            transport = fakeTransport(
                onOpen = { events += "open" },
                onSend = { events += "send" },
                onFinish = {
                    events += "finish"
                    "unexpected"
                },
                onCancel = { events += "cancel" }
            )
        )

        backend.startSession(listener(mutableListOf()))
        backend.acceptPcm16(shortArrayOf(1, 2), 2, 16_000)
        backend.cancelAndFlush()

        assertEquals(listOf("open", "send", "cancel"), events)
    }

    @Test
    fun `release without captured audio cancels the opened request`()
    {
        val events = mutableListOf<String>()
        var failureCount = 0
        val backend = PcLiveAudioRecognitionBackend(
            endpointProvider = PcEndpointProvider { PcDictationEndpoint("http://192.168.1.10:8765", "token") },
            transport = fakeTransport(
                onOpen = { events += "open" },
                onSend = { events += "send" },
                onFinish = {
                    events += "finish"
                    "unexpected"
                },
                onCancel = { events += "cancel" }
            )
        )
        val listener = object : StreamingSpeechEventListener
        {
            override fun onPartial(lineIdentifier: Long, text: String) = Unit
            override fun onCompleted(lineIdentifier: Long, text: String) = Unit
            override fun onFailure()
            {
                failureCount += 1
            }
        }

        backend.startSession(listener)
        backend.stopAndFlush()

        assertEquals(listOf("open", "cancel"), events)
        assertEquals(1, failureCount)
    }

    private fun fakeTransport(
        onOpen: () -> Unit,
        onSend: (ShortArray) -> Unit,
        onFinish: () -> String,
        onCancel: () -> Unit = {}
    ): PcDictationTransport
    {
        return object : PcDictationTransport
        {
            override suspend fun checkHealth(endpoint: PcDictationEndpoint) = PcHealthStatus.READY

            override fun openDictation(endpoint: PcDictationEndpoint): PcDictationStream
            {
                onOpen()
                return object : PcDictationStream
                {
                    override fun sendPcm16(samples: ShortArray, sampleCount: Int)
                    {
                        onSend(samples.copyOf(sampleCount))
                    }

                    override fun finish() = onFinish()

                    override fun cancel() = onCancel()
                }
            }
        }
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
