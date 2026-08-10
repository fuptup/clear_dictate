package com.cleardictate.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/**
 * Retrieves one retained WAV on demand and owns its playback lifecycle independently from the history user interface.
 */
class DesktopDictationAudioPlayer(
    private val history: SqliteDesktopDictationHistory,
    private val audioOutput: WavAudioOutput = JavaSoundWavAudioOutput()
) : AutoCloseable
{
    /**
     * Replaces any active recording with the selected history entry and scrubs the temporary database payload after the output accepts it.
     */
    suspend fun play(identifier: Long)
    {
        val wavAudio = requireNotNull(history.readWavAudio(identifier)) { "The selected dictation no longer exists." }
        try
        {
            withContext(Dispatchers.IO) { audioOutput.play(wavAudio) }
        }
        finally
        {
            wavAudio.fill(0)
        }
    }

    override fun close()
    {
        audioOutput.close()
    }
}

/**
 * Accepts complete WAV files so selection and database behavior can be verified without requiring an audio device in tests.
 */
fun interface WavAudioOutput : AutoCloseable
{
    fun play(wavAudio: ByteArray)

    override fun close()
    {
    }
}

/**
 * Plays one WAV through the Windows default output device and guarantees that repeated row clicks do not overlap recordings.
 */
private class JavaSoundWavAudioOutput : WavAudioOutput
{
    private val ownershipLock = Any()
    private var activeClip: Clip? = null

    override fun play(wavAudio: ByteArray)
    {
        val replacement = AudioSystem.getClip()
        try
        {
            ByteArrayInputStream(wavAudio).use { bytes ->
                BufferedInputStream(bytes).use { bufferedBytes ->
                    AudioSystem.getAudioInputStream(bufferedBytes).use(replacement::open)
                }
            }
            synchronized(ownershipLock)
            {
                activeClip?.stop()
                activeClip?.close()
                activeClip = replacement
                replacement.start()
            }
        }
        catch (throwable: Throwable)
        {
            replacement.close()
            throw throwable
        }
    }

    override fun close()
    {
        synchronized(ownershipLock)
        {
            activeClip?.stop()
            activeClip?.close()
            activeClip = null
        }
    }
}
