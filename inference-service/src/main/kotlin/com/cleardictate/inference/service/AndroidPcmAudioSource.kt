package com.cleardictate.inference.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supplies blocking 16-bit mono microphone samples without exposing Android types to coordination.
 */
interface PcmAudioSource : AutoCloseable
{
    val sampleRateHertz: Int

    val preferredReadSampleCount: Int

    fun start()

    fun read(destination: ShortArray, destinationOffset: Int, requestedSampleCount: Int): Int

    fun stop()
}

fun interface PcmAudioSourceFactory
{
    fun create(interruptionListener: () -> Unit): PcmAudioSource
}

/**
 * Owns one Android AudioRecord instance for one visible recording session.
 *
 * Microphone permission is deliberately checked by the visible application or keyboard client
 * before the inference service is promoted and this source is created.
 */
class AndroidPcmAudioSourceFactory(
    context: Context
) : PcmAudioSourceFactory
{
    private val applicationContext = context.applicationContext

    override fun create(interruptionListener: () -> Unit): PcmAudioSource
    {
        return AndroidPcmAudioSource(applicationContext, interruptionListener)
    }
}

private class AndroidPcmAudioSource(
    context: Context,
    private val interruptionListener: () -> Unit
) : PcmAudioSource
{
    override val sampleRateHertz = SAMPLE_RATE_HERTZ
    override val preferredReadSampleCount: Int
    private val audioRecord: AudioRecord
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureActive = AtomicBoolean(false)
    private val interruptionReported = AtomicBoolean(false)
    private var initialRoutedDeviceIdentifier: Int? = null
    private var routingObserverRegistered = false
    private var recordingConfigurationCallbackRegistered = false
    private var audioFocusHeld = false
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        {
            reportInterruption()
        }
    }
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
        .build()
    private val routingChangedListener = AudioRouting.OnRoutingChangedListener { routing ->
        val currentDeviceIdentifier = routing.routedDevice?.id
        val originalDeviceIdentifier = initialRoutedDeviceIdentifier

        if (originalDeviceIdentifier != null && currentDeviceIdentifier != originalDeviceIdentifier)
        {
            reportInterruption()
        }
    }
    private val recordingConfigurationCallback = object : AudioManager.AudioRecordingCallback()
    {
        override fun onRecordingConfigChanged(configurations: List<android.media.AudioRecordingConfiguration>)
        {
            val ownConfiguration = configurations.firstOrNull { configuration ->
                configuration.clientAudioSessionId == audioRecord.audioSessionId
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ownConfiguration?.isClientSilenced == true)
            {
                reportInterruption()
            }
        }
    }

    init
    {
        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HERTZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimumBufferBytes > 0) {
            "Android did not provide a valid microphone buffer size."
        }

        preferredReadSampleCount = maxOf(
            TARGET_CHUNK_SAMPLE_COUNT,
            minimumBufferBytes / PCM_16_BYTES_PER_SAMPLE
        )
        audioRecord = buildAudioRecord(
            maxOf(
                minimumBufferBytes,
                preferredReadSampleCount * PCM_16_BYTES_PER_SAMPLE * HARDWARE_BUFFER_CHUNK_COUNT
            )
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            "Android could not initialize the microphone."
        }
    }

    override fun start()
    {
        interruptionReported.set(false)
        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
        check(focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Android did not grant exclusive recording focus."
        }
        audioFocusHeld = true
        try
        {
            registerAudioObservers()
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android did not enter the microphone recording state."
            }
            initialRoutedDeviceIdentifier = audioRecord.routedDevice?.id
            captureActive.set(true)
        }
        catch (failure: Throwable)
        {
            captureActive.set(false)
            unregisterAudioObservers()
            abandonAudioFocus()
            throw failure
        }
    }

    override fun read(destination: ShortArray, destinationOffset: Int, requestedSampleCount: Int): Int
    {
        return audioRecord.read(
            destination,
            destinationOffset,
            requestedSampleCount,
            AudioRecord.READ_BLOCKING
        )
    }

    override fun stop()
    {
        captureActive.set(false)
        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        {
            audioRecord.stop()
        }
        unregisterAudioObservers()
        abandonAudioFocus()
    }

    override fun close()
    {
        try
        {
            stop()
        }
        finally
        {
            audioRecord.release()
        }
    }

    private fun registerAudioObservers()
    {
        if (routingObserverRegistered || recordingConfigurationCallbackRegistered)
        {
            return
        }

        try
        {
            audioRecord.addOnRoutingChangedListener(routingChangedListener, mainHandler)
            routingObserverRegistered = true
            audioManager.registerAudioRecordingCallback(recordingConfigurationCallback, mainHandler)
            recordingConfigurationCallbackRegistered = true
        }
        catch (failure: Throwable)
        {
            unregisterAudioObservers()
            throw failure
        }
    }

    private fun unregisterAudioObservers()
    {
        try
        {
            if (recordingConfigurationCallbackRegistered)
            {
                audioManager.unregisterAudioRecordingCallback(recordingConfigurationCallback)
            }
        }
        finally
        {
            recordingConfigurationCallbackRegistered = false

            if (routingObserverRegistered)
            {
                try
                {
                    audioRecord.removeOnRoutingChangedListener(routingChangedListener)
                }
                finally
                {
                    routingObserverRegistered = false
                }
            }
        }
    }

    private fun abandonAudioFocus()
    {
        if (audioFocusHeld)
        {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
            audioFocusHeld = false
        }
    }

    private fun reportInterruption()
    {
        if (captureActive.get() && interruptionReported.compareAndSet(false, true))
        {
            interruptionListener()
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(bufferSizeBytes: Int): AudioRecord
    {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE_HERTZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val audioRecordBuilder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSizeBytes)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        {
            audioRecordBuilder.setPrivacySensitive(true)
        }

        return audioRecordBuilder.build()
    }

    private companion object
    {
        const val SAMPLE_RATE_HERTZ = 16_000
        const val TARGET_CHUNK_SAMPLE_COUNT = 1_600
        const val PCM_16_BYTES_PER_SAMPLE = 2
        const val HARDWARE_BUFFER_CHUNK_COUNT = 4
    }
}
