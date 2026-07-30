package com.cleardictate.desktop.inference

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Creates a process on the input/output dispatcher while preserving ownership across
 * cancellation. If cancellation wins before the caller receives the Process object,
 * the exact newly created process is terminated by the continuation handoff.
 */
internal class CancellationSafeProcessStarter(
    private val processCreationDispatcher: CoroutineDispatcher = Dispatchers.IO
)
{
    suspend fun start(startOperation: () -> Process): Process
    {
        return suspendCancellableCoroutine { continuation ->
            processCreationDispatcher.dispatch(
                continuation.context,
                Runnable {
                    if (!continuation.isActive)
                    {
                        return@Runnable
                    }

                    try
                    {
                        val startedProcess = startOperation()
                        continuation.resume(startedProcess) { _, unclaimedProcess, _ ->
                            terminateUnclaimedProcess(unclaimedProcess)
                        }
                    }
                    catch (throwable: Throwable)
                    {
                        if (continuation.isActive)
                        {
                            continuation.resumeWithException(throwable)
                        }
                    }
                }
            )
        }
    }

    private fun terminateUnclaimedProcess(process: Process)
    {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.destroyForcibly() }
    }
}
