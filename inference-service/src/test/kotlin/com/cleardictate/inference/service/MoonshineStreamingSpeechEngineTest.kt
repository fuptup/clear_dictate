package com.cleardictate.inference.service

import org.junit.After
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies bounded audio buffering and serialized stop/cancellation without Android hardware.
 */
class MoonshineStreamingSpeechEngineTest
{
    private val audioSource = FakePcmAudioSource()
    private val recognitionBackend = RecordingRecognitionBackend()
    private val speechEngine = MoonshineStreamingSpeechEngine(
        audioSourceFactory = PcmAudioSourceFactory { _ -> audioSource },
        recognitionBackend = recognitionBackend
    )

    @After
    fun closeEngine()
    {
        speechEngine.close()
    }

    @Test
    fun `repeated capture reuses a fixed number of Pulse Code Modulation buffers`()
    {
        recognitionBackend.expectedAudioCallbacks = CountDownLatch(20)
        speechEngine.start(RecordingSpeechEventListener())

        assertTrue(recognitionBackend.expectedAudioCallbacks.await(2, TimeUnit.SECONDS))
        speechEngine.stopAndFlush()

        assertTrue(recognitionBackend.sampleCounts.take(20).all { sampleCount -> sampleCount == 8 })
        assertTrue(
            recognitionBackend.sampleArrayIdentities.take(20).distinct().size <= 16,
            "Capture allocated more arrays than the fixed buffer pool."
        )
        assertEquals(1, recognitionBackend.stopAndFlushCount.get())
        assertEquals(0, recognitionBackend.cancelAndFlushCount.get())
        assertTrue(recognitionBackend.sampleArrays.all { samples -> samples.all { sample -> sample == 0.toShort() } })
    }

    @Test
    fun `a final partial buffer forwards only its actual sample count`()
    {
        audioSource.partialFirstReadSampleCount = 3
        recognitionBackend.expectedAudioCallbacks = CountDownLatch(1)
        speechEngine.start(RecordingSpeechEventListener())
        assertTrue(audioSource.firstReadCompleted.await(2, TimeUnit.SECONDS))

        speechEngine.stopAndFlush()

        assertEquals(listOf(3), recognitionBackend.sampleCounts)
    }

    @Test
    fun `cancellation stops capture and uses the cancellation flush path`()
    {
        speechEngine.start(RecordingSpeechEventListener())
        assertTrue(audioSource.firstReadCompleted.await(2, TimeUnit.SECONDS))

        speechEngine.requestCancellation()
        speechEngine.cancelAndDrain()

        assertTrue(audioSource.stopCalled.get())
        assertEquals(0, recognitionBackend.stopAndFlushCount.get())
        assertEquals(1, recognitionBackend.cancelAndFlushCount.get())
    }

    @Test
    fun `capture failure is reported once and cancels native streaming`()
    {
        audioSource.failFirstRead = true
        val listener = RecordingSpeechEventListener()
        speechEngine.start(listener)

        assertTrue(listener.failureReported.await(2, TimeUnit.SECONDS))
        speechEngine.cancelAndDrain()

        assertEquals(1, listener.failureCount.get())
        assertEquals(1, recognitionBackend.cancelAndFlushCount.get())
        assertTrue(recognitionBackend.sampleArrays.all { samples -> samples.all { sample -> sample == 0.toShort() } })
    }

    @Test
    fun `recognition failure drains and permits a later session`()
    {
        recognitionBackend.failNextAudioAcceptance.set(true)
        val firstListener = RecordingSpeechEventListener()
        speechEngine.start(firstListener)

        assertTrue(firstListener.failureReported.await(2, TimeUnit.SECONDS))
        speechEngine.cancelAndDrain()

        recognitionBackend.expectedAudioCallbacks = CountDownLatch(1)
        audioSource.resetForAnotherSession()
        speechEngine.start(RecordingSpeechEventListener())
        assertTrue(recognitionBackend.expectedAudioCallbacks.await(2, TimeUnit.SECONDS))
        speechEngine.cancelAndDrain()

        assertEquals(2, recognitionBackend.cancelAndFlushCount.get())
    }

    @Test
    fun `audio route or focus interruption reports failure and drains recognition`()
    {
        val interruptedAudioSource = FakePcmAudioSource()
        val interruptedRecognitionBackend = RecordingRecognitionBackend()
        lateinit var interruptionListener: () -> Unit
        val interruptedEngine = MoonshineStreamingSpeechEngine(
            audioSourceFactory = PcmAudioSourceFactory { suppliedInterruptionListener ->
                interruptionListener = suppliedInterruptionListener
                interruptedAudioSource
            },
            recognitionBackend = interruptedRecognitionBackend
        )
        val listener = RecordingSpeechEventListener()

        try
        {
            interruptedEngine.start(listener)
            assertTrue(interruptedAudioSource.firstReadCompleted.await(2, TimeUnit.SECONDS))

            interruptionListener()

            assertTrue(listener.failureReported.await(2, TimeUnit.SECONDS))
            interruptedEngine.cancelAndDrain()
            assertEquals(1, interruptedRecognitionBackend.cancelAndFlushCount.get())
        }
        finally
        {
            interruptedEngine.close()
        }
    }
}

private class FakePcmAudioSource : PcmAudioSource
{
    override val sampleRateHertz = 16_000
    override val preferredReadSampleCount = 8
    val stopCalled = AtomicBoolean(false)
    val firstReadCompleted = CountDownLatch(1)
    var partialFirstReadSampleCount: Int? = null
    var failFirstRead = false
    private val readCount = AtomicInteger(0)

    override fun start()
    {
    }

    override fun read(destination: ShortArray, destinationOffset: Int, requestedSampleCount: Int): Int
    {
        val currentReadCount = readCount.incrementAndGet()

        if (failFirstRead && currentReadCount == 1)
        {
            firstReadCompleted.countDown()
            return -7
        }

        if (stopCalled.get())
        {
            return -3
        }

        val sampleCount = if (currentReadCount == 1)
        {
            partialFirstReadSampleCount ?: requestedSampleCount
        }
        else if (partialFirstReadSampleCount != null)
        {
            while (!stopCalled.get())
            {
                Thread.yield()
            }
            return -3
        }
        else
        {
            requestedSampleCount
        }

        for (sampleOffset in 0 until sampleCount)
        {
            destination[destinationOffset + sampleOffset] = (sampleOffset + 1).toShort()
        }
        firstReadCompleted.countDown()
        return sampleCount
    }

    override fun stop()
    {
        stopCalled.set(true)
    }

    override fun close()
    {
        stop()
    }

    fun resetForAnotherSession()
    {
        stopCalled.set(false)
    }
}

private class RecordingRecognitionBackend : StreamingRecognitionBackend
{
    var expectedAudioCallbacks = CountDownLatch(0)
    val sampleCounts = Collections.synchronizedList(mutableListOf<Int>())
    val sampleArrayIdentities = Collections.synchronizedList(mutableListOf<Int>())
    val stopAndFlushCount = AtomicInteger(0)
    val cancelAndFlushCount = AtomicInteger(0)
    val failNextAudioAcceptance = AtomicBoolean(false)
    val sampleArrays = Collections.synchronizedList(mutableListOf<ShortArray>())

    override fun startSession(listener: StreamingSpeechEventListener)
    {
    }

    override fun acceptPcm16(samples: ShortArray, sampleCount: Int, sampleRateHertz: Int)
    {
        sampleCounts += sampleCount
        sampleArrayIdentities += System.identityHashCode(samples)
        sampleArrays += samples
        expectedAudioCallbacks.countDown()

        if (failNextAudioAcceptance.compareAndSet(true, false))
        {
            throw IllegalStateException("Injected recognition failure.")
        }
    }

    override fun stopAndFlush()
    {
        stopAndFlushCount.incrementAndGet()
    }

    override fun cancelAndFlush()
    {
        cancelAndFlushCount.incrementAndGet()
    }

    override fun close()
    {
    }
}

private class RecordingSpeechEventListener : StreamingSpeechEventListener
{
    val failureReported = CountDownLatch(1)
    val failureCount = AtomicInteger(0)

    override fun onPartial(lineIdentifier: Long, text: String)
    {
    }

    override fun onCompleted(lineIdentifier: Long, text: String)
    {
    }

    override fun onFailure()
    {
        failureCount.incrementAndGet()
        failureReported.countDown()
    }
}
