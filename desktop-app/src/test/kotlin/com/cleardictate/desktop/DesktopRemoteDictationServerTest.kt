package com.cleardictate.desktop

import com.cleardictate.domain.TranscriptFallbackReason
import com.cleardictate.inference.remote.RemoteDictationProtocol
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves authentication and live frame delivery through a real chunked loopback HTTP connection.
 */
class DesktopRemoteDictationServerTest
{
    @Test
    fun temporaryBindConflictRecoversWithoutRestartingTheApplication()
    {
        val loopback = InetAddress.getLoopbackAddress()
        val blocker = ServerSocket(0, 1, loopback)
        val server = DesktopRemoteDictationServer(
            bindAddress = InetSocketAddress(loopback, blocker.localPort),
            authorizationToken = "test-token",
            dictationProcessor = processor()
        )

        try
        {
            server.start()
            assertEquals(DesktopPhoneServerState.RECOVERING, server.state.value)
            blocker.close()

            assertTrue(awaitCondition { server.localAddress != null }, "The server did not retry after the temporary port conflict cleared.")
            assertEquals(DesktopPhoneServerState.READY, server.state.value)
        }
        finally
        {
            blocker.close()
            server.close()
        }
    }

    @Test
    fun healthReportsModelPreparationWhileEndpointRemainsReachable()
    {
        var processingAttempted = false
        val server = DesktopRemoteDictationServer(
            bindAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            authorizationToken = "test-token",
            initiallyReady = false,
            dictationProcessor = DesktopRemoteDictationProcessor {
                processingAttempted = true
                session()
            }
        )

        server.use {
            server.start()
            val preparingHealth = sendHealth(server, "test-token")
            val preparingDictation = sendCompletedDictation(server, "test-token", shortArrayOf(1))
            server.setDictationReady(true)
            val readyHealth = sendHealth(server, "test-token")

            assertEquals(503, preparingHealth.statusCode())
            assertEquals(503, preparingDictation.statusCode())
            assertEquals(false, processingAttempted)
            assertEquals(200, readyHealth.statusCode())
            assertEquals(RemoteDictationProtocol.HEALTH_STATE_READY, readyHealth.headers().firstValue(RemoteDictationProtocol.HEALTH_STATE_HEADER).orElse(null))
        }
    }

    @Test
    fun firstFrameReachesAsrBeforePhoneSendsRelease()
    {
        val firstFrameAccepted = CountDownLatch(1)
        val receivedFrames = mutableListOf<ShortArray>()
        var finishCount = 0
        val server = DesktopRemoteDictationServer(
            bindAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            authorizationToken = "test-token",
            dictationProcessor = processor(
                onAccept = { samples ->
                    receivedFrames += samples.copyOf()
                    firstFrameAccepted.countDown()
                },
                onFinish = {
                    finishCount += 1
                    result("Send the report tomorrow.")
                }
            )
        )

        server.use {
            server.start()
            val connection = openStreamingConnection(server, "test-token")
            val output = DataOutputStream(connection.outputStream)
            RemoteDictationProtocol.writeStreamHeader(output)
            RemoteDictationProtocol.writeAudioFrame(output, shortArrayOf(0, 12_345), 2)
            output.flush()

            assertTrue(firstFrameAccepted.await(2, TimeUnit.SECONDS), "The server buffered audio until release.")
            assertEquals(0, finishCount)

            RemoteDictationProtocol.writeAudioFrame(output, shortArrayOf(-23_456), 1)
            RemoteDictationProtocol.writeStreamFinish(output)
            output.close()
            assertEquals(200, connection.responseCode)
            assertEquals("Send the report tomorrow.", connection.inputStream.bufferedReader().readText())
            connection.disconnect()
            assertEquals(1, finishCount)
            assertContentEquals(shortArrayOf(0, 12_345), receivedFrames[0])
            assertContentEquals(shortArrayOf(-23_456), receivedFrames[1])
            assertEquals(18, server.lastSuccessfulTiming.value?.totalMilliseconds)
        }
    }

    @Test
    fun missingAuthorizationIsRejectedBeforeOpeningAsr()
    {
        var opened = false
        val server = DesktopRemoteDictationServer(
            bindAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            authorizationToken = "test-token",
            dictationProcessor = DesktopRemoteDictationProcessor {
                opened = true
                session()
            }
        )

        server.use {
            server.start()
            val response = sendCompletedDictation(server, null, shortArrayOf(1))
            assertEquals(401, response.statusCode())
        }
        assertEquals(false, opened)
    }

    @Test
    fun malformedStreamCancelsTheOpenedAsrSession()
    {
        var cancelCount = 0
        val server = DesktopRemoteDictationServer(
            bindAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            authorizationToken = "test-token",
            dictationProcessor = processor(onCancel = { cancelCount += 1 })
        )

        server.use {
            server.start()
            val address = requireNotNull(server.localAddress)
            val request = HttpRequest.newBuilder()
                .uri(URI("http://" + address.hostString + ":" + address.port + RemoteDictationProtocol.DICTATION_PATH))
                .header("Authorization", "Bearer test-token")
                .header("Content-Type", RemoteDictationProtocol.STREAM_AUDIO_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1, 2, 3)))
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(422, response.statusCode())
            assertTrue(awaitCondition { cancelCount == 1 }, "The malformed stream was not cancelled.")
        }
    }

    private fun processor(
        onAccept: suspend (ShortArray) -> Unit = {},
        onFinish: suspend () -> DesktopDictationResult = { result("unused") },
        onCancel: suspend () -> Unit = {}
    ): DesktopRemoteDictationProcessor
    {
        return DesktopRemoteDictationProcessor { session(onAccept, onFinish, onCancel) }
    }

    private fun session(
        onAccept: suspend (ShortArray) -> Unit = {},
        onFinish: suspend () -> DesktopDictationResult = { result("unused") },
        onCancel: suspend () -> Unit = {}
    ): DesktopRemoteDictationSession
    {
        return object : DesktopRemoteDictationSession
        {
            override suspend fun acceptPcm16(samples: ShortArray) = onAccept(samples)
            override suspend fun finish(): DesktopDictationResult = onFinish()
            override suspend fun cancel() = onCancel()
        }
    }

    private fun openStreamingConnection(server: DesktopRemoteDictationServer, token: String?): HttpURLConnection
    {
        val address = requireNotNull(server.localAddress)
        val connection = URI("http://" + address.hostString + ":" + address.port + RemoteDictationProtocol.DICTATION_PATH).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setChunkedStreamingMode(1_024)
        connection.setRequestProperty("Content-Type", RemoteDictationProtocol.STREAM_AUDIO_CONTENT_TYPE)
        token?.let { connection.setRequestProperty("Authorization", "Bearer " + it) }
        return connection
    }

    private fun sendCompletedDictation(server: DesktopRemoteDictationServer, token: String?, samples: ShortArray): HttpResponse<String>
    {
        val payload = java.io.ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                RemoteDictationProtocol.writeStreamHeader(output)
                RemoteDictationProtocol.writeAudioFrame(output, samples, samples.size)
                RemoteDictationProtocol.writeStreamFinish(output)
            }
            bytes.toByteArray()
        }
        val address = requireNotNull(server.localAddress)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI("http://" + address.hostString + ":" + address.port + RemoteDictationProtocol.DICTATION_PATH))
            .header("Content-Type", RemoteDictationProtocol.STREAM_AUDIO_CONTENT_TYPE)
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
        token?.let { requestBuilder.header("Authorization", "Bearer " + it) }
        return HttpClient.newHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun sendHealth(server: DesktopRemoteDictationServer, token: String?): HttpResponse<String>
    {
        val address = requireNotNull(server.localAddress)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI("http://" + address.hostString + ":" + address.port + RemoteDictationProtocol.HEALTH_PATH))
            .GET()
        token?.let { requestBuilder.header("Authorization", "Bearer " + it) }
        return HttpClient.newHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun result(transcript: String): DesktopDictationResult
    {
        return DesktopDictationResult(
            rawTranscript = transcript,
            polishedTranscript = transcript,
            timing = DesktopDictationTiming(queueMilliseconds = 0, recognitionMilliseconds = 11, rewritingMilliseconds = 7, totalMilliseconds = 18),
            polishingOutcome = DesktopPolishingOutcome(false, TranscriptFallbackReason.NONE)
        )
    }

    private fun awaitCondition(timeoutMilliseconds: Long = 3_000L, condition: () -> Boolean): Boolean
    {
        val deadline = System.nanoTime() + timeoutMilliseconds * 1_000_000L
        while (System.nanoTime() < deadline)
        {
            if (condition())
            {
                return true
            }
            Thread.sleep(20L)
        }
        return condition()
    }
}
