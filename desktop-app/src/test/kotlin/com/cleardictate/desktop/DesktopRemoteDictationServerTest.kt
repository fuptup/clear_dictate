package com.cleardictate.desktop

import com.cleardictate.inference.remote.RemoteDictationProtocol
import com.cleardictate.inference.remote.RemotePcmAudio
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Proves authentication and exact audio interoperability through a real loopback HTTP server.
 */
class DesktopRemoteDictationServerTest
{
    @Test
    fun `authenticated request receives polished text and server scrubs decoded audio`()
    {
        var receivedSamples = shortArrayOf()
        var ownedSamples: ShortArray? = null
        val server = DesktopRemoteDictationServer(
            bindAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            authorizationToken = "test-token",
            dictationProcessor = DesktopRemoteDictationProcessor { audio ->
                ownedSamples = audio.samples
                receivedSamples = audio.samples.copyOf()
                result("Send the report tomorrow.")
            }
        )

        server.use {
            server.start()
            val response = sendDictation(server, "test-token", shortArrayOf(0, 12_345, -23_456))

            assertEquals(200, response.statusCode())
            assertEquals("Send the report tomorrow.", response.body())
            assertEquals(18, requireNotNull(server.lastSuccessfulTiming.value).totalMilliseconds)
        }

        assertContentEquals(shortArrayOf(0, 12_345, -23_456), receivedSamples)
        assertContentEquals(shortArrayOf(0, 0, 0), ownedSamples)
    }

    @Test
    fun `missing authorization is rejected before audio processing`()
    {
        var processingAttempted = false
        val server = DesktopRemoteDictationServer(
            bindAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            authorizationToken = "test-token",
            dictationProcessor = DesktopRemoteDictationProcessor {
                processingAttempted = true
                result("unexpected")
            }
        )

        server.use {
            server.start()
            val response = sendDictation(server, null, shortArrayOf(1))

            assertEquals(401, response.statusCode())
        }
        assertEquals(false, processingAttempted)
    }

    @Test
    fun `malformed audio is rejected before processing`()
    {
        var processingAttempted = false
        val server = DesktopRemoteDictationServer(
            bindAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            authorizationToken = "test-token",
            dictationProcessor = DesktopRemoteDictationProcessor {
                processingAttempted = true
                result("unexpected")
            }
        )

        server.use {
            server.start()
            val address = requireNotNull(server.localAddress)
            val request = HttpRequest.newBuilder()
                .uri(URI("http://${address.hostString}:${address.port}${RemoteDictationProtocol.DICTATION_PATH}"))
                .header("Authorization", "Bearer test-token")
                .header("Content-Type", RemoteDictationProtocol.AUDIO_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1, 2, 3)))
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(422, response.statusCode())
        }
        assertEquals(false, processingAttempted)
    }

    private fun sendDictation(server: DesktopRemoteDictationServer, token: String?, samples: ShortArray): HttpResponse<String>
    {
        val address = requireNotNull(server.localAddress)
        val audio = RemotePcmAudio(RemoteDictationProtocol.SAMPLE_RATE_HERTZ, samples)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI("http://${address.hostString}:${address.port}${RemoteDictationProtocol.DICTATION_PATH}"))
            .header("Content-Type", RemoteDictationProtocol.AUDIO_CONTENT_TYPE)
            .POST(HttpRequest.BodyPublishers.ofByteArray(RemoteDictationProtocol.encodeAudio(audio)))
        token?.let { requestBuilder.header("Authorization", "Bearer $it") }
        return HttpClient.newHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun result(transcript: String): DesktopDictationResult
    {
        return DesktopDictationResult(
            rawTranscript = transcript,
            polishedTranscript = transcript,
            timing = DesktopDictationTiming(queueMilliseconds = 0, recognitionMilliseconds = 11, rewritingMilliseconds = 7, totalMilliseconds = 18)
        )
    }
}
