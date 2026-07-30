package com.cleardictate.inference.service

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Small native-facing boundary that can be replaced by a deterministic fake in audio tests.
 */
internal interface StreamingRecognitionBackend : AutoCloseable
{
    fun startSession(listener: StreamingSpeechEventListener)

    fun acceptPcm16(samples: ShortArray, sampleCount: Int, sampleRateHertz: Int)

    fun stopAndFlush()

    fun cancelAndFlush()
}

/**
 * Opens Moonshine Tiny Streaming only after the complete verified group lease is live.
 */
class MoonshineStreamingSpeechEngineFactory(
    private val audioSourceFactory: PcmAudioSourceFactory
) : StreamingSpeechEngineFactory
{
    override fun open(verifiedModelLease: VerifiedSpeechModelLease): StreamingSpeechEngine
    {
        val transcriber = Transcriber(
            listOf(TranscriberOption("return_audio_data", "false"))
        )

        try
        {
            transcriber.loadFromFiles(
                verifiedModelLease.verifiedModelDirectoryPath,
                JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
            )
            return MoonshineStreamingSpeechEngine(
                audioSourceFactory = audioSourceFactory,
                recognitionBackend = MoonshineRecognitionBackend(transcriber)
            )
        }
        catch (failure: Throwable)
        {
            transcriber.close()
            throw failure
        }
    }
}

/**
 * Feeds bounded, pooled microphone buffers to one serialized Moonshine session.
 *
 * The capture thread performs no allocation in its repeated read loop. A fixed pool allows up to
 * 1.6 seconds of buffering at the default 100-millisecond chunk size before capture backpressure
 * becomes visible, while the recognition thread remains the sole caller of streaming native work.
 */
internal class MoonshineStreamingSpeechEngine(
    private val audioSourceFactory: PcmAudioSourceFactory,
    private val recognitionBackend: StreamingRecognitionBackend
) : StreamingSpeechEngine
{
    private val lifecycleLock = Any()
    private var activeSession: AudioRecognitionSession? = null
    private var closed = false

    override fun start(listener: StreamingSpeechEventListener)
    {
        val session: AudioRecognitionSession

        synchronized(lifecycleLock)
        {
            check(!closed) { "The speech engine is closed." }
            check(activeSession == null) { "A speech session is already active." }

            lateinit var createdSession: AudioRecognitionSession
            val audioSource = audioSourceFactory.create {
                createdSession.handleAudioInterruption()
            }
            createdSession = AudioRecognitionSession(
                audioSource = audioSource,
                listener = listener,
                recognitionBackend = recognitionBackend
            )
            session = createdSession
            activeSession = session
        }

        try
        {
            session.start()
        }
        catch (failure: Throwable)
        {
            synchronized(lifecycleLock)
            {
                if (activeSession === session)
                {
                    activeSession = null
                }
            }
            session.closeAfterFailedStart()
            throw failure
        }
    }

    override fun stopAndFlush()
    {
        val session = synchronized(lifecycleLock)
        {
            activeSession
        } ?: return

        session.stopAndFlush()
        clearSession(session)
    }

    override fun requestCancellation()
    {
        val session = synchronized(lifecycleLock)
        {
            activeSession
        }
        session?.requestCancellation()
    }

    override fun cancelAndDrain()
    {
        val session = synchronized(lifecycleLock)
        {
            activeSession
        } ?: return

        session.cancelAndDrain()
        clearSession(session)
    }

    override fun close()
    {
        val session: AudioRecognitionSession?

        synchronized(lifecycleLock)
        {
            if (closed)
            {
                return
            }

            closed = true
            session = activeSession
        }

        session?.requestCancellation()
        session?.cancelAndDrain()
        session?.let(::clearSession)
        recognitionBackend.close()
    }

    private fun clearSession(session: AudioRecognitionSession)
    {
        synchronized(lifecycleLock)
        {
            if (activeSession === session)
            {
                activeSession = null
            }
        }
    }
}

/**
 * Holds all per-recording threads, queues, and buffers so terminal cleanup is deterministic.
 */
private class AudioRecognitionSession(
    private val audioSource: PcmAudioSource,
    private val listener: StreamingSpeechEventListener,
    private val recognitionBackend: StreamingRecognitionBackend
)
{
    private val acceptingAudio = AtomicBoolean(true)
    private val cancellationRequested = AtomicBoolean(false)
    private val captureFinished = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val backendFinalized = AtomicBoolean(false)
    private val reusableBuffers: List<ReusablePcmBuffer>
    private val availableBuffers: ArrayBlockingQueue<ReusablePcmBuffer>
    private val filledBuffers: ArrayBlockingQueue<ReusablePcmBuffer>
    private val captureThread: Thread
    private val recognitionThread: Thread

    init
    {
        reusableBuffers = List(BUFFER_POOL_SIZE) {
            ReusablePcmBuffer(ShortArray(audioSource.preferredReadSampleCount))
        }
        availableBuffers = ArrayBlockingQueue(BUFFER_POOL_SIZE)
        filledBuffers = ArrayBlockingQueue(BUFFER_POOL_SIZE)
        availableBuffers.addAll(reusableBuffers)
        captureThread = Thread(::captureAudio, "cleardictate-audio-capture").apply {
            isDaemon = true
        }
        recognitionThread = Thread(::recognizeAudio, "cleardictate-audio-recognition").apply {
            isDaemon = true
        }
    }

    fun start()
    {
        recognitionBackend.startSession(listener)
        audioSource.start()
        recognitionThread.start()
        captureThread.start()
    }

    fun stopAndFlush()
    {
        acceptingAudio.set(false)
        safelyStopAudioSource()
        joinSessionThreads()
        scrubAllAudioBuffers()
    }

    fun requestCancellation()
    {
        cancellationRequested.set(true)
        acceptingAudio.set(false)
        safelyStopAudioSource()
        captureThread.interrupt()
        recognitionThread.interrupt()
    }

    fun handleAudioInterruption()
    {
        cancellationRequested.set(true)
        acceptingAudio.set(false)
        safelyStopAudioSource()
        captureThread.interrupt()
        recognitionThread.interrupt()
        reportFailureOnce()
    }

    fun cancelAndDrain()
    {
        requestCancellation()

        joinSessionThreads()
        scrubAllAudioBuffers()
    }

    fun closeAfterFailedStart()
    {
        acceptingAudio.set(false)
        captureFinished.set(true)

        try
        {
            finalizeBackend(cancelled = true)
        }
        finally
        {
            scrubAllAudioBuffers()
            audioSource.close()
        }
    }

    private fun captureAudio()
    {
        try
        {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
        catch (_: RuntimeException)
        {
            // Thread priority is an optimization; capture remains correct if a device rejects it.
        }

        try
        {
            while (acceptingAudio.get())
            {
                val reusableBuffer = availableBuffers.take()
                var filledSampleCount = 0

                while (acceptingAudio.get() && filledSampleCount < reusableBuffer.samples.size)
                {
                    val samplesRead = audioSource.read(
                        reusableBuffer.samples,
                        filledSampleCount,
                        reusableBuffer.samples.size - filledSampleCount
                    )

                    if (samplesRead < 0)
                    {
                        if (!acceptingAudio.get())
                        {
                            break
                        }

                        throw IllegalStateException("Android microphone read failed with code $samplesRead.")
                    }

                    if (samplesRead == 0)
                    {
                        continue
                    }

                    filledSampleCount += samplesRead
                }

                if (filledSampleCount > 0)
                {
                    reusableBuffer.sampleCount = filledSampleCount
                    filledBuffers.put(reusableBuffer)
                }
                else
                {
                    availableBuffers.put(reusableBuffer)
                }
            }
        }
        catch (_: InterruptedException)
        {
            Thread.currentThread().interrupt()
        }
        catch (_: Throwable)
        {
            reportFailureOnce()
            cancellationRequested.set(true)
        }
        finally
        {
            captureFinished.set(true)
        }
    }

    private fun recognizeAudio()
    {
        var forwardedSampleCount = 0L

        try
        {
            while (!captureFinished.get() || filledBuffers.isNotEmpty())
            {
                val reusableBuffer = filledBuffers.poll(QUEUE_POLL_MILLISECONDS, TimeUnit.MILLISECONDS)
                    ?: continue

                try
                {
                    if (!cancellationRequested.get())
                    {
                        if (forwardedSampleCount + reusableBuffer.sampleCount > MAXIMUM_SESSION_SAMPLE_COUNT)
                        {
                            cancellationRequested.set(true)
                            acceptingAudio.set(false)
                            safelyStopAudioSource()
                            captureThread.interrupt()
                            reportFailureOnce()
                            continue
                        }

                        listener.onAudioLevel(calculateNormalizedInputLevel(reusableBuffer))
                        recognitionBackend.acceptPcm16(
                            reusableBuffer.samples,
                            reusableBuffer.sampleCount,
                            audioSource.sampleRateHertz
                        )
                        forwardedSampleCount += reusableBuffer.sampleCount
                    }
                }
                finally
                {
                    java.util.Arrays.fill(
                        reusableBuffer.samples,
                        0,
                        reusableBuffer.sampleCount,
                        0.toShort()
                    )
                    reusableBuffer.sampleCount = 0
                    availableBuffers.put(reusableBuffer)
                }
            }

            if (cancellationRequested.get())
            {
                finalizeBackend(cancelled = true)
            }
            else
            {
                finalizeBackend(cancelled = false)
            }
        }
        catch (_: InterruptedException)
        {
            Thread.currentThread().interrupt()
            cancellationRequested.set(true)
            finalizeBackend(cancelled = true)
        }
        catch (_: Throwable)
        {
            cancellationRequested.set(true)
            acceptingAudio.set(false)
            safelyStopAudioSource()
            captureThread.interrupt()

            try
            {
                finalizeBackend(cancelled = true)
            }
            catch (_: Throwable)
            {
                // The original native failure remains the client-visible failure.
            }
            reportFailureOnce()
        }
        finally
        {
            discardQueuedBuffers()
            audioSource.close()
        }
    }

    private fun joinSessionThreads()
    {
        captureThread.join(THREAD_JOIN_TIMEOUT_MILLISECONDS)
        recognitionThread.join(THREAD_JOIN_TIMEOUT_MILLISECONDS)

        if (captureThread.isAlive || recognitionThread.isAlive)
        {
            throw IllegalStateException("Speech-session threads did not terminate within the safety timeout.")
        }
    }

    private fun safelyStopAudioSource()
    {
        try
        {
            audioSource.stop()
        }
        catch (_: IllegalStateException)
        {
            reportFailureOnce()
        }
    }

    private fun discardQueuedBuffers()
    {
        while (true)
        {
            val reusableBuffer = filledBuffers.poll() ?: break
            java.util.Arrays.fill(
                reusableBuffer.samples,
                0,
                reusableBuffer.sampleCount,
                0.toShort()
            )
            reusableBuffer.sampleCount = 0
            availableBuffers.offer(reusableBuffer)
        }
    }

    private fun finalizeBackend(cancelled: Boolean)
    {
        if (!backendFinalized.compareAndSet(false, true))
        {
            return
        }

        if (cancelled)
        {
            recognitionBackend.cancelAndFlush()
        }
        else
        {
            recognitionBackend.stopAndFlush()
        }
    }

    private fun scrubAllAudioBuffers()
    {
        reusableBuffers.forEach { reusableBuffer ->
            reusableBuffer.samples.fill(0)
            reusableBuffer.sampleCount = 0
        }
    }

    private fun reportFailureOnce()
    {
        if (failureReported.compareAndSet(false, true))
        {
            listener.onFailure()
        }
    }

    private fun calculateNormalizedInputLevel(reusableBuffer: ReusablePcmBuffer): Float
    {
        if (reusableBuffer.sampleCount == 0)
        {
            return 0.0f
        }

        var squaredSampleSum = 0.0

        for (sampleIndex in 0 until reusableBuffer.sampleCount)
        {
            val normalizedSample = reusableBuffer.samples[sampleIndex].toDouble() / Short.MAX_VALUE
            squaredSampleSum += normalizedSample * normalizedSample
        }

        return sqrt(squaredSampleSum / reusableBuffer.sampleCount).toFloat().coerceIn(0.0f, 1.0f)
    }

    private data class ReusablePcmBuffer(
        val samples: ShortArray,
        var sampleCount: Int = 0
    )

    private companion object
    {
        const val BUFFER_POOL_SIZE = 16
        const val QUEUE_POLL_MILLISECONDS = 20L
        const val THREAD_JOIN_TIMEOUT_MILLISECONDS = 3_000L
        const val MAXIMUM_SESSION_SAMPLE_COUNT = 16_000L * 60L * 5L
    }
}

/**
 * Keeps every Moonshine call on the recognition thread and copies only text out of native events.
 */
private class MoonshineRecognitionBackend(
    private val transcriber: Transcriber
) : StreamingRecognitionBackend
{
    private var activeListener: StreamingSpeechEventListener? = null
    private var streamHandle = INVALID_STREAM_HANDLE
    private var reusableFloatSamples = FloatArray(0)
    private val transcriptEventListener = java.util.function.Consumer<TranscriptEvent> { event ->
        deliverTranscriptEvent(event)
    }

    init
    {
        transcriber.addListener(transcriptEventListener)
    }

    override fun startSession(listener: StreamingSpeechEventListener)
    {
        check(streamHandle == INVALID_STREAM_HANDLE) { "A Moonshine stream is already active." }
        activeListener = listener
        streamHandle = transcriber.createStream()
        transcriber.startStream(streamHandle)
    }

    override fun acceptPcm16(samples: ShortArray, sampleCount: Int, sampleRateHertz: Int)
    {
        check(streamHandle != INVALID_STREAM_HANDLE) { "No Moonshine stream is active." }
        check(sampleCount in 1..samples.size) { "The Pulse Code Modulation sample count is invalid." }

        if (reusableFloatSamples.size != sampleCount)
        {
            reusableFloatSamples = FloatArray(sampleCount)
        }

        for (sampleIndex in 0 until sampleCount)
        {
            reusableFloatSamples[sampleIndex] = samples[sampleIndex] / 32768.0f
        }

        try
        {
            transcriber.addAudioToStream(streamHandle, reusableFloatSamples, sampleRateHertz)
        }
        finally
        {
            // Moonshine copies the Java samples synchronously into its stream before this call returns.
            reusableFloatSamples.fill(0.0f)
        }
    }

    override fun stopAndFlush()
    {
        finishStream()
    }

    override fun cancelAndFlush()
    {
        discardStream()
    }

    override fun close()
    {
        if (streamHandle != INVALID_STREAM_HANDLE)
        {
            finishStream()
        }
        transcriber.removeListener(transcriptEventListener)
        transcriber.close()
    }

    private fun finishStream()
    {
        val handleToClose = streamHandle

        if (handleToClose == INVALID_STREAM_HANDLE)
        {
            return
        }

        try
        {
            transcriber.stopStream(handleToClose)
        }
        finally
        {
            try
            {
                transcriber.freeStream(handleToClose)
            }
            finally
            {
                streamHandle = INVALID_STREAM_HANDLE
                activeListener = null
                reusableFloatSamples.fill(0.0f)
            }
        }
    }

    private fun discardStream()
    {
        val handleToClose = streamHandle

        if (handleToClose == INVALID_STREAM_HANDLE)
        {
            return
        }

        activeListener = null

        try
        {
            transcriber.freeStream(handleToClose)
        }
        finally
        {
            streamHandle = INVALID_STREAM_HANDLE
            reusableFloatSamples.fill(0.0f)
        }
    }

    private fun deliverTranscriptEvent(event: TranscriptEvent)
    {
        val listener = activeListener ?: return

        when (event)
        {
            is TranscriptEvent.LineStarted -> listener.onSpeechDetected()
            is TranscriptEvent.LineTextChanged -> listener.onPartial(
                lineIdentifier = event.line.id,
                text = event.line.text.orEmpty()
            )
            is TranscriptEvent.LineCompleted -> listener.onCompleted(
                lineIdentifier = event.line.id,
                text = event.line.text.orEmpty()
            )
            is TranscriptEvent.Error -> listener.onFailure()
        }
    }

    private companion object
    {
        const val INVALID_STREAM_HANDLE = -1
    }
}
