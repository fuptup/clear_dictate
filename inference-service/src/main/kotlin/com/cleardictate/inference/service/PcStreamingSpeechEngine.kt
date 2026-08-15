package com.cleardictate.inference.service

import com.cleardictate.inference.remote.RemoteDictationProtocol
import com.cleardictate.inference.remote.RemotePcmAudio
import kotlinx.coroutines.runBlocking

/**
 * Treats a verified PC health response as the speech-resource lease required by the existing coordinator.
 */
class PcEndpointVerifiedSpeechModelProvider(
    private val endpointProvider: PcEndpointProvider,
    private val transport: PcDictationTransport
) : VerifiedSpeechModelProvider
{
    override fun acquireVerifiedModel(cancellationSignal: InferenceCancellationSignal): VerifiedSpeechModelLease
    {
        check(!cancellationSignal.isCancellationRequested) { "PC verification was cancelled." }
        val endpoint = checkNotNull(endpointProvider.load()) { "No PC endpoint is paired." }
        check(runBlocking { transport.checkHealth(endpoint) } == PcHealthStatus.READY) { "The paired PC is not ready." }
        check(!cancellationSignal.isCancellationRequested) { "PC verification was cancelled." }
        return PcEndpointLease
    }

    private object PcEndpointLease : VerifiedSpeechModelLease
    {
        override val verifiedModelDirectoryPath = "pc://paired-endpoint"
        override fun close() = Unit
    }
}

/**
 * Reuses the foreground microphone session with a completed-audio PC recognition backend.
 */
class PcStreamingSpeechEngineFactory(
    private val audioSourceFactory: PcmAudioSourceFactory,
    private val endpointProvider: PcEndpointProvider,
    private val transport: PcDictationTransport
) : StreamingSpeechEngineFactory
{
    override fun open(verifiedModelLease: VerifiedSpeechModelLease): StreamingSpeechEngine
    {
        return BufferedStreamingSpeechEngine(
            audioSourceFactory = audioSourceFactory,
            recognitionBackend = PcCompletedAudioRecognitionBackend(endpointProvider, transport)
        )
    }
}

/**
 * Accumulates bounded PCM16 chunks, uploads only after release, and emits the PC-polished result as the operation transcript.
 */
internal class PcCompletedAudioRecognitionBackend(
    private val endpointProvider: PcEndpointProvider,
    private val transport: PcDictationTransport
) : StreamingRecognitionBackend
{
    private val audioChunks = mutableListOf<ShortArray>()
    private var activeListener: StreamingSpeechEventListener? = null
    private var activeEndpoint: PcDictationEndpoint? = null
    private var accumulatedSampleCount = 0

    override fun startSession(listener: StreamingSpeechEventListener)
    {
        check(activeListener == null) { "A PC dictation session is already active." }
        activeEndpoint = checkNotNull(endpointProvider.load()) { "No PC endpoint is paired." }
        activeListener = listener
        accumulatedSampleCount = 0
    }

    override fun acceptPcm16(samples: ShortArray, sampleCount: Int, sampleRateHertz: Int)
    {
        check(activeListener != null) { "No PC dictation session is active." }
        check(sampleRateHertz == RemoteDictationProtocol.SAMPLE_RATE_HERTZ) { "PC dictation requires 16 kHz audio." }
        check(sampleCount in 1..samples.size) { "The Pulse Code Modulation sample count is invalid." }
        check(accumulatedSampleCount.toLong() + sampleCount <= RemoteDictationProtocol.MAXIMUM_SAMPLE_COUNT) {
            "The PC dictation recording exceeds the shared protocol boundary."
        }
        audioChunks += samples.copyOf(sampleCount)
        accumulatedSampleCount += sampleCount
    }

    override fun stopAndFlush()
    {
        val listener = checkNotNull(activeListener) { "No PC dictation session is active." }
        val endpoint = checkNotNull(activeEndpoint) { "No PC endpoint is paired." }
        if (accumulatedSampleCount == 0)
        {
            clearSession()
            listener.onFailure()
            return
        }

        val completedAudio = ShortArray(accumulatedSampleCount)
        var destinationOffset = 0
        try
        {
            audioChunks.forEach { chunk ->
                chunk.copyInto(completedAudio, destinationOffset)
                destinationOffset += chunk.size
            }
            scrubChunks()
            val polishedTranscript = runBlocking {
                transport.dictate(
                    endpoint,
                    RemotePcmAudio(RemoteDictationProtocol.SAMPLE_RATE_HERTZ, completedAudio)
                )
            }
            listener.onCompleted(1L, polishedTranscript)
        }
        finally
        {
            completedAudio.fill(0)
            clearSession()
        }
    }

    override fun cancelAndFlush()
    {
        clearSession()
    }

    override fun close()
    {
        clearSession()
    }

    private fun clearSession()
    {
        scrubChunks()
        activeListener = null
        activeEndpoint = null
        accumulatedSampleCount = 0
    }

    private fun scrubChunks()
    {
        audioChunks.forEach { chunk -> chunk.fill(0) }
        audioChunks.clear()
    }
}
