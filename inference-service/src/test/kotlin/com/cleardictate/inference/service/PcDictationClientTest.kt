package com.cleardictate.inference.service

import com.cleardictate.inference.remote.RemoteDictationProtocol
import com.cleardictate.inference.remote.RemotePcmAudio
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Locks the Android request headers, shared audio bytes, response handling, and caller-audio scrubbing.
 */
class PcDictationClientTest
{
    @Test
    fun `completed audio reaches the paired PC and returns polished text`()
    {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("Send the report tomorrow."))
        val samples = shortArrayOf(0, 12_345, -23_456)

        server.use {
            server.start()
            val endpoint = PcDictationEndpoint(server.url("/").toString(), "paired-token")
            val transcript = runBlocking {
                PcDictationClient().dictate(endpoint, RemotePcmAudio(16_000, samples))
            }
            val request = server.takeRequest()

            assertEquals("Send the report tomorrow.", transcript)
            assertEquals(RemoteDictationProtocol.DICTATION_PATH, request.path)
            assertEquals("Bearer paired-token", request.getHeader("Authorization"))
            assertEquals(RemoteDictationProtocol.AUDIO_CONTENT_TYPE, request.getHeader("Content-Type"))
            assertContentEquals(
                shortArrayOf(0, 12_345, -23_456),
                RemoteDictationProtocol.decodeAudio(request.body.readByteArray()).samples
            )
            assertContentEquals(shortArrayOf(0, 0, 0), samples)
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
                runBlocking {
                    PcDictationClient().dictate(
                        PcDictationEndpoint(server.url("/").toString(), "wrong-token"),
                        RemotePcmAudio(16_000, shortArrayOf(1))
                    )
                }
            }

            assertEquals(PcDictationFailure.HTTP_FAILURE, exception.failure)
            assertEquals(401, exception.statusCode)
            assertEquals(false, exception.message.orEmpty().contains("server detail"))
        }
    }

    @Test
    fun `cancelling an in flight request cancels transport ownership and scrubs audio`()
    {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val samples = shortArrayOf(1, 2, 3)

        server.use {
            server.start()
            runBlocking {
                val request = launch {
                    PcDictationClient().dictate(
                        PcDictationEndpoint(server.url("/").toString(), "paired-token"),
                        RemotePcmAudio(16_000, samples)
                    )
                }
                yield()
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                request.cancelAndJoin()
            }

            assertContentEquals(shortArrayOf(0, 0, 0), samples)
        }
    }
}
