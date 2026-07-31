package com.cleardictate.desktop.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CaptureDeviceEnumerationException : IllegalStateException("Windows microphone inputs could not be listed.")

/**
 * Runs the short-lived native enumerator and returns only validated capture-device records.
 */
class WindowsCaptureDeviceProvider(
    private val enumeratorExecutable: Path,
    private val enumerationTimeoutMilliseconds: Long = 5_000
)
{
    init
    {
        require(enumerationTimeoutMilliseconds > 0) { "The device-enumeration timeout must be positive." }
    }

    suspend fun listActiveCaptureDevices(): List<WindowsCaptureDevice>
    {
        if (!Files.isRegularFile(enumeratorExecutable))
        {
            throw CaptureDeviceEnumerationException()
        }

        val process = try
        {
            CancellationSafeProcessStarter().start {
                ProcessBuilder(enumeratorExecutable.toString()).start()
            }
        }
        catch (cancellation: CancellationException)
        {
            throw cancellation
        }
        catch (_: Exception)
        {
            throw CaptureDeviceEnumerationException()
        }

        return coroutineScope {
            val standardOutput = async(Dispatchers.IO) {
                process.inputStream.readNBytes(MAXIMUM_PAYLOAD_BYTES + 1)
            }
            val standardError = async(Dispatchers.IO) {
                process.errorStream.readNBytes(MAXIMUM_DIAGNOSTIC_BYTES + 1)
            }

            try
            {
                runCatching { process.outputStream.close() }
                val exited = withContext(Dispatchers.IO) {
                    process.waitFor(enumerationTimeoutMilliseconds, TimeUnit.MILLISECONDS)
                }
                if (!exited)
                {
                    throw CaptureDeviceEnumerationException()
                }

                val payload = standardOutput.await()
                val diagnostic = standardError.await()
                if (process.exitValue() != 0 || payload.size > MAXIMUM_PAYLOAD_BYTES || diagnostic.size > MAXIMUM_DIAGNOSTIC_BYTES)
                {
                    throw CaptureDeviceEnumerationException()
                }

                try
                {
                    CaptureDeviceListPayloadCodec.decode(payload)
                }
                catch (_: CaptureDeviceListPayloadException)
                {
                    throw CaptureDeviceEnumerationException()
                }
            }
            finally
            {
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                if (process.isAlive)
                {
                    runCatching { process.destroyForcibly() }
                }
            }
        }
    }

    private companion object
    {
        const val MAXIMUM_PAYLOAD_BYTES = 64 * 1024
        const val MAXIMUM_DIAGNOSTIC_BYTES = 4 * 1024
    }
}
