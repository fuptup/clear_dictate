package com.cleardictate.inference.service

import com.cleardictate.inference.remote.RemoteDictationProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Locks the Android streaming request headers, incremental audio frames, response handling, and cancellation.
 */
class PcDictationClientTest
{
    @Test
    fun `health distinguishes ready preparation and unavailable responses`()
    {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setHeader(RemoteDictationProtocol.HEALTH_STATE_HEADER, RemoteDictationProtocol.HEALTH_STATE_READY))
        server.enqueue(MockResponse().setResponseCode(503).setHeader(RemoteDictationProtocol.HEALTH_STATE_HEADER, RemoteDictationProtocol.HEALTH_STATE_PREPARING_AI))
        server.enqueue(MockResponse().setResponseCode(401))

        server.use {
            server.start()
            val endpoint = PcDictationEndpoint(server.url("/").toString(), "paired-token")

            assertEquals(PcHealthStatus.READY, runBlocking { PcDictationClient().checkHealth(endpoint) })
            assertEquals(PcHealthStatus.PREPARING_AI, runBlocking { PcDictationClient().checkHealth(endpoint) })
            assertEquals(PcHealthStatus.UNAVAILABLE, runBlocking { PcDictationClient().checkHealth(endpoint) })
        }
    }

    @Test
    fun `audio frames reach the paired PC in one stream and release returns polished text`()
    {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("Send the report tomorrow."))
        val samples = shortArrayOf(0, 12_345, -23_456)

        server.use {
            server.start()
            val endpoint = PcDictationEndpoint(server.url("/").toString(), "paired-token")
            val stream = PcDictationClient().openDictation(endpoint)
            stream.sendPcm16(samples, 2)
            stream.sendPcm16(samples.copyOfRange(2, 3), 1)
            val transcript = stream.finish()
            val request = server.takeRequest()

            assertEquals("Send the report tomorrow.", transcript)
            assertEquals(RemoteDictationProtocol.DICTATION_PATH, request.path)
            assertEquals("Bearer paired-token", request.getHeader("Authorization"))
            assertEquals(RemoteDictationProtocol.STREAM_AUDIO_CONTENT_TYPE, request.getHeader("Content-Type"))
            DataInputStream(request.body.inputStream()).use { input ->
                RemoteDictationProtocol.readAndValidateStreamHeader(input)
                assertContentEquals(shortArrayOf(0, 12_345), RemoteDictationProtocol.readAudioFrame(input))
                assertContentEquals(shortArrayOf(-23_456), RemoteDictationProtocol.readAudioFrame(input))
                assertEquals(null, RemoteDictationProtocol.readAudioFrame(input))
            }
            assertContentEquals(shortArrayOf(0, 12_345, -23_456), samples)
        }
    }

    @Test
    fun `authentication failure does not expose the response body`()
    {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("server detail"))

        server.use {
            server.start()
            val exception = assertFailsWith<PcDictationException> {
                val stream = PcDictationClient().openDictation(PcDictationEndpoint(server.url("/").toString(), "wrong-token"))
                stream.sendPcm16(shortArrayOf(1), 1)
                stream.finish()
            }

            assertEquals(PcDictationFailure.HTTP_FAILURE, exception.failure)
            assertEquals(401, exception.statusCode)
            assertEquals(false, exception.message.orEmpty().contains("server detail"))
        }
    }

    @Test
    fun `cancelling an in flight stream closes its request without a result`()
    {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val samples = shortArrayOf(1, 2, 3)

        server.use {
            server.start()
            val stream = PcDictationClient().openDictation(PcDictationEndpoint(server.url("/").toString(), "paired-token"))
            stream.sendPcm16(samples, samples.size)
            stream.cancel()

            assertContentEquals(shortArrayOf(1, 2, 3), samples)
        }
    }
}
