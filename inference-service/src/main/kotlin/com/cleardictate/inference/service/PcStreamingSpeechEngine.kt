package com.cleardictate.inference.service

import com.cleardictate.inference.remote.RemoteDictationProtocol
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
 * Reuses the foreground microphone session with a live PC recognition backend.
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
            recognitionBackend = PcLiveAudioRecognitionBackend(endpointProvider, transport)
        )
    }
}

/**
 * Opens one authenticated request at microphone activation and forwards every bounded PCM16 chunk before release.
 */
internal class PcLiveAudioRecognitionBackend(
    private val endpointProvider: PcEndpointProvider,
    private val transport: PcDictationTransport
) : StreamingRecognitionBackend
{
    private var activeListener: StreamingSpeechEventListener? = null
    private var activeStream: PcDictationStream? = null
    private var accumulatedSampleCount = 0

    override fun startSession(listener: StreamingSpeechEventListener)
    {
        check(activeListener == null) { "A PC dictation session is already active." }
        val endpoint = checkNotNull(endpointProvider.load()) { "No PC endpoint is paired." }
        activeStream = transport.openDictation(endpoint)
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
        checkNotNull(activeStream) { "No PC dictation stream is active." }.sendPcm16(samples, sampleCount)
        accumulatedSampleCount += sampleCount
    }

    override fun stopAndFlush()
    {
        val listener = checkNotNull(activeListener) { "No PC dictation session is active." }
        val stream = checkNotNull(activeStream) { "No PC dictation stream is active." }
        if (accumulatedSampleCount == 0)
        {
            stream.cancel()
            clearSession()
            listener.onFailure()
            return
        }

        try
        {
            val polishedTranscript = stream.finish()
            listener.onCompleted(1L, polishedTranscript)
        }
        finally
        {
            clearSession()
        }
    }

    override fun cancelAndFlush()
    {
        activeStream?.cancel()
        clearSession()
    }

    override fun close()
    {
        activeStream?.cancel()
        clearSession()
    }

    private fun clearSession()
    {
        activeListener = null
        activeStream = null
        accumulatedSampleCount = 0
    }
}
