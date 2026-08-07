package com.cleardictate.inference.service

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
 * Feeds bounded, pooled microphone buffers to one serialized recognition backend.
 *
 * The capture thread performs no allocation in its repeated read loop. A fixed pool allows up to
 * 1.6 seconds of buffering at the default 100-millisecond chunk size before capture backpressure
 * becomes visible, while the recognition thread remains the sole caller of streaming native work.
 */
internal class BufferedStreamingSpeechEngine(
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
